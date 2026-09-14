package io.github.wangyangxu.ailink.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.github.wangyangxu.ailink.mapper.KnowledgeChunkMapper;
import io.github.wangyangxu.ailink.mapper.KnowledgeDocumentMapper;
import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库索引服务 —— RAG 链路的写入侧（灌库）。
 * <p>
 * 三类来源统一走同一条流水线：
 * <pre>
 *   读取文本 → SHA-256 指纹去重 → 标题感知切分 → 批量向量化 → 落库 → 更新内存索引
 * </pre>
 * 设计要点：
 * <ul>
 *   <li><b>幂等</b>：source_path + content_hash（+ 向量模型）都一致才算「已索引」，
 *       重启不会重复灌库，改过的文件会被识别为变更并重建；</li>
 *   <li><b>可降级</b>：向量化失败不阻断灌库，片段照常入库（embedding 为 NULL，
 *       只走关键词召回），下一轮重建再补向量；</li>
 *   <li><b>不阻塞启动</b>：启动灌库跑在单线程守护线程上，失败只记日志。</li>
 * </ul>
 */
@Service
public class KnowledgeIndexService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexService.class);

    /** 单次调用向量模型的片段数上限（DashScope 批量接口对单次请求条数有限制） */
    private static final int EMBED_BATCH_SIZE = 10;

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of("md", "markdown", "txt", "yaml", "yml", "html", "htm", "json", "csv");

    /** 内置知识资产在类路径下的根目录（简历生成器的参考资料、模板、案例） */
    private static final String RESOURCE_ROOT = "resume-builder";

    /** 灌库结果 */
    public record IndexOutcome(long documentId, String sourcePath, String title,
                               int chunkCount, int vectorizedCount, boolean refreshed) {}

    public record IndexStats(int documents, int chunks, int vectorizedChunks, int dimensions, String embeddingModelId) {}

    private final RagProperties props;
    private final TextSplitter splitter;
    private final RetrievalIndex retrievalIndex;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeChunkMapper chunkMapper;
    private final Tika tika = new Tika();

    private final AtomicBoolean indexing = new AtomicBoolean(false);

    @Autowired
    public KnowledgeIndexService(RagProperties props,
                                 TextSplitter splitter,
                                 RetrievalIndex retrievalIndex,
                                 EmbeddingModel ragEmbeddingModel,
                                 KnowledgeDocumentMapper documentMapper,
                                 KnowledgeChunkMapper chunkMapper) {
        this.props = props;
        this.splitter = splitter;
        this.retrievalIndex = retrievalIndex;
        this.embeddingModel = ragEmbeddingModel;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    // ==================== 启动灌库 ====================

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!props.isEnabled() || !props.isIndexOnStartup()) {
            log.info("RAG 启动灌库已关闭（rag.enabled / rag.index-on-startup）");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                indexAll();
            } catch (Exception e) {
                log.error("启动灌库失败（不影响主链路）", e);
            }
        }, "rag-indexer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 全量灌库：先用库中片段恢复内存索引（避免重启后重新调用向量模型），
     * 再增量扫描内置资产与用户上传目录。
     */
    public void indexAll() {
        if (!props.isEnabled()) {
            return;
        }
        if (!indexing.compareAndSet(false, true)) {
            log.info("已有灌库任务在执行，跳过本次");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            rebuildFromDatabase();
            int resources = indexResourceAssets();
            int uploads = indexUploadedDocuments();
            log.info("灌库完成: 内置资产 {} 份, 上传文档 {} 份, 片段 {}（向量化 {}）, 耗时 {}ms",
                    resources, uploads, retrievalIndex.chunkCount(), retrievalIndex.vectorCount(),
                    System.currentTimeMillis() - start);
        } finally {
            indexing.set(false);
        }
    }

    /** 从库中恢复内存索引：片段与向量都已持久化，无需再次调用向量模型。 */
    public void rebuildFromDatabase() {
        List<KnowledgeDocument> documents = documentMapper.findAll();
        Map<Long, KnowledgeDocument> byId = new HashMap<>();
        for (KnowledgeDocument doc : documents) {
            byId.put(doc.getId(), doc);
        }
        List<KnowledgeChunk> chunks = chunkMapper.findAll();
        retrievalIndex.rebuild(chunks, byId);
    }

    // ==================== 来源扫描 ====================

    /** 内置知识资产：classpath:resume-builder/**（简历方法论、模板、案例、评价标准）。 */
    public int indexResourceAssets() {
        int indexed = 0;
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:" + RESOURCE_ROOT + "/**/*");
            List<Resource> sorted = new ArrayList<>(List.of(resources));
            sorted.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource resource : sorted) {
                if (!resource.isReadable() || !isTextResource(resource.getFilename())) {
                    continue;
                }
                String path = resourceRelativePath(resource);
                try (InputStream in = resource.getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String title = resource.getFilename() == null ? path : resource.getFilename();
                    IndexOutcome outcome = indexText(KnowledgeDocument.SOURCE_RESOURCE, path, title, text);
                    if (outcome.refreshed()) {
                        indexed++;
                    }
                } catch (Exception e) {
                    log.warn("内置资产入库失败: {} ({})", path, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("扫描内置知识资产失败: {}", e.getMessage());
        }
        return indexed;
    }

    /** 用户上传文档：data/documents、data/resumes（Tika 负责格式识别：docx/pdf/xlsx/txt）。 */
    public int indexUploadedDocuments() {
        int indexed = 0;
        for (String dir : props.getUploadDirList()) {
            Path root = Paths.get(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root, 3)) {
                List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
                for (Path file : files) {
                    try {
                        IndexOutcome outcome = indexFile(file, KnowledgeDocument.SOURCE_USER_UPLOAD, null);
                        if (outcome.refreshed()) {
                            indexed++;
                        }
                    } catch (Exception e) {
                        log.warn("上传文档入库失败: {} ({})", file, e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("扫描上传目录失败: {} ({})", dir, e.getMessage());
            }
        }
        return indexed;
    }

    // ==================== 单份文档入库 ====================

    /**
     * 用户上传文件入库（ChatFileService 实时调用）：Tika 抽文本后走统一流水线。
     * 返回结果中带片段数，供长文档摘要做覆盖率采样。
     */
    public IndexOutcome indexFile(Path file, String sourceType, String titleOverride) {
        String absolutePath = file.toAbsolutePath().toString();
        String title = titleOverride != null ? titleOverride : file.getFileName().toString();
        try {
            String text = tika.parseToString(file.toFile());
            return indexText(sourceType, absolutePath, title, text);
        } catch (Exception e) {
            log.warn("文本抽取失败: {} ({})", absolutePath, e.getMessage());
            throw new IllegalStateException("文本抽取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 核心流水线：指纹去重 → 切分 → 向量化 → 落库 → 更新内存索引。
     * 内容与向量模型都没变时直接返回（refreshed=false），不产生任何写操作。
     */
    public synchronized IndexOutcome indexText(String sourceType, String sourcePath, String title, String text) {
        String content = text == null ? "" : text;
        String hash = sha256(content);
        String modelId = embeddingModelId();

        KnowledgeDocument existing = documentMapper.findByPath(sourcePath);
        if (existing != null
                && hash.equals(existing.getContentHash())
                && modelId.equals(existing.getEmbeddingModel())
                && existing.getChunkCount() != null
                && existing.getChunkCount() > 0) {
            return new IndexOutcome(existing.getId(), sourcePath, existing.getTitle(),
                    existing.getChunkCount(), 0, false);
        }

        List<TextChunker.Chunk> pieces = splitter.split(content);
        KnowledgeDocument document = existing != null ? existing
                : new KnowledgeDocument(sourceType, sourcePath, title, hash, modelId, props.getEmbeddingDimension());
        document.setSourceType(sourceType);
        document.setTitle(title);
        document.setContentHash(hash);

        List<KnowledgeChunk> chunks = new ArrayList<>();
        List<float[]> vectors = embed(pieces);
        int vectorized = 0;
        Long documentId = existing == null ? null : existing.getId();
        for (int i = 0; i < pieces.size(); i++) {
            float[] vector = i < vectors.size() ? vectors.get(i) : null;
            if (vector != null) {
                vectorized++;
            }
            KnowledgeChunk chunk = new KnowledgeChunk(documentId, pieces.get(i).index(), pieces.get(i).heading(),
                    pieces.get(i).text(), EmbeddingCodec.encode(vector),
                    vector == null ? null : vector.length,
                    vector == null ? null : modelId);
            chunks.add(chunk);
        }

        document.setChunkCount(chunks.size());
        document.setEmbeddingModel(vectorized > 0 ? modelId : null);
        // 维度以实际写入的向量为准（降级向量与线上模型维度可能不同）
        Integer vectorDimension = null;
        for (float[] vector : vectors) {
            if (vector != null) {
                vectorDimension = vector.length;
                break;
            }
        }
        document.setDimension(vectorDimension);
        // 文本进了库就能被关键词召回，因此状态一律 indexed；是否有向量由 embedding_model 是否为空表达
        document.setStatus(KnowledgeDocument.STATUS_INDEXED);

        if (existing == null) {
            documentMapper.insert(document);
        } else {
            documentMapper.updateStats(document);
            chunkMapper.deleteByDocumentId(document.getId());
        }
        documentId = document.getId();
        if (documentId == null) {
            throw new IllegalStateException("文档主键回填失败，无法建立片段关联");
        }
        for (KnowledgeChunk chunk : chunks) {
            chunk.setDocumentId(documentId);
        }
        if (!chunks.isEmpty()) {
            for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
                chunkMapper.insertBatch(chunks.subList(i, Math.min(chunks.size(), i + EMBED_BATCH_SIZE)));
            }
        }
        if (existing == null) {
            retrievalIndex.index(document, chunks);
        } else {
            // 覆盖已有文档：让向量通道先按 document_id 清掉旧点，避免片段变少时留下幽灵点
            retrievalIndex.replace(document, chunks);
        }
        log.info("知识入库: {} → {} 片段（向量化 {}），来源={}", sourcePath, chunks.size(), vectorized, sourceType);
        return new IndexOutcome(documentId, sourcePath, title, chunks.size(), vectorized, true);
    }

    /** 删除一份文档（含索引与库内片段）。 */
    public synchronized void removeDocument(long documentId) {
        chunkMapper.deleteByDocumentId(documentId);
        retrievalIndex.removeDocument(documentId);
    }

    /**
     * 长文档摘要的覆盖率采样：把片段按序号等距抽样到字符预算内，
     * 让摘要看到「开头 + 中段 + 结尾」，而不是只看到前 2000 字。
     */
    public String coverageSample(long documentId, int maxChars) {
        List<KnowledgeVectorIndex.Entry> entries = retrievalIndex.entriesOfDocument(documentId);
        if (entries.isEmpty()) {
            return "";
        }
        if (entries.size() == 1) {
            return truncate(entries.get(0).content(), maxChars);
        }
        int sampleCount = Math.min(entries.size(), 8);
        if (sampleCount == 1) {
            return truncate(entries.get(0).content(), maxChars);
        }
        // 等距抽样且首尾必取：采样点落在 k * (n-1) / (sampleCount-1)，
        // 因此最后一段一定会被取到 —— 这正是「只取前 2000 字」丢掉的部分。
        int budgetPerChunk = Math.max(200, maxChars / sampleCount);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < sampleCount; k++) {
            int idx = (int) Math.round((double) k * (entries.size() - 1) / (sampleCount - 1));
            KnowledgeVectorIndex.Entry e = entries.get(idx);
            if (e.heading() != null && !e.heading().isBlank()) {
                sb.append('【').append(e.heading()).append("】\n");
            }
            // 首片段取开头、尾片段取结尾：保证「文档结尾讲了什么」这类问题有据可依
            String body = (k == sampleCount - 1 && e.content().length() > budgetPerChunk)
                    ? "…" + tail(e.content(), budgetPerChunk)
                    : truncate(e.content(), budgetPerChunk);
            sb.append(body).append("\n\n");
        }
        return sb.toString().strip();
    }

    public IndexStats stats() {
        return new IndexStats(documentMapper.countAll(), retrievalIndex.chunkCount(),
                retrievalIndex.vectorCount(), retrievalIndex.dimensions(), embeddingModelId());
    }

    /** 当前向量模型标识：写入片段并与库中记录比对，实现「换模型即重建」。 */
    public String embeddingModelId() {
        if (embeddingModel instanceof HashingEmbeddingModel) {
            return HashingEmbeddingModel.MODEL_NAME;
        }
        return props.getEmbeddingModel() + "@" + props.getEmbeddingDimension();
    }

    // ==================== 内部工具 ====================

    /** 批量向量化；任何异常都不抛出，返回空列表表示「本次只入库文本，不含向量」。 */
    private List<float[]> embed(List<TextChunker.Chunk> pieces) {
        if (pieces.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i += EMBED_BATCH_SIZE) {
            List<TextChunker.Chunk> batch = pieces.subList(i, Math.min(pieces.size(), i + EMBED_BATCH_SIZE));
            List<TextSegment> segments = batch.stream()
                    .map(p -> TextSegment.from(p.text()))
                    .toList();
            try {
                Response<List<Embedding>> response = embeddingModel.embedAll(segments);
                List<Embedding> embeddings = response.content();
                if (embeddings == null || embeddings.size() != segments.size()) {
                    throw new IllegalStateException("向量模型返回条数不匹配");
                }
                for (Embedding embedding : embeddings) {
                    float[] vector = embedding.vector();
                    EmbeddingCodec.normalize(vector);
                    vectors.add(vector);
                }
            } catch (Exception e) {
                log.warn("向量化失败（本批 {} 条降级为纯关键词召回）: {}", segments.size(), e.getMessage());
                for (int k = 0; k < segments.size(); k++) {
                    vectors.add(null);
                }
            }
        }
        return vectors;
    }

    /** 类路径资源的相对路径（保留 references/、assets/templates/ 等子目录，避免同名文件互相覆盖）。 */
    private static String resourceRelativePath(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int idx = url.indexOf(RESOURCE_ROOT + "/");
            if (idx >= 0) {
                return url.substring(idx);
            }
        } catch (Exception ignored) {
            // 取不到 URL（非常规类加载器）时退回文件名
        }
        return RESOURCE_ROOT + "/" + resource.getFilename();
    }
    private static boolean isTextResource(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ENGLISH));
    }

    private static String tail(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 预留：Tika 直读 File（工具/管理接口用） */
    public IndexOutcome indexFile(File file, String sourceType) {
        return indexFile(file.toPath(), sourceType, null);
    }
}
