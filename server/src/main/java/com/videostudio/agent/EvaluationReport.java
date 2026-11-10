package com.videostudio.agent;

import com.videostudio.domain.TranscriptSegment;
import com.videostudio.domain.VisualEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 产物质量评测：对一份已生成的草稿算一组可比较的指标。
 *
 * <p>为什么需要它：改提示词、调检索权重、放宽校验阈值之后，很难判断是变好还是变坏——
 * 单看「这次跑通了」说明不了问题。这里把质量拆成几个稳定可比的数字，
 * 让改动前后能在同一把尺子上比较。
 *
 * <p>指标全部是<b>确定性</b>的，不调用模型：同样的输入必然得到同样的分数，
 * 因此可以作为回归基线长期保留。模型主观判断（措辞是否得体、推断是否越界）
 * 不在这里衡量——那属于 Critic 的职责，且不可复现。
 *
 * @param structuredValid   结构完整（有标题、有结论、有证据）
 * @param timestampCoverage 证据时间戳落在素材范围内的比例
 * @param evidenceSupport   证据原文能在对应来源中核验的比例
 * @param claimSupport      结论能找到绑定证据的比例
 * @param keywordCoverage   期望关键词被结论覆盖的比例（无期望时记为 1）
 */
public record EvaluationReport(boolean structuredValid, double timestampCoverage,
                               double evidenceSupport, double claimSupport, double keywordCoverage,
                               int evidenceCount, int conclusionCount, List<String> notes) {

    /**
     * 综合分：以「证据是否真能核验」为主。
     *
     * <p>timestampCoverage 权重最低（0.10）：它只说明时间点落在素材范围内，
     * 一条完全编造的证据只要时间戳随便落在某个片段里就能拿满分。
     * 真正说明质量的是 evidenceSupport 与 claimSupport——文字能否对上原文。
     */
    public double score() {
        if (!structuredValid) {
            return 0;
        }
        return 0.10 * timestampCoverage + 0.45 * evidenceSupport
                + 0.30 * claimSupport + 0.15 * keywordCoverage;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("structuredValid", structuredValid);
        map.put("timestampCoverage", round(timestampCoverage));
        map.put("evidenceSupport", round(evidenceSupport));
        map.put("claimSupport", round(claimSupport));
        map.put("keywordCoverage", round(keywordCoverage));
        map.put("score", round(score()));
        map.put("evidenceCount", evidenceCount);
        map.put("conclusionCount", conclusionCount);
        map.put("notes", notes);
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
