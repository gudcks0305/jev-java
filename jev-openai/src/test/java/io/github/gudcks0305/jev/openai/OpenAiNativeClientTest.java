package io.github.gudcks0305.jev.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class OpenAiNativeClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USAGE = """
            {"input_tokens":12,"output_tokens":0,"total_tokens":12,
             "input_tokens_details":{"cached_tokens":2,"cache_write_tokens":0},
             "output_tokens_details":{"reasoning_tokens":0}}
            """;

    private static DecisionRequest request() {
        return new DecisionRequest(DecisionInput.text("A synthetic ticket"),
                List.of(new DecisionQuestion.Predicate(null, "Is this a ticket?")));
    }

    @Test
    void nativeHttpPreservesTypedValuesUnnamedRefusalAndRequestOptions() throws Exception {
        AtomicReference<JsonNode> sent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/decisions", exchange -> {
            sent.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = ("""
                    {"model":"resolved","answers":[
                     {"name":null,"type":"refusal"},
                     {"name":"flag","type":"choice","choice":true,"confidence":0.8,
                      "probabilities":[{"value":true,"probability":0.8},{"value":"true","probability":0.2}]}],
                     "usage":
                    """ + USAGE + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        var question = new DecisionQuestion.Choice("flag", "Pick one",
                List.of(new DecisionQuestion.Option(DecisionValue.bool(true), "Boolean"),
                        new DecisionQuestion.Option(DecisionValue.text("true"), "Text")));
        DecisionRequest request = new DecisionRequest(DecisionInput.text("text"),
                List.of(new DecisionQuestion.Predicate(null, ""), question), "request-model", "user-opaque");
        try (var client = OpenAiJevClient.builder().apiKey("test-key")
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).build()) {
            var result = client.decide(request);
            assertEquals("request-model", sent.get().path("model").textValue());
            assertEquals("user-opaque", sent.get().path("safety_identifier").textValue());
            assertFalse(sent.get().at("/questions/0").has("name"));
            assertEquals("", sent.get().at("/questions/0/instructions").textValue());
            assertTrue(sent.get().at("/questions/1/choices/0/value").isBoolean());
            assertTrue(sent.get().at("/questions/1/choices/1/value").isTextual());
            assertInstanceOf(DecisionAnswer.Refusal.class, result.answers().get(0));
            assertNull(result.answers().get(0).name());
            var choice = assertInstanceOf(DecisionAnswer.Choice.class, result.answers().get(1));
            assertEquals(DecisionValue.bool(true), choice.choice());
            assertEquals(2, choice.probabilities().size());
            assertEquals("resolved", result.model());
            assertEquals(12, result.usage().inputTokens());
            assertEquals(0, result.usage().outputTokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nativeCancellationAndClientCloseShareLifecycleWithEvaluate() {
        var transport = new PendingTransport();
        var client = OpenAiJevClient.builder().apiKey("test-key").transport(transport).build();
        var cancelled = client.decideAsync(request());
        var first = transport.pending;
        assertTrue(cancelled.cancel(true));
        assertTrue(first.isCancelled());
        var nativeFuture = client.decideAsync(request());
        var nativeSource = transport.pending;
        var genericFuture = client.evaluateAsync("text", NoulQuestion.of("flag", "Is it a ticket?"));
        var genericSource = transport.pending;
        client.close();
        assertClosed(nativeFuture);
        assertClosed(genericFuture);
        assertTrue(nativeSource.isCancelled());
        assertTrue(genericSource.isCancelled());
        assertFalse(transport.closed);
        assertClosed(client.decideAsync(null)); // Closed precedes request validation.
    }

    @Test
    void cancellationStillReachesSourceWhenCloseRacesWithTransportReturn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<JsonNode> pending = new CompletableFuture<>();
        JevTransport transport = new JevTransport() {
            @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Transport release timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return pending;
            }
            @Override public void close() {}
        };
        var client = OpenAiJevClient.builder().apiKey("test-key").transport(transport).build();
        var invocation = CompletableFuture.supplyAsync(() -> client.decideAsync(request()));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            client.close();
        } finally {
            release.countDown();
        }
        assertClosed(invocation.get(5, TimeUnit.SECONDS));
        assertTrue(pending.isCancelled());
    }

    @Test
    void blockingInterruptCancelsNativeRequestAndPreservesInterrupt() throws Exception {
        var transport = new PendingTransport();
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        try (var client = OpenAiJevClient.builder().apiKey("test-key").transport(transport).build()) {
            Thread worker = new Thread(() -> {
                try { client.decide(request()); }
                catch (Throwable error) { failure.set(error); }
                finally { interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            worker.start();
            assertTrue(transport.entered.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(5000);
            assertFalse(worker.isAlive());
            assertEquals(JevException.Kind.CONNECTION, assertInstanceOf(JevException.class, failure.get()).kind());
            assertTrue(interrupted.get());
            assertTrue(transport.pending.isCancelled());
        }
    }

    private static void assertClosed(CompletableFuture<?> future) {
        var error = assertThrows(CompletionException.class, future::join);
        assertEquals(JevException.Kind.CLOSED, assertInstanceOf(JevException.class, error.getCause()).kind());
    }
    private static final class PendingTransport implements JevTransport {
        volatile CompletableFuture<JsonNode> pending;
        final CountDownLatch entered = new CountDownLatch(1);
        boolean closed;
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            pending = new CompletableFuture<>();
            entered.countDown();
            return pending;
        }
        @Override public void close() { closed = true; }
    }
}
