package com.chy.zhikexing.config;

import com.chy.zhikexing.util.LoginInterceptor;
import com.chy.zhikexing.util.RefreshTokenInterceptor;
import com.chy.zhikexing.auth.AuthSessionService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class MvcConfiguration implements WebMvcConfigurer {

    private final AuthSessionService sessions;

    @Value("${app.cors-origins}")
    private String[] corsOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 只接受明确列出的前端来源；部署时可通过 CORS_ORIGINS 完整替换白名单。
        registry.addMapping("/**")
                .allowedOrigins(corsOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition", "Retry-After");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(sessions))
                .addPathPatterns("/**")
                .excludePathPatterns("/user/login", "/user/register", "/internal/**", "/error", "/actuator/**")
                .order(0);
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/login",
                        "/user/register",
                        "/user/logout",
                        "/internal/**",
                        "/actuator/**",
                        "/error")
                .order(1);
    }
}
