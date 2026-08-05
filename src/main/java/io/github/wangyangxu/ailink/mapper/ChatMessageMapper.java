package io.github.wangyangxu.ailink.mapper;

import io.github.wangyangxu.ailink.model.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 聊天消息 MyBatis Mapper。
 */
@Mapper
public interface ChatMessageMapper {

    /** 插入一条消息 */
    void insert(ChatMessage message);

    /** 按 botId + userId 查询全部消息（按时间正序） */
    List<ChatMessage> findByBotUser(@Param("botId") String botId, @Param("userId") String userId);

    /** 删除某用户在某 Bot 下的全部消息 */
    void deleteByBotUser(@Param("botId") String botId, @Param("userId") String userId);

    /**
     * 裁剪：保留最新 keepCount 条，删除更早的记录。
     */
    void trimOldMessages(@Param("botId") String botId, @Param("userId") String userId, @Param("keepCount") int keepCount);

    /** 查询某用户在某 Bot 下的最新一条消息 ID（记忆溯源用） */
    Long findLatestId(@Param("botId") String botId, @Param("userId") String userId);
}
