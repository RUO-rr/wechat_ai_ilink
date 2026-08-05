package io.github.wangyangxu.ailink.mapper;

import io.github.wangyangxu.ailink.model.AgentMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 长期记忆 MyBatis Mapper。
 * 记忆以 user_id 为维度（跟随微信用户，而非 Bot 实例），冲突解决依赖 dimension 归一化匹配。
 */
@Mapper
public interface AgentMemoryMapper {

    void insert(AgentMemory memory);

    /** 某维度下最新的 active 记忆（冲突解决：recency wins 的比对对象） */
    AgentMemory findLatestActiveByDimension(@Param("userId") String userId, @Param("dimension") String dimension);

    /** 该用户最新 active 的 fact / preference（按时间倒序，limit 截断） */
    List<AgentMemory> findActiveFacts(@Param("userId") String userId, @Param("limit") int limit);

    /** 最新 active 的摘要（滚动摘要只保留一条） */
    AgentMemory findLatestActiveSummary(@Param("userId") String userId);

    /** 该用户 active 的笔记（按时间倒序，limit 截断） */
    List<AgentMemory> findActiveNotes(@Param("userId") String userId, @Param("limit") int limit);

    /** 某用户某维度的全部记忆（按时间正序，审计回溯 supersedes 链用） */
    List<AgentMemory> findByUserAndDimension(@Param("userId") String userId, @Param("dimension") String dimension);

    /** 标记为 superseded（冲突解决：旧记忆被新记忆取代） */
    void markSuperseded(@Param("id") Long id);

    /** 软删除（remember 工具的 delete，保留审计轨迹） */
    void markDeleted(@Param("id") Long id, @Param("userId") String userId);
}
