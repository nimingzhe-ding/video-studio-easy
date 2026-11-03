package com.videostudio.domain;

/** 分析流水线阶段，用于进度与事件展示 */
public enum JobStage {
    QUEUED("排队", 0),
    PROBE("解析媒体", 5),
    AUDIO("音频抽取", 15),
    TRANSCRIBE("语音识别", 30),
    FRAMES("关键帧抽取", 45),
    OCR("画面文字识别", 55),
    SEGMENT("时间轴对齐", 65),
    CHUNK("分块与索引", 75),
    RETRIEVE("检索证据", 82),
    PLAN("规划提纲", 86),
    EXECUTE("撰写正文", 92),
    CRITIC("证据校验", 96),
    REPORT("生成文档", 99),
    DONE("完成", 100),
    FAILED("失败", 100);

    private final String label;
    private final int floor;

    JobStage(String label, int floor) {
        this.label = label;
        this.floor = floor;
    }

    public String label() {
        return label;
    }

    public int floor() {
        return floor;
    }
}
