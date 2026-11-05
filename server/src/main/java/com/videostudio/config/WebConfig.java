package com.videostudio.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 前端联调配置。
 * <p>开发时 Vite 跑在 5173，通过 /api 代理到 9090；
 * 生产/演示时把 <code>client/dist</code> 直接托管在 9090，单进程即可完整演示。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AppProperties properties;
    private final com.videostudio.security.OwnerGuard owner;

    public WebConfig(AppProperties properties, com.videostudio.security.OwnerGuard owner) {
        this.owner=owner;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(owner).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        File dist = resolveClientDist();
        if (dist != null) {
            registry.addResourceHandler("/**")
                    .addResourceLocations("file:" + dist.getAbsolutePath() + File.separator)
                    .resourceChain(true);
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (resolveClientDist() != null) {
            // 前端是单页应用：非 /api 的路径回落到 index.html
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }

    private File resolveClientDist() {
        File[] candidates = {
                new File("../client/dist"),
                new File("client/dist"),
                new File("../../client/dist")
        };
        for (File candidate : candidates) {
            if (new File(candidate, "index.html").isFile()) {
                return candidate.getAbsoluteFile();
            }
        }
        return null;
    }
}
