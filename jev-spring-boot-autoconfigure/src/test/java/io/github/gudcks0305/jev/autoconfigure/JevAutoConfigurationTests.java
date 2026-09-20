package io.github.gudcks0305.jev.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.Question;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;
import io.github.gudcks0305.jev.spi.JevTransport;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import io.github.gudcks0305.jev.webflux.WebClientJevTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class JevAutoConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JevAutoConfiguration.class));

    private final ApplicationContextRunner autoDiscoveryContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableAutoConfigurationTestConfiguration.class);

    @Test
    void configuresTypeSafeByDefault() {
        this.contextRunner
                .withPropertyValues("jev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context.getBean(JevClient.class)).isInstanceOf(TypeSafeJevClient.class);
                });
    }

    @Test
    void discoversAutoConfigurationFromImportsFile() {
        this.autoDiscoveryContextRunner
                .withPropertyValues("jev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context.getBean(JevClient.class)).isInstanceOf(TypeSafeJevClient.class);
                    assertThat(context).hasSingleBean(JevProperties.class);
                });
    }

    @Test
    void resolvesApiKeyPlaceholder() {
        this.contextRunner
                .withSystemProperties("TYPESAFE_API_KEY=test-key")
                .withPropertyValues("jev.api-key=${TYPESAFE_API_KEY}")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context.getBean(JevProperties.class).getApiKey()).isEqualTo("test-key");
                });
    }

    @Test
    void configuresVercelProvider() {
        this.contextRunner
                .withPropertyValues("jev.provider=vercel", "jev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context.getBean(JevClient.class)).isInstanceOf(VercelJevClient.class);
                });
    }

    @Test
    void configuresOpenRouterProvider() {
        this.contextRunner
                .withPropertyValues("jev.provider=openrouter", "jev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context.getBean(JevClient.class)).isInstanceOf(OpenRouterJevClient.class);
                });
    }

    @Test
    void appliesApiKeyModelAndExactEndpointToEveryProviderRequest() {
        assertProviderRequest("typesafe", TypeSafeJevClient.class, false);
        assertProviderRequest("vercel", VercelJevClient.class, true);
        assertProviderRequest("openrouter", OpenRouterJevClient.class, false);
    }

    @Test
    void configuresWebClientTransportAndReactorFacade() {
        this.contextRunner
                .withPropertyValues("jev.api-key=test-key", "jev.transport=webclient")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevTransport.class);
                    assertThat(context.getBean(JevTransport.class)).isInstanceOf(WebClientJevTransport.class);
                    assertThat(context).hasSingleBean(ReactorJevClient.class);
                });
    }

    @Test
    void usesUserProvidedWebClientAndItsFilters() {
        this.contextRunner
                .withUserConfiguration(UserWebClientConfiguration.class)
                .withPropertyValues("jev.api-key=test-key", "jev.transport=webclient")
                .run(context -> {
                    UserWebClientProbe probe = context.getBean(UserWebClientProbe.class);
                    context.getBean(JevClient.class)
                            .evaluateAsync("test state", NoulQuestion.of("decision", "Decide"))
                            .handle((result, failure) -> null)
                            .join();
                    assertThat(probe.filterCalls.get()).isEqualTo(1);
                    assertThat(probe.exchangeCalls.get()).isEqualTo(1);
                });
    }

    @Test
    void bindsAllProperties() {
        this.contextRunner
                .withPropertyValues(
                        "jev.api-key=test-key",
                        "jev.model=test-model",
                        "jev.transport=jdk",
                        "jev.base-url=https://example.test/api",
                        "jev.timeout=5s",
                        "jev.max-retries=4")
                .run(context -> {
                    JevProperties properties = context.getBean(JevProperties.class);
                    assertThat(properties.getApiKey()).isEqualTo("test-key");
                    assertThat(properties.getModel()).isEqualTo("test-model");
                    assertThat(properties.getTransport()).isEqualTo(JevProperties.Transport.JDK);
                    assertThat(properties.getBaseUrl()).isEqualTo(URI.create("https://example.test/api"));
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.getMaxRetries()).isEqualTo(4);
                });
    }

    @Test
    void backsOffForCustomClient() {
        this.contextRunner
                .withUserConfiguration(CustomClientConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context.getBean(JevClient.class))
                            .isSameAs(context.getBean("customJevClient"));
                });
    }

    @Test
    void canBeDisabled() {
        this.contextRunner
                .withPropertyValues("jev.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(JevClient.class);
                    assertThat(context).doesNotHaveBean(JevTransport.class);
                    assertThat(context).doesNotHaveBean(ReactorJevClient.class);
                });
    }

    @Test
    void rejectsUnknownProvider() {
        this.contextRunner
                .withPropertyValues("jev.provider=unknown", "jev.api-key=test-key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsConflictingBaseUrlAndEndpoint() {
        this.contextRunner
                .withPropertyValues(
                        "jev.api-key=test-key",
                        "jev.base-url=https://base.example.test/api",
                        "jev.endpoint=https://endpoint.example.test/proxy/decisions?tenant=test")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Configure either baseUrl or endpoint, not both");
                });
    }

    @Test
    void rejectsNegativeMaxRetries() {
        this.contextRunner
                .withPropertyValues("jev.api-key=test-key", "jev.max-retries=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNonPositiveTimeout() {
        this.contextRunner
                .withPropertyValues("jev.api-key=test-key", "jev.timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsClearlyWhenWebClientTransportIsUnavailable() {
        this.contextRunner
                .withClassLoader(new FilteredClassLoader(WebClient.class))
                .withPropertyValues("jev.api-key=test-key", "jev.transport=webclient")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "jev.transport=webclient requires the jev-spring-webflux module and Spring WebFlux on the classpath");
                });
    }

    @Test
    void usesJdkTransportWithoutSpringWebFlux() {
        this.contextRunner
                .withClassLoader(new FilteredClassLoader(
                        "io.github.gudcks0305.jev.webflux",
                        "reactor",
                        "org.springframework.web.reactive"))
                .withPropertyValues("jev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(JevClient.class);
                    assertThat(context).doesNotHaveBean(JevTransport.class);
                    assertThat(context).doesNotHaveBean("reactorJevClient");
                });
    }

    private void assertProviderRequest(
            String provider, Class<? extends JevClient> clientType, boolean modelInHeader) {
        URI endpoint = URI.create("https://proxy.example.test/custom/decisions?tenant=a-b&mode=fast");
        this.contextRunner
                .withUserConfiguration(CapturingTransportConfiguration.class)
                .withSystemProperties("jev.endpoint=" + endpoint)
                .withPropertyValues(
                        "jev.provider=" + provider,
                        "jev.api-key=bound-key",
                        "jev.model=bound-model",
                        "jev.transport=webclient")
                .run(context -> {
                    assertThat(context.getBean(JevClient.class)).isInstanceOf(clientType);
                    CapturingTransport transport = context.getBean(CapturingTransport.class);
                    context.getBean(JevClient.class)
                            .evaluateAsync("state", NoulQuestion.of("decision", "Decide"));
                    assertThat(transport.uri).isEqualTo(endpoint);
                    assertThat(transport.headers)
                            .containsEntry("Authorization", "Bearer bound-key");
                    if (modelInHeader) {
                        assertThat(transport.headers)
                                .containsEntry("ai-model-id", "bound-model");
                    } else {
                        assertThat(transport.body.path("model").asText()).isEqualTo("bound-model");
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class EnableAutoConfigurationTestConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class UserWebClientConfiguration {

        @Bean
        UserWebClientProbe userWebClientProbe() {
            return new UserWebClientProbe();
        }

        @Bean
        WebClient userWebClient(UserWebClientProbe probe) {
            return WebClient.builder()
                    .filter((request, next) -> {
                        probe.filterCalls.incrementAndGet();
                        return next.exchange(ClientRequest.from(request).build());
                    })
                    .exchangeFunction(request -> {
                        probe.exchangeCalls.incrementAndGet();
                        return Mono.error(new IllegalStateException("expected test exchange failure"));
                    })
                    .build();
        }
    }

    static final class UserWebClientProbe {

        private final java.util.concurrent.atomic.AtomicInteger filterCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        private final java.util.concurrent.atomic.AtomicInteger exchangeCalls =
                new java.util.concurrent.atomic.AtomicInteger();
    }

    @Configuration(proxyBeanMethods = false)
    static class CapturingTransportConfiguration {

        @Bean
        CapturingTransport capturingTransport() {
            return new CapturingTransport();
        }
    }

    static final class CapturingTransport implements JevTransport {

        private URI uri;
        private Map<String, String> headers;
        private JsonNode body;

        @Override
        public CompletableFuture<JsonNode> post(
                URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            return new CompletableFuture<>();
        }

        @Override
        public void close() {
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomClientConfiguration {

        @Bean
        JevClient customJevClient() {
            return new StubJevClient();
        }
    }

    static final class StubJevClient implements JevClient {

        @Override
        public CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions) {
            throw new UnsupportedOperationException("not used by auto-configuration test");
        }

        @Override
        public void close() {
        }
    }
}
