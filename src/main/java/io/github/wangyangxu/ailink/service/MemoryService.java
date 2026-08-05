package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.mapper.AgentMemoryMapper;
import io.github.wangyangxu.ailink.model.AgentMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 长期记忆服务 —— 存储、冲突解决、读路径注入、笔记管理。
 * <ul>
 *   <li>fact / preference：每轮 LLM 自动提取，recency-wins supersede 解决偏好冲突</li>
 *   <li>summary：每 N 轮滚动摘要，supersedes_id 链保留前身（读路径只取最新 active，O(1)）</li>
 *   <li>note：remember 工具手动写入，永不 supersede，仅手动软删除（status=deleted）</li>
 * </ul>
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    /** 读路径配额：摘要槽固定 1 条，记忆槽 / 笔记槽各有限额，互不竞争 */
    private static final int INJECT_FACT_LIMIT = 5;
    private static final int INJECT_NOTE_LIMIT = 3;

    @Autowired
    private AgentMemoryMapper memoryMapper;

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
        log.info("笔记写入: userId={} noteId={}", userId, note.getId());
        return note.getId();
    }

    /** remember 工具：软删除笔记（status=deleted，保留审计轨迹） */
    public boolean deleteNote(String userId, Long noteId) {
        if (noteId == null) return false;
        memoryMapper.markDeleted(noteId, userId);
        log.info("笔记软删除: userId={} noteId={}", userId, noteId);
        return true;
    }

    /** remember 工具：列出 active 笔记 */
    public List<AgentMemory> listNotes(String userId) {
        return memoryMapper.findActiveNotes(userId, 50);
    }

    /** 读路径注入：摘要槽（1 条）/ 记忆槽（≤5）/ 笔记槽（≤3），互不竞争 */
    public MemoryInjection getInjection(String userId) {
        String summary = null;
        AgentMemory s = memoryMapper.findLatestActiveSummary(userId);
        if (s != null) {
            summary = s.getContent();
        }

        List<String> facts = new ArrayList<>();
        for (AgentMemory m : memoryMapper.findActiveFacts(userId, INJECT_FACT_LIMIT)) {
            facts.add("[" + m.getMemoryType() + "/" + m.getDimension() + "] " + m.getContent());
        }

        List<String> notes = new ArrayList<>();
        for (AgentMemory m : memoryMapper.findActiveNotes(userId, INJECT_NOTE_LIMIT)) {
            notes.add(m.getContent());
        }
        return new MemoryInjection(summary, facts, notes);
    }

    // ==================== 内部 ====================

    private void insertActive(String userId, String type, String dimension, String content, Long sourceMessageId) {
        AgentMemory memory = new AgentMemory(userId, type, dimension, content,
                sourceMessageId, AgentMemory.STATUS_ACTIVE, null);
        memoryMapper.insert(memory);
        log.info("记忆写入: userId={} type={} dimension={}", userId, type, dimension);
    }

    /**
     * recency wins：旧 active 置 superseded，新记忆挂 supersedes_id 形成可审计的演变链。
     * 读路径只取最新 active（O(1)），不遍历链。
     */
    private void supersedeAndInsert(String userId, String type, String dimension, String content, Long sourceMessageId) {
        AgentMemory old = memoryMapper.findLatestActiveByDimension(userId, dimension);
        Long supersedesId = null;
        if (old != null) {
            memoryMapper.markSuperseded(old.getId());
            supersedesId = old.getId();
            log.info("记忆冲突解决: userId={} dimension={} 新值取代旧记忆 id={}", userId, dimension, old.getId());
        }
        AgentMemory memory = new AgentMemory(userId, type, dimension, content,
                sourceMessageId, AgentMemory.STATUS_ACTIVE, supersedesId);
        memoryMapper.insert(memory);
        log.info("记忆写入: userId={} type={} dimension={}", userId, type, dimension);
    }

    private static String normalizeType(String memoryType) {
        if (memoryType == null) return AgentMemory.TYPE_FACT;
        String t = memoryType.trim().toLowerCase(Locale.ENGLISH);
        return AgentMemory.TYPE_PREFERENCE.equals(t) ? AgentMemory.TYPE_PREFERENCE : AgentMemory.TYPE_FACT;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
