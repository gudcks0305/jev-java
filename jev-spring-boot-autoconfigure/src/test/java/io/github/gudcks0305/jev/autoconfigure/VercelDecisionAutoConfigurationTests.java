package io.github.gudcks0305.jev.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.JevImage;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.spi.JevTransport;
import io.github.gudcks0305.jev.vercel.VercelDecisionInput;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic fixtures only; validates configuration without provider credentials. */
class VercelDecisionAutoConfigurationTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final NoulQuestion PRESENT = NoulQuestion.of("present", "Present?");
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JevAutoConfiguration.class));

    @Test void defaultsToLegacyProtocolAndIgnoresVercelPropertyForOtherProviders() {
        runner.withUserConfiguration(TransportConfiguration.class)
                .withPropertyValues("jev.provider=vercel", "jev.api-key=fixture-key", "jev.transport=webclient")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JevProperties.class).getVercelProtocol()).isEqualTo("evaluation-v4");
                    context.getBean(JevClient.class).evaluateAsync("state", PRESENT).cancel(true);
                    var transport = context.getBean(Capture.class);
                    assertThat(transport.uri).isEqualTo(URI.create("https://ai-gateway.vercel.sh/v4/ai/evaluation-model"));
                    assertThat(transport.body.has("state")).isTrue();
                    assertThat(transport.headers).containsKey("ai-evaluation-model-specification-version");
                });
        runner.withPropertyValues("jev.provider=typesafe", "jev.api-key=fixture-key", "jev.vercel-protocol=unknown")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision-v4", "DECISION-V4"})
    void bindsDecisionProtocolForWebClientAndNativeReactorInput(String protocol) {
        runner.withUserConfiguration(TransportConfiguration.class)
                .withPropertyValues("jev.provider=vercel", "jev.api-key=fixture-key", "jev.transport=webclient",
                        "jev.vercel-protocol=" + protocol, "jev.model=bound-model")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var image = JevImage.fromBytes(new byte[]{1, 2, 3}, "png");
                    var input = VercelDecisionInput.builder().text("Inspect").image(image).build();
                    var subscription = context.getBean(ReactorJevClient.class).evaluate(input, PRESENT).subscribe();
                    var transport = context.getBean(Capture.class);
                    assertThat(transport.uri).isEqualTo(URI.create("https://ai-gateway.vercel.sh/v4/ai/decision-model"));
                    assertThat(transport.headers).containsEntry("ai-decision-model-specification-version", "4")
                            .containsEntry("ai-model-id", "bound-model").doesNotContainKey("ai-evaluation-model-specification-version");
                    assertThat(transport.body.at("/stateParts/1/data/data").textValue()).isEqualTo("AQID");
                    assertThat(transport.body.has("state")).isFalse();
                    subscription.dispose();
                    assertThat(transport.pending.isCancelled()).isTrue();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "decision_v4", ""})
    void rejectsUnknownVercelProtocolBeforeCreatingClient(String protocol) {
        runner.withPropertyValues("jev.provider=vercel", "jev.api-key=fixture-key", "jev.vercel-protocol=" + protocol)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "jev.vercel-protocol must be evaluation-v4 or decision-v4");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk", "webclient"})
    void supportsNativeImageThroughConfiguredTransports(String transport) throws Exception {
        var request = new AtomicReference<JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/decision", exchange -> {
            request.set(JSON.readTree(exchange.getRequestBody()));
            byte[] response = "{\"answers\":{\"present\":{\"type\":\"boolean\",\"probability\":0.9}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            runner.withPropertyValues("jev.provider=vercel", "jev.api-key=fixture-key", "jev.transport=" + transport,
                            "jev.vercel-protocol=decision-v4", "jev.max-retries=0",
                            "jev.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/decision")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        var input = VercelDecisionInput.builder().image(JevImage.fromBytes(new byte[]{1}, "jpeg")).build();
                        var result = context.getBean(JevClient.class).evaluate(input, PRESENT);
                        assertThat(result.answer(PRESENT).probability()).isEqualTo(.9);
                        assertThat(request.get().at("/stateParts/0/mediaType").textValue()).isEqualTo("image/jpeg");
                    });
        } finally { server.stop(0); }
    }

    @Configuration(proxyBeanMethods = false)
    static class TransportConfiguration {
        @Bean Capture capture() { return new Capture(); }
    }
    static final class Capture implements JevTransport {
        private URI uri;
        private Map<String, String> headers;
        private JsonNode body;
        private CompletableFuture<JsonNode> pending;
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            pending = new CompletableFuture<>();
            return pending;
        }
        @Override public void close() {}
    }
}
