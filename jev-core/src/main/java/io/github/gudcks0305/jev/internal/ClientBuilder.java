package io.github.gudcks0305.jev.internal;

import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/** Shared provider configuration. Injected transports and HTTP clients remain caller-owned. */
public abstract class ClientBuilder<B extends ClientBuilder<B>> {
    private String apiKey;
    private String model;
    private URI baseUrl;
    private URI endpoint;
    private Duration timeout = Duration.ofSeconds(30);
    private int maxRetries = 2;
    private HttpClient httpClient;
    private JevTransport transport;

    protected abstract B self();
    public B apiKey(String apiKey) { this.apiKey = Objects.requireNonNull(apiKey, "apiKey"); return self(); }
    public B model(String model) { this.model = Objects.requireNonNull(model, "model"); return self(); }
    public B baseUrl(URI baseUrl) { this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl"); return self(); }
    /** Full request URL, including path and optional query. Mutually exclusive with baseUrl. */
    public B endpoint(URI endpoint) { this.endpoint = Objects.requireNonNull(endpoint, "endpoint"); return self(); }
    /** Total HTTP deadline, including retries, for the default JDK transport. */
    public B timeout(Duration timeout) { this.timeout = Objects.requireNonNull(timeout, "timeout"); return self(); }
    /** Retry count for the default JDK transport. Injected transports own their retry policy. */
    public B maxRetries(int maxRetries) { this.maxRetries = maxRetries; return self(); }
    public B httpClient(HttpClient httpClient) { this.httpClient = Objects.requireNonNull(httpClient, "httpClient"); return self(); }
    public B transport(JevTransport transport) { this.transport = Objects.requireNonNull(transport, "transport"); return self(); }

    protected Config configure(String keyEnvironment, String defaultModel, URI defaultBaseUrl, String path) {
        String key = apiKey == null ? System.getenv(keyEnvironment) : apiKey;
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Set apiKey or " + keyEnvironment);
        if (!key.chars().allMatch(c -> c >= 33 && c <= 126)) throw new IllegalArgumentException("Invalid API key format");
        String modelId = JsonSupport.nonBlank(model == null ? defaultModel : model, "Model");
        if (modelId.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid model format");
        if (baseUrl != null && endpoint != null) throw new IllegalArgumentException("Configure either baseUrl or endpoint, not both");
        URI target;
        if (endpoint != null) {
            validateUrl(endpoint, "endpoint", true);
            target = endpoint;
        } else {
            URI base = baseUrl == null ? defaultBaseUrl : baseUrl;
            validateUrl(base, "baseUrl", false);
            target = URI.create(base.toString().replaceAll("/+$", "") + "/" + path);
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and at most 1 day");
        }
        if (maxRetries < 0 || maxRetries > 10) throw new IllegalArgumentException("maxRetries must be between 0 and 10");
        if (transport != null && httpClient != null) throw new IllegalArgumentException("Configure either transport or httpClient, not both");
        JevTransport selected = transport == null ? new HttpTransport(httpClient == null ? DefaultHttp.CLIENT : httpClient, timeout, maxRetries) : transport;
        return new Config(key, modelId, target, selected, transport == null);
    }

    private static void validateUrl(URI uri, String field, boolean allowQuery) {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || (!allowQuery && uri.getQuery() != null)) {
            throw new IllegalArgumentException(field + " must be an HTTP(S) URL without credentials or fragment"
                    + (allowQuery ? "" : " or query"));
        }
    }

    protected static final class Config {
        final String apiKey;
        final String model;
        final URI endpoint;
        final JevTransport transport;
        final boolean ownsTransport;
        Config(String apiKey, String model, URI endpoint, JevTransport transport, boolean ownsTransport) {
            this.apiKey = apiKey;
            this.model = model;
            this.endpoint = endpoint;
            this.transport = transport;
            this.ownsTransport = ownsTransport;
        }
    }
    private static final class DefaultHttp {
        static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
