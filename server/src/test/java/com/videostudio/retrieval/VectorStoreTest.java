package com.videostudio.retrieval;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class VectorStoreTest {
    @Test void unavailableVectorServiceFailsRatherThanUsingLocalCosine() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(503,-1); exchange.close(); }); server.start();
        try {
            var vectors=new QdrantVectorStore("http://127.0.0.1:"+server.getAddress().getPort(),"test","test_chunks",3);
            assertThrows(IllegalStateException.class,()->vectors.semanticScores(1,List.of(),List.of(1d,0d,0d)));
        } finally { server.stop(0); }
    }
    @Test void dimensionMismatchDoesNotDeleteExistingCollection() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var deletes=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            if("DELETE".equals(exchange.getRequestMethod())) deletes.incrementAndGet();
            byte[] body="{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":4}}}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            var vectors=new QdrantVectorStore("http://127.0.0.1:"+server.getAddress().getPort(),"test","test_chunks",3);
            assertThrows(IllegalStateException.class,vectors::initialize); assertEquals(0,deletes.get());
        } finally { server.stop(0); }
    }
}
