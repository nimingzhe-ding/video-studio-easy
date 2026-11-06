package com.videostudio.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.videostudio.common.Json;
import com.videostudio.domain.AnalysisMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 分析模式注册表：从 JSON 读取可用模式，新增模式不再需要改 Java。
 *
 * <p>读取顺序是「内置默认 → classpath:modes.json → 外置文件」，后者同名 code 覆盖前者。
 * 外置文件用 {@code agent.modes-file} 指定，方便在不重新打包的情况下灰度一种新模式；
 * 也可以用它覆盖内置模式的措辞。
 *
 * <p>启动时校验：code 唯一、至少存在一个 GENERAL、guidance 非空。校验失败直接拒绝启动——
 * 一份写错的模式配置会让所有任务用错提纲，而这在运行期很难被发现。
 */
@Component
public class ModeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModeRegistry.class);
    /** 用户没填目标时使用的模式；同时也是兜底模式，必须存在 */
    public static final String DEFAULT_CODE = "GENERAL";

    private final Map<String, AnalysisMode> modes;

    public ModeRegistry(@Value("${agent.modes-file:}") String externalFile) {
        Map<String, AnalysisMode> merged = new LinkedHashMap<>();
        for (AnalysisMode mode : AnalysisMode.builtins()) {
            merged.put(mode.code(), mode);
        }
        Map<String, AnalysisMode> fromClasspath = load(readClasspath());
        merged.putAll(fromClasspath);
        if (externalFile != null && !externalFile.isBlank()) {
            Map<String, AnalysisMode> external = load(readFile(Path.of(externalFile.strip())));
            merged.putAll(external);
            log.info("已加载外置模式文件 {}，覆盖 {} 个模式", externalFile, external.size());
        }
        if (!merged.containsKey(DEFAULT_CODE)) {
            throw new IllegalStateException("模式配置缺少 " + DEFAULT_CODE + "，无法启动");
        }
        for (AnalysisMode mode : merged.values()) {
            if (mode.guidance().isBlank()) {
                throw new IllegalStateException("模式 " + mode.code() + " 的 guidance 不能为空");
            }
        }
        this.modes = Map.copyOf(merged);
        log.info("分析模式已注册：{}", String.join("、", ordered().stream().map(AnalysisMode::code).toList()));
    }

    /** 全部模式，内置顺序在前 */
    public List<AnalysisMode> ordered() {
        List<AnalysisMode> builtin = AnalysisMode.builtins().stream()
                .map(mode -> modes.get(mode.code())).filter(java.util.Objects::nonNull).toList();
        List<AnalysisMode> extra = modes.values().stream()
                .filter(mode -> AnalysisMode.builtins().stream()
                        .noneMatch(builtinMode -> builtinMode.code().equals(mode.code())))
                .sorted(java.util.Comparator.comparing(AnalysisMode::code))
                .toList();
        List<AnalysisMode> out = new ArrayList<>(builtin);
        out.addAll(extra);
        return List.copyOf(out);
    }

    public Optional<AnalysisMode> find(String code) {
        return code == null ? Optional.empty()
                : Optional.ofNullable(modes.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** 必须存在的模式，找不到就抛出可读错误 */
    public AnalysisMode require(String code) {
        return find(code).orElseThrow(() -> new IllegalStateException("未知的分析模式: " + code));
    }

    public AnalysisMode defaultMode() {
        return require(DEFAULT_CODE);
    }

    /**
     * 历史数据的模式还原。
     *
     * <p>模式被从配置里移除后，库里的旧值仍然要能显示。这时保留原始 code
     * （label 退回 code），而不是悄悄改写成 GENERAL——那份报告当初用的确实不是 GENERAL。
     */
    public AnalysisMode forDisplay(String raw) {
        return find(raw).orElseGet(() -> AnalysisMode.ofCode(raw));
    }

    /** 给前端与提示词用的模式清单，顺序稳定 */
    public List<Map<String, String>> describe() {
        List<Map<String, String>> out = new ArrayList<>();
        for (AnalysisMode mode : ordered()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", mode.code());
            item.put("label", mode.label());
            out.add(item);
        }
        return out;
    }

    private String readClasspath() {
        ClassPathResource resource = new ClassPathResource("modes.json");
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.info("classpath:modes.json 不存在，使用内置模式");
            return "";
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取模式配置文件: " + path, e);
        }
    }

    private static Map<String, AnalysisMode> load(String json) {
        Map<String, AnalysisMode> map = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }
        JsonNode root;
        try {
            root = Json.tree(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("模式配置不是合法 JSON: " + e.getMessage(), e);
        }
        JsonNode items = root.path("modes");
        if (!items.isArray()) {
            throw new IllegalStateException("模式配置缺少 modes 数组");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : items) {
            AnalysisMode mode = new AnalysisMode(
                    item.path("code").asText(""),
                    item.path("label").asText(""),
                    item.path("guidance").asText(""),
                    strings(item.path("sectionKeys")),
                    item.path("defaultGoal").asText(""));
            if (!seen.add(mode.code())) {
                throw new IllegalStateException("模式配置里 " + mode.code() + " 重复定义");
            }
            if (mode.label().isBlank()) {
                throw new IllegalStateException("模式 " + mode.code() + " 缺少 label");
            }
            map.put(mode.code(), mode);
        }
        return map;
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String text = node.asText("").strip();
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
        return out;
    }
}
