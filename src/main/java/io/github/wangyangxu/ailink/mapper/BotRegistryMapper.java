package io.github.wangyangxu.ailink.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * Bot 注册表（bot_registry）Mapper —— Bot-User 映射持久化。
 * 存储 SDK 返回的真实微信身份（wechatUserId / wechatBotId），
 * 用于重启后恢复 Bot 实例。
 */
@Mapper
public interface BotRegistryMapper {

    /**
     * 插入或更新 Bot 注册记录。
     * 登录成功时调用，写入 SDK 返回的真实身份。
     */
    void upsert(@Param("botId") String botId,
                @Param("label") String label,
                @Param("wechatUserId") String wechatUserId,
                @Param("wechatBotId") String wechatBotId,
                @Param("botToken") String botToken,
                @Param("baseUrl") String baseUrl);

    /** 查询所有已注册的 Bot */
    List<Map<String, Object>> findAll();

    /** 更新最后活跃时间 */
    void updateActiveAt(@Param("botId") String botId);

    /** 删除 Bot 注册记录 */
    void delete(@Param("botId") String botId);
}
