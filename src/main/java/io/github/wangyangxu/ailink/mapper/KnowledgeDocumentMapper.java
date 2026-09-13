package io.github.wangyangxu.ailink.mapper;

import io.github.wangyangxu.ailink.model.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG 知识库文档 Mapper。
 * source_path 唯一，保证同一份文件重复灌入时是「更新」而不是「再插一份」。
 */
@Mapper
public interface KnowledgeDocumentMapper {

    KnowledgeDocument findByPath(@Param("sourcePath") String sourcePath);

    void insert(KnowledgeDocument document);

    /** 重建后回填统计信息（片段数 / 向量模型 / 状态 / 内容指纹） */
    void updateStats(KnowledgeDocument document);

    List<KnowledgeDocument> findAll();

    int countAll();
}