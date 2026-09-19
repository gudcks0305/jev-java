package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.JevException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransportTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<HttpTransport> transports = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        transports.forEach(HttpTransport::close);
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void postsAndParsesJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            assertEquals("fake-api-key", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"decision\":\"yes\"}");
        });
        HttpTransport transport = transport(Duration.ofSeconds(2), 0);

        var result = transport.post(uri(server), Map.of("Authorization", "fake-api-key"),
                JSON.readTree("{\"input\":\"hello\"}"));

        assertEquals("yes", result.join().get("decision").asText());
        assertEquals(1, requests.get());
    }

    @Test
    void authenticationFailureDoesNotRetryOrExposeResponse() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 401, "secret-token-and-state");
        });
        HttpTransport transport = transport(Duration.ofSeconds(2), 3);

        JevException failure = failure(transport.post(
                uri(server), Map.of("Authorization", "fake-api-key"), JSON.createObjectNode()));

        assertEquals(JevException.Kind.AUTHENTICATION, failure.kind());
        assertEquals(401, failure.statusCode());
        assertFalse(failure.getMessage().contains("secret-token-and-state"));
        assertEquals(1, requests.get());
    }

    @Test
    void retries429ThenReturnsJson() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                respond(exchange, 429, "rate limited");
            } else {
                respond(exchange, 200, "{\"retried\":true}");
            }
        });
        HttpTransport transport = transport(Duration.ofSeconds(2), 1);

        var result = transport.post(uri(server), Map.of(), JSON.createObjectNode()).join();

        assertTrue(result.get("retried").asBoolean());
        assertEquals(2, requests.get());
    }

    @Test
    void retryAfterLongerThanDeadlineTimesOutWithoutAnotherRequest() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "10");
            respond(exchange, 429, "rate limited");
        });
        HttpTransport transport = transport(Duration.ofSeconds(1), 2);

        JevException failure = failure(transport.post(
                uri(server), Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.TIMEOUT, failure.kind());
        assertEquals(1, requests.get());
    }

    @Test
    void totalDeadlineCancelsSlowRequest() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            accepted.countDown();
            await(release);
            respondIgnoringDisconnect(exchange, 200, "{}");
        });
        HttpTransport transport = transport(Duration.ofSeconds(1), 3);

        CompletableFuture<?> call = transport.post(
                uri(server), Map.of(), JSON.createObjectNode());
        assertTrue(accepted.await(3, TimeUnit.SECONDS));
        try {
            assertEquals(JevException.Kind.TIMEOUT, failure(call).kind());
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void callerCancellationStopsCall() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            accepted.countDown();
            await(release);
            respondIgnoringDisconnect(exchange, 200, "{}");
        });
        HttpTransport transport = transport(Duration.ofSeconds(5), 2);
        CompletableFuture<?> call = transport.post(
                uri(server), Map.of(), JSON.createObjectNode());
        assertTrue(accepted.await(3, TimeUnit.SECONDS));

        try {
            assertTrue(call.cancel(true));
            assertTrue(call.isCancelled());
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeCancelsActiveCallAndRejectsNewCalls() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            accepted.countDown();
            await(release);
            respondIgnoringDisconnect(exchange, 200, "{}");
        });
        HttpTransport transport = transport(Duration.ofSeconds(5), 2);
        CompletableFuture<?> active = transport.post(
                uri(server), Map.of(), JSON.createObjectNode());
        assertTrue(accepted.await(3, TimeUnit.SECONDS));

        try {
            transport.close();
            assertEquals(JevException.Kind.CLOSED, failure(active).kind());
            assertEquals(JevException.Kind.CLOSED, failure(transport.post(
                    uri(server), Map.of(), JSON.createObjectNode())).kind());
            assertEquals(1, requests.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void successfulNonJsonResponseIsProtocolFailure() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "not-json"));
        HttpTransport transport = transport(Duration.ofSeconds(2), 0);

        JevException failure = failure(transport.post(
                uri(server), Map.of(), JSON.createObjectNode()));

        assertEquals(JevException.Kind.PROTOCOL, failure.kind());
    }

    private HttpTransport transport(Duration timeout, int maxRetries) {
        HttpTransport transport = new HttpTransport(HttpClient.newHttpClient(), timeout, maxRetries);
        transports.add(transport);
        return transport;
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        servers.add(server);
        return server;
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private static JevException failure(CompletableFuture<?> future) {
        CompletionException completion = assertThrows(CompletionException.class, future::join);
        return (JevException) completion.getCause();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondIgnoringDisconnect(HttpExchange exchange, int status, String body) {
        try {
            respond(exchange, status, body);
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
