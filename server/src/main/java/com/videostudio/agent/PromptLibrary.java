package com.videostudio.agent;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 读取 classpath:prompts/*.md，进程内缓存。prompt 与代码分离，便于调参而不重编译。 */
@Component
public class PromptLibrary {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String name) {
        return cache.computeIfAbsent(name, key -> {
            String path = "prompts/" + key + ".md";
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalStateException("缺少提示词文件: " + path, e);
            }
        });
    }
}
