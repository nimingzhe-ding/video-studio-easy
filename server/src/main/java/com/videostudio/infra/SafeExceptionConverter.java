package com.videostudio.infra;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.videostudio.common.SafeErrors;

/** Retains stack frames and causes while removing credentials from their messages. */
public class SafeExceptionConverter extends ThrowableProxyConverter {
    @Override public String convert(ILoggingEvent event) {
        return SafeErrors.redact(super.convert(event));
    }
}
