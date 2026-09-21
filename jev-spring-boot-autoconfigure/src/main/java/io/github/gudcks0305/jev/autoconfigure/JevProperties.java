package io.github.gudcks0305.jev.autoconfigure;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jev")
public class JevProperties {

    /** Whether Jev auto-configuration creates a default client. */
    private boolean enabled = true;

    /** Provider to use. Defaults to TypeSafe. */
    private Provider provider = Provider.TYPESAFE;

    /** HTTP transport to use. Defaults to the JDK transport. */
    private Transport transport = Transport.JDK;

    /**
     * Provider API key. When omitted, the provider builder reads {@code TYPESAFE_API_KEY}
     * for TypeSafe, {@code AI_GATEWAY_API_KEY} for Vercel, or
     * {@code OPENROUTER_API_KEY} for OpenRouter, or {@code CLOUDFLARE_API_TOKEN}
     * for Cloudflare.
     */
    private String apiKey;

    /** Provider model identifier. When omitted, the selected provider's default is used. */
    private String model;

    /** Cloudflare account ID. When omitted, Cloudflare reads {@code CLOUDFLARE_ACCOUNT_ID}. */
    private String accountId;

    /** Provider base URL. When omitted, the selected provider's default is used. */
    private URI baseUrl;

    /**
     * Full provider endpoint URL. Preserves its path and query exactly and is mutually
     * exclusive with {@code base-url}.
     */
    private URI endpoint;

    /** Total call deadline including retries. Must be positive and at most one day. */
    private Duration timeout = Duration.ofSeconds(30);

    /** Maximum retry count. Must be between zero and ten. */
    private int maxRetries = 2;

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Provider getProvider() {
        return this.provider;
    }

    public void setProvider(Provider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("jev.provider must not be null");
        }
        this.provider = provider;
    }

    public Transport getTransport() {
        return this.transport;
    }

    public void setTransport(Transport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("jev.transport must not be null");
        }
        this.transport = transport;
    }

    public String getApiKey() {
        return this.apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return this.model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getAccountId() {
        return this.accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public URI getBaseUrl() {
        return this.baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public URI getEndpoint() {
        return this.endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = endpoint;
    }

    public Duration getTimeout() {
        return this.timeout;
    }

    public void setTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("jev.timeout must be positive");
        }
        this.timeout = timeout;
    }

    public int getMaxRetries() {
        return this.maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("jev.max-retries must be nonnegative");
        }
        this.maxRetries = maxRetries;
    }

    public enum Provider {
        TYPESAFE,
        VERCEL,
        OPENROUTER,
        CLOUDFLARE
    }

    public enum Transport {
        JDK,
        WEBCLIENT
    }
}
