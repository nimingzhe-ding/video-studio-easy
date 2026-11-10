package com.videostudio.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 非法 JSON 属于调用方输入错误，必须 400，而不是让客户端以为服务端故障可以重试。 */
class ApiExceptionHandlerTest {

    @Test
    void malformedJsonIsBadRequestNotServerError() {
        var error = new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));

        var response = new ApiExceptionHandler().unreadable(error);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().ok());
        assertFalse(response.getBody().message().contains("bad json"), "不应回显解析细节");
    }
}
