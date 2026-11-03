package com.videostudio.domain;

/** 任务状态机 */
public enum JobStatus {
    /** 排队中 */
    PENDING,
    /** 执行中 */
    RUNNING,
    /** 成功 */
    SUCCEEDED,
    /** 失败 */
    FAILED,
    /** 已取消 */
    CANCELED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELED;
    }

    public boolean running() {
        return this == PENDING || this == RUNNING;
    }
}
