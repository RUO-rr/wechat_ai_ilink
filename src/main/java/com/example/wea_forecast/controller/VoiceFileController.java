package com.example.wea_forecast.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 语音文件下载控制器。
 * <p>
 * 把本地的 voice_cache 目录通过 HTTP 暴露出去，配合 ngrok 让外界能下载。
 * 路径映射：GET /voice/{filename} → 返回 voice_cache/{filename} 的内容
 */
@RestController
public class VoiceFileController {

    private static final Logger log = LoggerFactory.getLogger(VoiceFileController.class);

    @Value("${voice.cache-dir:./voice_cache}")
    private String cacheDir;

    @GetMapping("/voice/{filename}")
    public ResponseEntity<byte[]> download(@PathVariable String filename) {
        try {
            // 安全检查：防止路径穿越（如 /voice/../../etc/passwd）
            Path filePath = Paths.get(cacheDir).resolve(filename).normalize();
            if (!filePath.startsWith(Paths.get(cacheDir).normalize())) {
                log.warn("非法路径访问: {}", filename);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }

            byte[] data = Files.readAllBytes(filePath);
            log.info("提供语音文件下载: {} ({} bytes)", filename, data.length);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(data);
        } catch (Exception e) {
            log.error("读取语音文件失败 {}: {}", filename, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
