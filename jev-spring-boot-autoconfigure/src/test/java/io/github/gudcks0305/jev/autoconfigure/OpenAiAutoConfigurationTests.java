package io.github.gudcks0305.jev.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.openai.OpenAiJevClient;
import io.github.gudcks0305.jev.openai.DecisionInput;
import io.github.gudcks0305.jev.openai.DecisionQuestion;
import io.github.gudcks0305.jev.openai.DecisionRequest;
import io.github.gudcks0305.jev.spi.JevTransport;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import io.github.gudcks0305.jev.webflux.ReactorOpenAiClient;
import io.github.gudcks0305.jev.webflux.WebClientJevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiAutoConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JevAutoConfiguration.class, OpenAiReactorAutoConfiguration.class));

    @Test
    void configuresTypeSafeWhenOpenAiClassesAreTrulyAbsent() throws Exception {
        URL location = JevAutoConfiguration.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new IsolatedAutoConfigurationClassLoader(location)) {
            Class<?> configuration = Class.forName(JevAutoConfiguration.class.getName(), true, loader);
            Class<?> reactorConfiguration = Class.forName(OpenAiReactorAutoConfiguration.class.getName(), true, loader);
            assertThat(configuration.getClassLoader()).isSameAs(loader);
            assertThatThrownBy(() -> loader.loadClass(OpenAiJevClient.class.getName()))
                    .isInstanceOf(ClassNotFoundException.class);
            // Reflection resolves method descriptors through the defining loader, not a
            // parent loader which could still see the optional provider dependency.
            assertThat(configuration.getDeclaredMethods()).isNotEmpty();
            new ApplicationContextRunner()
                    .withClassLoader(loader)
                    .withConfiguration(AutoConfigurations.of(configuration, reactorConfiguration))
                    .withPropertyValues("jev.provider=typesafe", "jev.api-key=test-key")
                    .run(context -> {
                        assertThat(context).hasSingleBean(JevClient.class);
                        assertThat(context.getBean(JevClient.class))
                                .isInstanceOf(io.github.gudcks0305.jev.typesafe.TypeSafeJevClient.class);
                        assertThat(context).doesNotHaveBean("openAiJevClient");
                        assertThat(context).doesNotHaveBean("reactorOpenAiClient");
                    });
        }
    }

    @Test
    void uppercaseOpenAiProviderIsIndependentOfTurkishDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            this.contextRunner
                    .withPropertyValues("jev.provider=OPENAI", "jev.api-key=test-key")
                    .run(context -> {
                        assertThat(context).hasSingleBean(JevClient.class);
                        assertThat(context).hasSingleBean(OpenAiJevClient.class);
                        assertThat(context).hasSingleBean(ReactorOpenAiClient.class);
                    });
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void injectsTypedNativeClientAndBothReactorFacadesWithoutDuplicateClients() {
        this.contextRunner
                .withUserConfiguration(NativeClientConsumerConfiguration.class)
                .withPropertyValues("jev.provider=openai", "jev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context).hasSingleBean(OpenAiJevClient.class);
                    assertThat(context).hasSingleBean(ReactorOpenAiClient.class);
                    assertThat(context).hasSingleBean(ReactorJevClient.class);
                    assertThat(context.getBean(NativeClientConsumer.class).client())
                            .isSameAs(context.getBean(JevClient.class));
                    var request = new DecisionRequest(DecisionInput.text("test state"),
                            List.of(new DecisionQuestion.Predicate("decision", "Decide")));
                    assertThat(context.getBean(ReactorOpenAiClient.class).decide(request)).isNotNull();
                });
    }

    @Test
    void customDifferentJevClientSuppressesOpenAiClientAndNativeFacade() {
        this.contextRunner
                .withUserConfiguration(JevAutoConfigurationTests.CustomClientConfiguration.class)
                .withPropertyValues("jev.provider=openai")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context).doesNotHaveBean(OpenAiJevClient.class);
                    assertThat(context).doesNotHaveBean(ReactorOpenAiClient.class);
                    assertThat(context).hasSingleBean(ReactorJevClient.class);
                });
    }

    @Test
    void callerProvidedTypedOpenAiClientGetsNativeFacade() {
        this.contextRunner
                .withUserConfiguration(CustomOpenAiClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context).hasSingleBean(OpenAiJevClient.class);
                    assertThat(context).hasSingleBean(ReactorOpenAiClient.class);
                    assertThat(context.getBean(JevClient.class))
                            .isSameAs(context.getBean("customOpenAiClient"));
                });
    }

    @Test
    void disabledAutoConfigurationDoesNotWrapCustomOpenAiClient() {
        this.contextRunner
                .withUserConfiguration(CustomOpenAiClientConfiguration.class)
                .withPropertyValues("jev.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiJevClient.class);
                    assertThat(context).doesNotHaveBean(ReactorOpenAiClient.class);
                    assertThat(context).doesNotHaveBean(ReactorJevClient.class);
                });
    }

    @Test
    void usesJdkDefaultModelAndBaseUrlSuffixWithoutWebFlux() throws Exception {
        assertLocalRequest(false, false);
    }

    @Test
    void usesJdkExactEndpointWithQueryAndModelOverride() throws Exception {
        assertLocalRequest(false, true);
    }

    @Test
    void usesWebClientAndLazyReactorWithDefaultModel() throws Exception {
        assertLocalRequest(true, false);
    }

    @Test
    void usesWebClientExactEndpointWithQueryAndModelOverride() throws Exception {
        assertLocalRequest(true, true);
    }

    @Test
    void propagatesOpenAiRefusalThroughReactor() throws Exception {
        assertLocalRequest(true, false, true);
    }

    @Test
    void rejectsConflictingOpenAiEndpointAndBaseUrl() {
        this.contextRunner
                .withPropertyValues(
                        "jev.provider=openai", "jev.api-key=test-key",
                        "jev.base-url=https://base.example.test",
                        "jev.endpoint=https://endpoint.example.test/v1/decisions")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Configure either baseUrl or endpoint, not both");
                });
    }

    private void assertLocalRequest(boolean webClient, boolean exactEndpoint) throws Exception {
        assertLocalRequest(webClient, exactEndpoint, false);
    }

    private void assertLocalRequest(boolean webClient, boolean exactEndpoint, boolean refusal) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<URI> requestUri = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        String model = exactEndpoint ? "custom-decision-model" : "gpt-6-luna";
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestUri.set(exchange.getRequestURI());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(mapper.readTree(exchange.getRequestBody()));
            String answer = refusal
                    ? "{\"type\":\"refusal\",\"name\":\"decision\"}"
                    : "{\"type\":\"predicate\",\"name\":\"decision\",\"probability\":0.8}";
            byte[] response = ("{\"model\":\"" + model
                    + "\",\"answers\":[" + answer + "],"
                    + "\"usage\":{\"input_tokens\":1,\"output_tokens\":0,\"total_tokens\":1,"
                    + "\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},"
                    + "\"output_tokens_details\":{\"reasoning_tokens\":0}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            ApplicationContextRunner runner = this.contextRunner.withPropertyValues(
                    "jev.provider=openai", "jev.api-key=bound-key",
                    "jev.timeout=5s", "jev.max-retries=0",
                    "jev.transport=" + (webClient ? "webclient" : "jdk"),
                    exactEndpoint
                            ? "jev.endpoint=" + origin + "/proxy/decisions?tenant=a-b&mode=fast"
                            : "jev.base-url=" + origin + "/gateway");
            if (exactEndpoint) {
                runner = runner.withPropertyValues("jev.model=" + model);
            }
            if (!webClient) {
                runner = runner.withClassLoader(new FilteredClassLoader(
                        "io.github.gudcks0305.jev.webflux", "reactor", "org.springframework.web.reactive"));
            }
            runner.run(context -> {
                assertThat(context).hasSingleBean(JevClient.class);
                assertThat(context.getBean(JevClient.class)).isInstanceOf(OpenAiJevClient.class);
                NoulQuestion question = NoulQuestion.of("decision", "Decide");
                Evaluation result;
                if (webClient) {
                    assertThat(context.getBean(JevTransport.class)).isInstanceOf(WebClientJevTransport.class);
                    assertThat(context).hasSingleBean(ReactorJevClient.class);
                    var pending = context.getBean(ReactorJevClient.class).evaluate("test state", question);
                    assertThat(requests.get()).isZero();
                    if (refusal) {
                        assertThatThrownBy(() -> pending.block(Duration.ofSeconds(5)))
                                .isInstanceOfSatisfying(JevException.class,
                                        failure -> assertThat(failure.kind()).isEqualTo(JevException.Kind.REFUSAL));
                        assertThat(requests.get()).isEqualTo(1);
                        return;
                    }
                    result = pending.block(Duration.ofSeconds(5));
                } else {
                    assertThat(context).doesNotHaveBean(JevTransport.class);
                    assertThat(context).doesNotHaveBean("reactorJevClient");
                    result = context.getBean(JevClient.class).evaluate("test state", question);
                }
                assertThat(result).isNotNull();
                assertThat(result.answer(question).probability()).isEqualTo(0.8);
                assertThat(result.model()).isEqualTo(model);
                assertThat(result.usage().inputTokens()).hasValue(1);
                assertThat(result.usage().outputTokens()).hasValue(0);
                assertThat(requests.get()).isEqualTo(1);
                assertThat(requestUri.get().toString()).isEqualTo(exactEndpoint
                        ? "/proxy/decisions?tenant=a-b&mode=fast" : "/gateway/v1/decisions");
                assertThat(authorization.get()).isEqualTo("Bearer bound-key");
                JsonNode body = requestBody.get();
                assertThat(body.path("model").asText()).isEqualTo(model);
                assertThat(body.path("input").asText()).isEqualTo("test state");
                assertThat(body.has("state")).isFalse();
                assertThat(body.path("questions").isArray()).isTrue();
                assertThat(body.path("questions").size()).isEqualTo(1);
                assertThat(body.path("questions").get(0).path("name").asText()).isEqualTo("decision");
                assertThat(body.path("questions").get(0).path("type").asText()).isEqualTo("predicate");
                assertThat(body.path("questions").get(0).path("instructions").asText()).isEqualTo("Decide");
            });
        } finally {
            server.stop(0);
        }
    }

    record NativeClientConsumer(OpenAiJevClient client) {}

    @Configuration(proxyBeanMethods = false)
    static class NativeClientConsumerConfiguration {
        @Bean
        NativeClientConsumer nativeClientConsumer(OpenAiJevClient client) {
            return new NativeClientConsumer(client);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomOpenAiClientConfiguration {
        @Bean(destroyMethod = "close")
        OpenAiJevClient customOpenAiClient() {
            return OpenAiJevClient.builder().apiKey("test-key").build();
        }
    }

    private static final class IsolatedAutoConfigurationClassLoader extends URLClassLoader {
        IsolatedAutoConfigurationClassLoader(URL location) {
            super(new URL[] {location}, JevAutoConfiguration.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("io.github.gudcks0305.jev.openai.")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith("io.github.gudcks0305.jev.autoconfigure.")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
