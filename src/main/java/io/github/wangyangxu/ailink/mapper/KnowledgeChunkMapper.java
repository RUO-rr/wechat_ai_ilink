package io.github.wangyangxu.ailink.mapper;

import io.github.wangyangxu.ailink.model.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG 知识库片段 Mapper。
 * 重建策略是「先删后插」（按 document_id 覆盖），保证片段序号不出现残留空洞。
 */
@Mapper
public interface KnowledgeChunkMapper {

    void insertBatch(@Param("chunks") List<KnowledgeChunk> chunks);

    List<KnowledgeChunk> findByDocumentId(@Param("documentId") Long documentId);

    /** 启动时把全量片段载入内存索引（语料规模小，进程内比对比每次查库更快） */
    List<KnowledgeChunk> findAll();

    void deleteByDocumentId(@Param("documentId") Long documentId);

    int countAll();
}