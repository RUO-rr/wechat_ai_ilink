package io.github.wangyangxu.ailink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 共享的 RestTemplate Bean，统一配置超时和连接参数。
 * 所有 Service 注入此 Bean 而非各自 new RestTemplate()。
 */
@Configuration
public class RestTemplateConfig {

    /** 连接超时 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 读取超时 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return new RestTemplate(factory);
    }
}
