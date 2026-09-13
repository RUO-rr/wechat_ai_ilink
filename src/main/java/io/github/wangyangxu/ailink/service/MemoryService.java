package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.memory.MemoryEntry;
import io.github.wangyangxu.ailink.memory.MemoryIndex;
import io.github.wangyangxu.ailink.memory.MemoryProperties;
import io.github.wangyangxu.ailink.memory.MemoryRetriever;
import io.github.wangyangxu.ailink.model.AgentMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 长期记忆服务 —— 存储、冲突解决、读路径注入、笔记管理。
 * <ul>
 *   <li>fact / preference：每轮 LLM 自动提取，recency-wins supersede 解决偏好冲突</li>
 *   <li>summary：每 N 轮滚动摘要，supersedes_id 链保留前身（读路径只取最新 active，O(1)）</li>
 *   <li>note：remember 工具手动写入，永不 supersede，仅手动软删除（status=deleted）</li>
 * </ul>
 * 本类负责「写什么、怎么解决冲突、每个槽位给多少配额」；
 * 「哪几条与当前问题相关」交给 {@link MemoryRetriever}（复用 RAG 的检索内核）。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 读路径配额：摘要槽固定 1 条，记忆槽 / 笔记槽各有限额，互不竞争 */
    private static final int INJECT_FACT_LIMIT = 5;
    private static final int INJECT_NOTE_LIMIT = 3;

    /** 参与语义检索的记忆类型；摘要是「最近若干轮的压缩」，走固定槽位，不进索引 */
    private static final Set<String> FACT_TYPES = Set.of(AgentMemory.TYPE_FACT, AgentMemory.TYPE_PREFERENCE);
    private static final Set<String> NOTE_TYPES = Set.of(AgentMemory.TYPE_NOTE);

    @Autowired
    private AgentMemoryMapper memoryMapper;

    @Autowired
    private MemoryIndex memoryIndex;

    @Autowired
    private MemoryRetriever memoryRetriever;

    @Autowired
    private MemoryProperties memoryProps;

    /** 记忆提取条目（LLM 结构化输出解析后的中间态） */
    public record ExtractedMemory(String memoryType, String dimension, String content,
                                  String conflictAction, String raw) {}

    /** 读路径注入内容：三个独立槽位 */
    public record MemoryInjection(String summary, List<String> facts, List<String> notes) {}

    /**
     * dimension 写入前统一归一化：冲突解决依赖字符串匹配，必须保证同义维度写法一致。
     */
    static String normalizeDimension(String dimension) {
        if (dimension == null) return null;
        return dimension.trim().toLowerCase(Locale.ENGLISH);
    }

    /**
     * 提取结果写入：逐条校验，坏条目丢弃（WARN 留痕），好条目照常写入。
     * 必填字段：dimension / content / conflict_action；memory_type 缺省视为 fact。
     */
    public void recordExtracted(String userId, Long sourceMessageId, List<ExtractedMemory> entries) {
        if (entries == null || entries.isEmpty()) return;
        for (ExtractedMemory e : entries) {
            String dimension = normalizeDimension(e.dimension());
            String action = e.conflictAction() == null ? null : e.conflictAction().trim().toLowerCase(Locale.ENGLISH);
            boolean actionValid = "new".equals(action) || "supersede".equals(action) || "skip".equals(action);
            if (dimension == null || dimension.isBlank()
                    || e.content() == null || e.content().isBlank()
                    || !actionValid) {
                log.warn("记忆条目校验失败，丢弃本条: userId={} raw={}", userId, truncate(e.raw()));
                continue;
            }
            String type = normalizeType(e.memoryType());
            switch (action) {
                case "supersede" -> supersedeAndInsert(userId, type, dimension, e.content(), sourceMessageId);
                case "skip" -> log.debug("记忆 skip: userId={} dimension={}", userId, dimension);
                default -> insertActive(userId, type, dimension, e.content(), sourceMessageId);
            }
        }
    }

    /** 滚动摘要写入：最新摘要取代旧摘要（同维度只保留一条 active） */
    public void recordSummary(String userId, String content) {
        if (content == null || content.isBlank()) return;
        supersedeAndInsert(userId, AgentMemory.TYPE_SUMMARY, AgentMemory.DIM_SUMMARY, content.trim(), null);
    }

    /** remember 工具：写入持久笔记（无冲突链，永不 supersede） */
    public Long addNote(String userId, String content) {
        AgentMemory note = new AgentMemory(userId, AgentMemory.TYPE_NOTE, AgentMemory.DIM_NOTE,
                content, null, AgentMemory.STATUS_ACTIVE, null);
        memoryMapper.insert(note);
        memoryIndex.upsert(note);
        log.info("笔记写入: userId={} noteId={}", userId, note.getId());
        return note.getId();
    }

    /** remember 工具：软删除笔记（status=deleted，保留审计轨迹） */
    public boolean deleteNote(String userId, Long noteId) {
        if (noteId == null) return false;
        memoryMapper.markDeleted(noteId, userId);
        memoryIndex.remove(userId, noteId);
        log.info("笔记软删除: userId={} noteId={}", userId, noteId);
        return true;
    }

    /** remember 工具：列出 active 笔记 */
    public List<AgentMemory> listNotes(String userId) {
        return memoryMapper.findActiveNotes(userId, 50);
    }

    /**
     * 读路径注入：摘要槽（1 条）/ 记忆槽（≤5）/ 笔记槽（≤3），互不竞争。
     * <p>
     * {@code query} 是当前用户消息：记忆槽与笔记槽按它做相关性召回，
     * 而不是机械地取最近 N 条 —— 用户记忆一多，「最近」和「相关」就是两件事。
     */
    public MemoryInjection getInjection(String userId, String query) {
        String summary = null;
        AgentMemory latestSummary = memoryMapper.findLatestActiveSummary(userId);
        if (latestSummary != null) {
            summary = latestSummary.getContent();
        }
        return new MemoryInjection(summary, selectFacts(userId, query), selectNotes(userId, query));
    }

    /**
     * 记忆槽 = 「最近 N 条保底」∪「与当前问题相关的召回（补足限额）」。
     * 只按相关性召回会让「回答简洁」这类全局偏好在没命中的轮次里消失；
     * 只按新旧又会让近期但无关的记忆占满配额 —— 两者各留一部分配额。
     */
    private List<String> selectFacts(String userId, String query) {
        LinkedHashMap<Long, String> selected = new LinkedHashMap<>();
        for (AgentMemory memory : memoryMapper.findActiveFacts(userId, guarantee(memoryProps.getRecentFactGuarantee()))) {
            selected.put(memory.getId(), MemoryEntry.from(memory).display());
        }
        for (MemoryEntry entry : memoryRetriever.recall(userId, query, FACT_TYPES, INJECT_FACT_LIMIT)) {
            if (selected.size() >= INJECT_FACT_LIMIT) break;
            selected.putIfAbsent(entry.id(), entry.display());
        }
        // 降级：索引不可用（检索关闭 / 装载失败）时补齐最近记忆，注入内容不因检索失败而变少
        if (!memoryRetriever.isReady(userId)) {
            for (AgentMemory memory : memoryMapper.findActiveFacts(userId, INJECT_FACT_LIMIT)) {
                if (selected.size() >= INJECT_FACT_LIMIT) break;
                selected.putIfAbsent(memory.getId(), MemoryEntry.from(memory).display());
            }
        }
        return new ArrayList<>(selected.values());
    }

    /** 笔记槽：与记忆槽同一套「保底 + 召回」，只是配额与展示格式不同（笔记不展示类型标签） */
    private List<String> selectNotes(String userId, String query) {
        LinkedHashMap<Long, String> selected = new LinkedHashMap<>();
        for (AgentMemory memory : memoryMapper.findActiveNotes(userId, guarantee(memoryProps.getRecentNoteGuarantee()))) {
            selected.put(memory.getId(), memory.getContent());
        }
        for (MemoryEntry entry : memoryRetriever.recall(userId, query, NOTE_TYPES, INJECT_NOTE_LIMIT)) {
            if (selected.size() >= INJECT_NOTE_LIMIT) break;
            selected.putIfAbsent(entry.id(), entry.content());
        }
        if (!memoryRetriever.isReady(userId)) {
            for (AgentMemory memory : memoryMapper.findActiveNotes(userId, INJECT_NOTE_LIMIT)) {
                if (selected.size() >= INJECT_NOTE_LIMIT) break;
                selected.putIfAbsent(memory.getId(), memory.getContent());
            }
        }
        return new ArrayList<>(selected.values());
    }

    // ==================== 内部 ====================

    private void insertActive(String userId, String type, String dimension, String content, Long sourceMessageId) {
        // 去重兜底：LLM 判定 new，但库里已有语义几乎相同的记忆（哪怕 dimension 写法不同）→ 不再重复写
        if (memoryRetriever.isDuplicate(userId, content)) {
            log.info("记忆重复，跳过写入: userId={} type={} dimension={}", userId, type, dimension);
            return;
        }
        AgentMemory memory = new AgentMemory(userId, type, dimension, content,
                sourceMessageId, AgentMemory.STATUS_ACTIVE, null);
        memoryMapper.insert(memory);
        memoryIndex.upsert(memory);
        log.info("记忆写入: userId={} type={} dimension={}", userId, type, dimension);
    }

    /**
     * recency wins：旧 active 置 superseded，新记忆挂 supersedes_id 形成可审计的演变链。
     * 读路径只取最新 active（O(1)），不遍历链。
     * <p>
     * 摘要不进索引：它走固定槽位，不参与相关性召回。
     */
    private void supersedeAndInsert(String userId, String type, String dimension, String content, Long sourceMessageId) {
        AgentMemory old = memoryMapper.findLatestActiveByDimension(userId, dimension);
        Long supersedesId = null;
        if (old != null) {
            memoryMapper.markSuperseded(old.getId());
            supersedesId = old.getId();
            memoryIndex.remove(userId, old.getId());
            log.info("记忆冲突解决: userId={} dimension={} 新值取代旧记忆 id={}", userId, dimension, old.getId());
        }
        AgentMemory memory = new AgentMemory(userId, type, dimension, content,
                sourceMessageId, AgentMemory.STATUS_ACTIVE, supersedesId);
        memoryMapper.insert(memory);
        if (!AgentMemory.TYPE_SUMMARY.equals(type)) {
            memoryIndex.upsert(memory);
        }
        log.info("记忆写入: userId={} type={} dimension={}", userId, type, dimension);
    }

    private static String normalizeType(String memoryType) {
        if (memoryType == null) return AgentMemory.TYPE_FACT;
        String t = memoryType.trim().toLowerCase(Locale.ENGLISH);
        return AgentMemory.TYPE_PREFERENCE.equals(t) ? AgentMemory.TYPE_PREFERENCE : AgentMemory.TYPE_FACT;
    }

    private static int guarantee(int configured) {
        return Math.max(0, configured);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}