package com.example.wea_forecast.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Service
public class IlinkService {

    private static final Logger log = LoggerFactory.getLogger(IlinkService.class);

    private ILinkClient client;

    public void initialize(OnLoginListener loginListener, OnMessageListener messageListener) {
        client = ILinkClient.builder()
                .onLogin(loginListener)
                .onMessage(messageListener)
                .build();
        log.info("ILinkClient 构建完成");
    }

    public String login() {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        return client.executeLogin();
    }

    public CompletableFuture<LoginContext> getLoginFuture() {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        return client.getLoginFuture();
    }

    public void sendText(String toUserId, String text) throws Exception {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        client.sendText(toUserId, text);
    }

    public void sendVoice(String toUserId, byte[] voiceBytes, String fileName,
                          Integer playTimeMs, Integer sampleRate) throws IOException {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        client.sendVoice(toUserId, voiceBytes, fileName, playTimeMs, sampleRate);
    }

    public void sendFile(String toUserId, byte[] fileBytes, String fileName, String caption) throws IOException {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        client.sendFile(toUserId, fileBytes, fileName, caption);
    }

    public void sendImage(String toUserId, byte[] imageBytes, String fileName, String caption) throws IOException {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        client.sendImage(toUserId, imageBytes, fileName, caption);
    }

    public byte[] downloadMedia(CDNMedia media) throws IOException {
        if (client == null) {
            throw new IllegalStateException("ILinkClient 未初始化，请先调用 initialize()");
        }
        return client.downloadMedia(media);
    }

    @PreDestroy
    public void destroy() {
        if (client != null) {
            client.close();
            log.info("ILinkClient 已关闭");
        }
    }
}
