package io.github.wangyangxu.ailink.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

/**
 * ngrok 内网穿透工具类。
 * <p>
 * 职责：
 * 1. 启动 ngrok 进程（映射本机 8080 端口）
 * 2. 从 ngrok 本地监控端口 (127.0.0.1:4040) 拉取当前公网域名
 * 3. 缓存公网 URL，供其他 Service 使用
 */
public class NgrokUtil {

    private static final Logger log = LoggerFactory.getLogger(NgrokUtil.class);
    private static final RestTemplate restTemplate = new RestTemplate();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static volatile String publicUrl;
    private static volatile Process ngrokProcess;

    /**
     * 启动 ngrok 并获取公网 URL，最多重试 10 次。
     *
     * @param ngrokPath ngrok.exe 的绝对路径
     * @return 公网 URL（如 https://xxx.ngrok-free.app）
     */
    public static String startAndFetchUrl(String ngrokPath) {
        // 1. 先检查是否已有 ngrok 在跑（上次没正常退出可能会残留）
        try {
            String existingUrl = fetchFromLocalApi();
            if (existingUrl != null && !existingUrl.isBlank()) {
                publicUrl = existingUrl.replaceAll("/+$", "");
                log.info("检测到已有 ngrok 隧道，复用: {}", publicUrl);
                return publicUrl;
            }
        } catch (Exception e) {
            // 没找到已有隧道，继续启动新的
        }

        // 2. 启动新的 ngrok 进程
        try {
            ProcessBuilder pb = new ProcessBuilder(ngrokPath, "http", "8080",
                    "--request-header-add", "ngrok-skip-browser-warning:true");
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            ngrokProcess = pb.start();
            log.info("ngrok 进程已启动");

            // 注册 JVM 关闭钩子
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (ngrokProcess != null && ngrokProcess.isAlive()) {
                    ngrokProcess.destroy();
                    log.info("ngrok 进程已关闭");
                }
            }));
        } catch (Exception e) {
            log.error("启动 ngrok 失败: {}", e.getMessage(), e);
            throw new RuntimeException("启动 ngrok 失败", e);
        }

        // 2. 循环重试获取 URL（最多 10 次，每次等 2 秒）
        for (int i = 1; i <= 10; i++) {
            try {
                Thread.sleep(2000);
                String url = fetchFromLocalApi();
                if (url != null && !url.isBlank()) {
                    publicUrl = url.replaceAll("/+$", ""); // 去末尾斜杠
                    log.info("第{}次尝试成功获取 ngrok 公网地址: {}", i, publicUrl);
                    return publicUrl;
                }
                log.warn("第{}次尝试未获取到 ngrok URL，继续重试...", i);
            } catch (Exception e) {
                log.warn("第{}次尝试获取 ngrok URL 失败: {}", i, e.getMessage());
            }
        }
        throw new RuntimeException("重试10次后仍无法获取 ngrok 公网 URL");
    }

    /**
     * 请求 ngrok 本地 API：http://127.0.0.1:4040/api/tunnels
     * 从返回的 JSON 中提取 https 协议的 public_url。
     */
    private static String fetchFromLocalApi() throws Exception {
        String json = restTemplate.getForObject("http://127.0.0.1:4040/api/tunnels", String.class);
        JsonNode root = objectMapper.readTree(json);
        JsonNode tunnels = root.get("tunnels");
        if (tunnels != null && tunnels.isArray()) {
            for (JsonNode t : tunnels) {
                String proto = t.get("proto").asText();
                if ("https".equals(proto)) {
                    return t.get("public_url").asText();
                }
            }
            // 如果没有 https，取第一个
            if (tunnels.size() > 0) {
                return tunnels.get(0).get("public_url").asText();
            }
        }
        return null;
    }

    /**
     * 获取缓存的公网 URL。
     */
    public static String getPublicUrl() {
        if (publicUrl == null) {
            throw new IllegalStateException("ngrok 公网 URL 尚未初始化");
        }
        return publicUrl;
    }
}
