package com.videostudio.infra;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SafeLoggingTest {
    @Test @SuppressWarnings("unchecked") void configuredConsoleRedactsMessageAndNestedExceptions() throws Exception {
        var context=new LoggerContext();
        context.setMDCAdapter(new LogbackMDCAdapter());
        try {
            var configurator=new JoranConfigurator(); configurator.setContext(context);
            configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
            var root=context.getLogger(Logger.ROOT_LOGGER_NAME);
            var appender=(OutputStreamAppender<ILoggingEvent>)root.getAppender("CONSOLE");
            assertNotNull(appender); assertTrue(appender.isStarted());
            var failure=new IllegalStateException("api_key=hidden-cause-456",new RuntimeException("password='hidden-password-789'"));
            failure.addSuppressed(new IllegalArgumentException("Bearer hidden-suppressed-000"));
            var event=new LoggingEvent(getClass().getName(),root,Level.ERROR,"request failed Bearer {}",failure,new Object[]{"hidden-message-123"});
            String output=new String(appender.getEncoder().encode(event),StandardCharsets.UTF_8);
            assertTrue(output.contains("[REDACTED]"),output);
            assertTrue(output.contains("Caused by:"),output);
            assertTrue(output.contains("SafeLoggingTest.java"),output);
            assertFalse(output.contains("hidden-"),output);
            assertFalse(output.contains("PARSER_ERROR"),output);
        } finally { context.stop(); }
    }
}
