package io.github.gudcks0305.jev.autoconfigure;

import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.cloudflare.CloudflareJevClient;
import io.github.gudcks0305.jev.openrouter.OpenRouterJevClient;
import io.github.gudcks0305.jev.spi.JevTransport;
import io.github.gudcks0305.jev.typesafe.TypeSafeJevClient;
import io.github.gudcks0305.jev.vercel.VercelJevClient;
import io.github.gudcks0305.jev.webflux.ReactorJevClient;
import io.github.gudcks0305.jev.webflux.WebClientJevTransport;
import reactor.core.publisher.Mono;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@ConditionalOnClass(JevClient.class)
@ConditionalOnProperty(prefix = "jev", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(JevProperties.class)
@Import({JevAutoConfiguration.WebClientTransportConfiguration.class,
        JevAutoConfiguration.ReactorClientConfiguration.class})
public class JevAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(JevClient.class)
    JevClient jevClient(JevProperties properties, ObjectProvider<JevTransport> transports) {
        JevTransport transport = resolveTransport(properties, transports);
        return switch (properties.getProvider()) {
            case TYPESAFE -> typeSafeClient(properties, transport);
            case VERCEL -> vercelClient(properties, transport);
            case OPENROUTER -> openRouterClient(properties, transport);
            case CLOUDFLARE -> cloudflareClient(properties, transport);
        };
    }

    private static JevTransport resolveTransport(
            JevProperties properties, ObjectProvider<JevTransport> transports) {
        if (properties.getTransport() == JevProperties.Transport.JDK) {
            return null;
        }
        JevTransport transport = transports.getIfAvailable();
        if (transport == null) {
            throw new IllegalStateException(
                    "jev.transport=webclient requires the jev-spring-webflux module and Spring WebFlux on the classpath");
        }
        return transport;
    }

    private static JevClient typeSafeClient(JevProperties properties, JevTransport transport) {
        var builder = TypeSafeJevClient.builder()
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries());
        if (transport != null) {
            builder.transport(transport);
        }
        if (hasText(properties.getApiKey())) {
            builder.apiKey(properties.getApiKey());
        }
        if (hasText(properties.getModel())) {
            builder.model(properties.getModel());
        }
        if (properties.getBaseUrl() != null) {
            builder.baseUrl(properties.getBaseUrl());
        }
        if (properties.getEndpoint() != null) {
            builder.endpoint(properties.getEndpoint());
        }
        return builder.build();
    }

    private static JevClient vercelClient(JevProperties properties, JevTransport transport) {
        var builder = VercelJevClient.builder()
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries());
        if (transport != null) {
            builder.transport(transport);
        }
        if (hasText(properties.getApiKey())) {
            builder.apiKey(properties.getApiKey());
        }
        if (hasText(properties.getModel())) {
            builder.model(properties.getModel());
        }
        if (properties.getBaseUrl() != null) {
            builder.baseUrl(properties.getBaseUrl());
        }
        if (properties.getEndpoint() != null) {
            builder.endpoint(properties.getEndpoint());
        }
        return builder.build();
    }

    private static JevClient openRouterClient(JevProperties properties, JevTransport transport) {
        var builder = OpenRouterJevClient.builder()
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries());
        if (transport != null) {
            builder.transport(transport);
        }
        if (hasText(properties.getApiKey())) {
            builder.apiKey(properties.getApiKey());
        }
        if (hasText(properties.getModel())) {
            builder.model(properties.getModel());
        }
        if (properties.getBaseUrl() != null) {
            builder.baseUrl(properties.getBaseUrl());
        }
        if (properties.getEndpoint() != null) {
            builder.endpoint(properties.getEndpoint());
        }
        return builder.build();
    }

    private static JevClient cloudflareClient(JevProperties properties, JevTransport transport) {
        var builder = CloudflareJevClient.builder()
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries());
        if (transport != null) {
            builder.transport(transport);
        }
        if (hasText(properties.getApiKey())) {
            builder.apiKey(properties.getApiKey());
        }
        if (hasText(properties.getModel())) {
            builder.model(properties.getModel());
        }
        if (hasText(properties.getAccountId())) {
            builder.accountId(properties.getAccountId());
        }
        if (properties.getBaseUrl() != null) {
            builder.baseUrl(properties.getBaseUrl());
        }
        if (properties.getEndpoint() != null) {
            builder.endpoint(properties.getEndpoint());
        }
        return builder.build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({WebClient.class, WebClientJevTransport.class})
    @ConditionalOnProperty(prefix = "jev", name = "transport", havingValue = "webclient")
    static class WebClientTransportConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(JevTransport.class)
        JevTransport jevTransport(
                ObjectProvider<WebClient> webClients,
                ObjectProvider<WebClient.Builder> webClientBuilders,
                JevProperties properties) {
            WebClient webClient = webClients.getIfUnique();
            if (webClient == null) {
                WebClient.Builder builder = webClientBuilders.getIfUnique();
                webClient = builder != null ? builder.build() : WebClient.builder().build();
            }
            return new WebClientJevTransport(
                    webClient, properties.getTimeout(), properties.getMaxRetries());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({Mono.class, ReactorJevClient.class})
    static class ReactorClientConfiguration {

        @Bean
        @ConditionalOnMissingBean(ReactorJevClient.class)
        ReactorJevClient reactorJevClient(JevClient client) {
            return new ReactorJevClient(client);
        }
    }
}
