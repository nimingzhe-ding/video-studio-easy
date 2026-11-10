package com.videostudio.web;

import com.videostudio.common.ApiException;
import com.videostudio.common.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 统一异常 → { ok:false, message }，避免把堆栈暴露给前端 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error("文件超过上传大小限制"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> missing(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("缺少参数：" + e.getParameterName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        // 请求体不是合法 JSON 属于调用方输入问题，返回 500 会让客户端误以为可以重试
        log.debug("请求体无法解析", e);
        return ResponseEntity.badRequest().body(ApiResponse.error("请求体不是合法的 JSON"));
    }

    @ExceptionHandler({org.springframework.dao.DataAccessResourceFailureException.class,
        org.springframework.data.redis.RedisConnectionFailureException.class})
    public ResponseEntity<ApiResponse<Void>> unavailable(Exception e) {
        log.error("共享数据服务不可用",e);
        return ResponseEntity.status(503).body(ApiResponse.error("服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("服务内部错误，请稍后重试"));
    }
}
