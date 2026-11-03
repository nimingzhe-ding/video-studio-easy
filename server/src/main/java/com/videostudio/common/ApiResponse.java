package com.videostudio.common;

/** 统一响应包装：{ ok, data, message } */
public record ApiResponse<T>(boolean ok, T data, String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
