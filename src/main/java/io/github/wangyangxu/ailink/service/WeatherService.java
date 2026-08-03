package io.github.wangyangxu.ailink.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${weather.api-key}")
    private String apiKey;

    @Value("${weather.base-url}")
    private String baseUrl;

    public WeatherService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 查询指定城市的实时天气。
     *
     * @param city 城市名称，如"北京"、"上海"
     * @return 格式化天气字符串；失败返回错误描述
     */
    public String getWeather(String city) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("高德天气 API Key 未配置");
            return "天气服务未配置，请联系管理员";
        }

        String url = baseUrl + "?key=" + apiKey + "&city=" + city + "&extensions=base";

        try {
            String responseBody = restTemplate.getForObject(url, String.class);
            log.info("高德天气 API 响应: {}", responseBody);

            if (responseBody == null || responseBody.isBlank()) {
                log.error("高德天气 API 返回空响应");
                return "天气数据获取失败，请稍后重试";
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // 高德 API 返回 status=1 表示成功
            String status = root.get("status").asText();
            if (!"1".equals(status)) {
                String info = root.has("info") ? root.get("info").asText() : "未知错误";
                log.error("高德天气 API 返回错误: status={}, info={}", status, info);
                return "天气查询失败：" + info;
            }

            JsonNode lives = root.get("lives");
            if (lives == null || !lives.isArray() || lives.size() == 0) {
                log.error("高德天气 API 未返回 lives 数据, city={}", city);
                return "未找到「" + city + "」的天气数据，请检查城市名称";
            }

            JsonNode live = lives.get(0);
            String resultCity = live.get("city").asText();
            String weather = live.get("weather").asText();
            String temperature = live.get("temperature").asText();
            String humidity = live.get("humidity").asText();
            String winddirection = live.get("winddirection").asText();
            String windpower = live.get("windpower").asText();
            String reporttime = live.get("reporttime").asText();

            return resultCity + "实时天气：" + weather
                    + "，气温" + temperature + "℃"
                    + "，湿度" + humidity + "%"
                    + "，" + winddirection + "风" + windpower + "级"
                    + "，数据更新时间" + reporttime;

        } catch (RestClientException e) {
            log.error("调用高德天气 API 失败: {}", e.getMessage(), e);
            return "天气服务网络异常，请稍后重试";
        } catch (Exception e) {
            log.error("解析高德天气 API 响应失败: {}", e.getMessage(), e);
            return "天气数据解析失败，请稍后重试";
        }
    }
}
