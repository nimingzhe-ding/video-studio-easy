package com.videostudio.infra;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.videostudio.common.SafeErrors;

public class SafeMessageConverter extends MessageConverter {
    @Override public String convert(ILoggingEvent event) {
        return SafeErrors.redact(super.convert(event));
    }
}
