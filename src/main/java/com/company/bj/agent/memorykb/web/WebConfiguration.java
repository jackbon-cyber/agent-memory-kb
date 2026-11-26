package com.company.bj.agent.memorykb.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配。
 *
 * <p><b>为什么开启跨域</b>：控制台（Task 10）跑在 Vite dev server 上，
 * 默认端口 5178，与后端 8088 不同源。若不做跨域配置，
 * 浏览器会把每一次接口调用拦在预检阶段，
 * 表现为「后端日志里根本没有收到请求」—— 极容易被误判成后端故障。
 *
 * <p><b>为什么限制来源</b>：允许任意来源等于把接口暴露给任何网页，
 * 而本次交付<b>不做鉴权</b>（见 design.md 的非目标清单）。
 * 因此这里只放行本机开发端口 —— 足以支撑控制台联调，
 * 又不至于让接口在部署后仍对公网敞开。
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    /** 允许的跨域来源：Vite 开发服务器。 */
    private static final String ALLOWED_ORIGIN_PATTERN = "http://localhost:*";

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(ALLOWED_ORIGIN_PATTERN)
            .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
    }
}
