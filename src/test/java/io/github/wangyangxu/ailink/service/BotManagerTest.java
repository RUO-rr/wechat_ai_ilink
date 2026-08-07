package io.github.wangyangxu.ailink.service;

import io.github.wangyangxu.ailink.config.BotConfiguration;
import io.github.wangyangxu.ailink.mapper.BotRegistryMapper;
import io.github.wangyangxu.ailink.model.BotInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BotManagerTest {

    @Mock
    private BotConfiguration botConfig;

    @Mock
    private BotRegistryMapper botRegistryMapper;

    @Mock
    private BotAlertService alertService;

    @Mock
    private MetricsService metricsService;

    @InjectMocks
    private BotManager botManager;

    @Test
    void createBot_exceedsMax_throws() {
        when(botConfig.getMaxBots()).thenReturn(2);
        botManager.createBot("a");
        botManager.createBot("b");
        assertThrows(IllegalStateException.class, () -> botManager.createBot("c"));
    }

    @Test
    void loginBotAsync_missingBot_throws() {
        assertThrows(IllegalArgumentException.class, () -> botManager.loginBotAsync("bot_missing"));
    }

    @Test
    void loginBotAsync_busyGuard_throwsWhenLoginInProgress() {
        when(botConfig.getMaxBots()).thenReturn(10);
        String botId = botManager.createBot("x");
        BotInstance bot = botManager.getBot(botId);
        assertTrue(bot.tryBeginLogin()); // 占用登录标记

        assertThrows(IllegalStateException.class, () -> botManager.loginBotAsync(botId));
    }

    @Test
    void shutdownBot_deletesRegistry() {
        when(botConfig.getMaxBots()).thenReturn(10);
        String botId = botManager.createBot("x");
        botManager.shutdownBot(botId);
        verify(botRegistryMapper).delete(botId);
    }
}
