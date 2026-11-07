package com.videostudio.llm;
import com.videostudio.config.AiProperties;
import com.videostudio.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class LlmGatewayTest {
    @Test void unconfiguredModelThrowsRatherThanReturningTemplateSignal() {
        assertThrows(ApiException.class,()->new LlmGateway(new AiProperties()).chat(List.of(),0,10,"test"));
    }
    @Test void unauthorizedProviderRequestIsNotRetriedOrSilentlyIgnored() throws Exception {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", e -> { count.incrementAndGet(); e.sendResponseHeaders(401,-1); e.close(); }); server.start();
        try {
            var props=new AiProperties(); props.setApiKey("test-fixture"); props.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            var failure=assertThrows(ModelRequestRejectedException.class,()->new LlmGateway(props).chat(List.of(),0,10,"test"));
            assertEquals(502,failure.status());
            assertEquals(1,count.get());
        } finally { server.stop(0); }
    }
    @Test void embeddingResponsesAreReorderedByProviderIndex() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", e -> {
            byte[] body="{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}".getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,body.length); e.getResponseBody().write(body); e.close();
        }); server.start();
        try {
            var props=new AiProperties(); props.setApiKey("test-fixture"); props.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            assertEquals(List.of(List.of(1d,0d),List.of(0d,1d)),new LlmGateway(props).embed(List.of("a","b")));
        } finally { server.stop(0); }
    }
}
