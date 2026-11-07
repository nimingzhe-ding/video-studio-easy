package com.videostudio.llm;

import com.videostudio.common.ApiException;

/** Keep the upstream classification without exposing its response body or credentials. */
public class ModelRequestRejectedException extends ApiException {
    public ModelRequestRejectedException(int upstreamStatus) {
        super(502,"模型服务拒绝请求（HTTP "+upstreamStatus+"），请检查模型与服务端配置");
    }
}
