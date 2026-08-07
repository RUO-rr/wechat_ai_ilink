package io.github.wangyangxu.ailink.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理 API Web 配置 —— 只对 /api/** 应用 Token 鉴权拦截器。
 */
@Configuration
public class ManagementApiWebConfig implements WebMvcConfigurer {

    @Autowired
    private ManagementApiInterceptor managementApiInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(managementApiInterceptor).addPathPatterns("/api/**");
    }
}
