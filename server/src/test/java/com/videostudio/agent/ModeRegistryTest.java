package com.videostudio.agent;

import com.videostudio.domain.AnalysisMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模式注册表：新增模式不改代码，配置写错必须拒绝启动。
 *
 * <p>这里钉住的是「配置即真相」：classpath 的 modes.json 定义内置模式，
 * 外置文件可以覆盖或追加；任何会让任务用错提纲的配置错误都要在启动时炸掉，
 * 而不是等到运行期产出一份看起来正常的报告。
 */
class ModeRegistryTest {

    @TempDir
    Path temp;

    private ModeRegistry withFile(String json) throws IOException {
        Path file = temp.resolve("modes.json");
        Files.writeString(file, json);
        return new ModeRegistry(file.toString());
    }

    @Test
    void builtinModesAreAvailableWithoutAnyConfig() {
        ModeRegistry registry = new ModeRegistry("");
        List<String> codes = registry.ordered().stream().map(AnalysisMode::code).toList();

        assertEquals(List.of("GENERAL", "LEARNING", "REVIEW", "CREATION"), codes);
        assertEquals("通用理解", registry.defaultMode().label());
    }

    /** 新增模式只加一份配置：不需要改 Java，也不需要重新编译 */
    @Test
    void externalFileAddsANewMode() throws IOException {
        ModeRegistry registry = withFile("""
                {"modes":[{"code":"CONTRACT","label":"合同审查","guidance":"找出条款风险与缺失项",
                  "sectionKeys":["risks","omissions"],"defaultGoal":"审查合同风险"}]}
                """);

        AnalysisMode contract = registry.require("CONTRACT");
        assertEquals("合同审查", contract.label());
        assertEquals(List.of("risks", "omissions"), contract.sectionKeys());
        assertTrue(registry.ordered().stream().anyMatch(m -> m.code().equals("CONTRACT")));
    }

    /** 同名 code 覆盖内置定义，用于灰度调整措辞 */
    @Test
    void externalFileOverridesBuiltinDefinition() throws IOException {
        ModeRegistry registry = withFile("""
                {"modes":[{"code":"REVIEW","label":"评审纪要（改）","guidance":"新的指导语"}]}
                """);

        assertEquals("评审纪要（改）", registry.require("REVIEW").label());
        assertEquals("新的指导语", registry.require("REVIEW").guidance());
    }

    /**
     * 外置文件只能追加或覆盖，删不掉内置模式。
     *
     * <p>这是有意的：如果配置文件能删掉 GENERAL，一次写错的部署就会让所有「没填目标」的
     * 提交失去默认模式。构造器里对 GENERAL 的存在性校验因此是防御性的——当前合并策略下
     * 它不该被触发，但一旦有人把合并方式改成「只用外置文件」，启动就会立刻失败而不是静默跑偏。
     */
    @Test
    void externalFileCannotRemoveBuiltinModes() throws IOException {
        ModeRegistry registry = withFile("""
                {"modes":[{"code":"RENAMED","label":"改了名","guidance":"x"}]}
                """);

        assertEquals("GENERAL", registry.defaultMode().code());
        assertEquals("评审纪要", registry.require("REVIEW").label(), "内置模式仍然完整可用");
    }

    @Test
    void emptyGuidanceIsRejectedAtStartup() throws IOException {
        assertThrows(IllegalStateException.class, () -> withFile("""
                {"modes":[{"code":"EMPTY","label":"空指导语","guidance":"  "}]}
                """));
    }

    @Test
    void duplicateCodeIsRejected() throws IOException {
        assertThrows(IllegalStateException.class, () -> withFile("""
                {"modes":[{"code":"A","label":"一","guidance":"x"},
                          {"code":"A","label":"二","guidance":"y"}]}
                """));
    }

    @Test
    void invalidJsonIsRejected() throws IOException {
        assertThrows(IllegalStateException.class, () -> withFile("{not json"));
    }

    /** 从配置里移除的模式，历史数据仍要能显示原始标识，而不是被改写成 GENERAL */
    @Test
    void removedModeStillRendersItsOwnCode() {
        ModeRegistry registry = new ModeRegistry("");

        AnalysisMode gone = registry.forDisplay("RETIRED_MODE");
        assertEquals("RETIRED_MODE", gone.code());
        assertEquals("RETIRED_MODE", gone.label(), "标签缺失时退回 code，而不是谎称它属于别的模式");
    }

    /** 库里存的未知值不能伪装成 GENERAL，否则历史报告会显示错误的分析模式 */
    @Test
    void unknownStoredCodeIsNotSilentlyRewrittenToGeneral() {
        AnalysisMode mode = AnalysisMode.ofCode("LEGACY");
        assertEquals("LEGACY", mode.code());
        assertFalse(mode.code().equals("GENERAL"));
    }

    /** 代码格式非法（例如混入小写或符号）时才回退到 GENERAL */
    @Test
    void malformedStoredCodeFallsBackToGeneral() {
        assertEquals("GENERAL", AnalysisMode.ofCode("bad code!").code());
        assertEquals("GENERAL", AnalysisMode.ofCode(null).code());
    }

    /** 前端需要的清单只暴露 code 与 label，顺序稳定 */
    @Test
    void describeExposesCodeAndLabelInStableOrder() {
        ModeRegistry registry = new ModeRegistry("");
        List<String> codes = registry.describe().stream().map(m -> m.get("code")).toList();

        assertEquals(List.of("GENERAL", "LEARNING", "REVIEW", "CREATION"), codes);
        assertTrue(registry.describe().stream().allMatch(m -> m.get("label") != null));
    }
}
