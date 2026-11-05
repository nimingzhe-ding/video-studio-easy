package com.videostudio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流阈值（单位：次 / 分钟，<=0 表示关闭）。
 * <p>阈值刻意设得比较宽松：目标是挡住脚本刷接口和误操作，
 * 而不是给正常用户制造麻烦——限流太紧时用户会以为系统坏了。
 */
@ConfigurationProperties(prefix = "rate")
public class RateLimitProperties {

    /** 同一素材提交分析的次数上限 */
    private int submitPerMinute = 12;
    /** 上传次数上限（按客户端 IP） */
    private int uploadPerMinute = 30;
    /** 追问次数上限（按任务） */
    private int chatPerMinute = 40;
    /** 实时对话建连次数上限（按 IP） */
    private int realtimePerMinute = 30;

    public int getSubmitPerMinute() {
        return submitPerMinute;
    }

    public void setSubmitPerMinute(int submitPerMinute) {
        this.submitPerMinute = submitPerMinute;
    }

    public int getUploadPerMinute() {
        return uploadPerMinute;
    }

    public void setUploadPerMinute(int uploadPerMinute) {
        this.uploadPerMinute = uploadPerMinute;
    }

    public int getChatPerMinute() {
        return chatPerMinute;
    }

    public void setChatPerMinute(int chatPerMinute) {
        this.chatPerMinute = chatPerMinute;
    }

    public int getRealtimePerMinute() {
        return realtimePerMinute;
    }

    public void setRealtimePerMinute(int realtimePerMinute) {
        this.realtimePerMinute = realtimePerMinute;
    }
}
