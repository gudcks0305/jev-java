package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.observation.EvaluationEvent;
import io.github.gudcks0305.jev.observation.EvaluationObserver;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class AbstractJevClient implements JevClient {
    private final JevTransport transport;
    private final boolean ownsTransport;
    private final URI endpoint;
    private final String model;
    private final Map<String, String> headers;
    private final WireFormat format;
    private final EvaluationObserver observer;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> calls = ConcurrentHashMap.newKeySet();

    protected AbstractJevClient(ClientBuilder.Config config, boolean gateway) {
        this(config, gateway ? WireFormat.VERCEL : WireFormat.TYPESAFE);
    }

    protected AbstractJevClient(ClientBuilder.Config config, WireFormat format) {
        this.transport = config.transport;
        this.ownsTransport = config.ownsTransport;
        this.endpoint = config.endpoint;
        this.model = config.model;
        this.observer = config.observer;
        this.format = Objects.requireNonNull(format, "format");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Authorization", "Bearer " + config.apiKey);
        values.put("Content-Type", "application/json");
        values.put("Accept", "application/json");
        values.put("User-Agent", "jev-java/0.3.0");
        if (format == WireFormat.VERCEL) {
            values.put("ai-gateway-protocol-version", "0.0.1");
            values.put("ai-gateway-auth-method", "api-key");
            values.put("ai-evaluation-model-specification-version", "4");
            values.put("ai-model-id", model);
        }
        this.headers = Map.copyOf(values);
    }

    @Override public CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions) {
        if (closed.get()) return CompletableFuture.failedFuture(closedError());
        Objects.requireNonNull(questions, "Questions are required");
        if (questions.length == 0) throw new IllegalArgumentException("At least one question is required");
        Map<String, Question<?>> indexed = new LinkedHashMap<>();
        for (Question<?> question : questions) {
            Objects.requireNonNull(question, "Question cannot be null");
            if (indexed.putIfAbsent(question.id(), question) != null) throw new IllegalArgumentException("Duplicate question id");
        }
        JsonNode body = EvaluationCodec.request(state, indexed, model, format);
        long startedNanos = System.nanoTime();
        AtomicReference<String> returnedModel = observer == null ? null : new AtomicReference<>();
        return executeAsync(body, response -> {
            Evaluation evaluation = EvaluationCodec.response(response, indexed, model, format);
            if (returnedModel != null) returnedModel.set(providerModel(response));
            return evaluation;
        }, (evaluation, failure) -> notifyObserver(startedNanos, indexed.size(),
                returnedModel == null ? null : returnedModel.get(), evaluation, failure));
    }

    /** Configured model for provider-specific request encoders. */
    protected final String configuredModel() { return model; }

    /** Shared lifecycle for typed provider requests; cancellation reaches the transport. */
    protected final <T> CompletableFuture<T> executeAsync(
            Supplier<? extends JsonNode> encoder, Function<JsonNode, T> decoder) {
        if (closed.get()) return CompletableFuture.failedFuture(closedError());
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return executeAsync(encoder.get(), decoder, null);
    }

    private <T> CompletableFuture<T> executeAsync(JsonNode body, Function<JsonNode, T> decoder,
                                                 BiConsumer<T, Throwable> onComplete) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<JsonNode>> source = new AtomicReference<>();
        calls.add(result);
        result.whenComplete((value, failure) -> {
            calls.remove(result);
            CompletableFuture<JsonNode> pending = source.get();
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (onComplete != null) onComplete.accept(value, failure);
        });
        if (closed.get()) result.completeExceptionally(closedError());
        if (result.isDone()) return result;
        try {
            CompletableFuture<JsonNode> pending = transport.post(endpoint, headers, body);
            source.set(pending);
            if (result.isDone()) pending.cancel(true);
            pending.whenComplete((response, failure) -> {
                if (result.isDone()) return;
                if (failure != null) result.completeExceptionally(failure);
                else {
                    try { result.complete(decoder.apply(response)); }
                    catch (JevException ex) { result.completeExceptionally(ex); }
                    catch (RuntimeException ex) { result.completeExceptionally(new JevException(JevException.Kind.PROTOCOL, "Invalid evaluation response")); }
                }
            });
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        calls.forEach(call -> call.completeExceptionally(closedError()));
        if (ownsTransport) transport.close();
    }

    private static JevException closedError() { return new JevException(JevException.Kind.CLOSED, "Jev client is closed"); }

    private String providerModel(JsonNode response) {
        JsonNode payload = format == WireFormat.CLOUDFLARE && response.has("result")
                ? response.path("result") : response;
        JsonNode value = payload.path("model");
        return value.isTextual() ? value.textValue() : null;
    }

    private void notifyObserver(long startedNanos, int questionCount, String returnedModel,
                                Evaluation evaluation, Throwable failure) {
        if (observer == null) return;
        EvaluationEvent.Outcome outcome;
        Optional<Usage> usage = Optional.empty();
        Optional<String> providerModel = Optional.empty();
        Optional<JevException.Kind> errorKind = Optional.empty();
        OptionalInt statusCode = OptionalInt.empty();
        if (failure == null) {
            outcome = EvaluationEvent.Outcome.SUCCESS;
            usage = Optional.of(evaluation.usage());
            providerModel = Optional.ofNullable(returnedModel);
        } else {
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            outcome = cause instanceof CancellationException
                    ? EvaluationEvent.Outcome.CANCELLED : EvaluationEvent.Outcome.FAILURE;
            if (cause instanceof JevException jev) {
                errorKind = Optional.of(jev.kind());
                if (jev.statusCode() > 0) statusCode = OptionalInt.of(jev.statusCode());
            }
        }
        EvaluationEvent event = new EvaluationEvent(Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)),
                model, providerModel, questionCount, outcome, usage, errorKind, statusCode);
        try { observer.onEvaluation(event); }
        catch (Throwable ignored) { /* User callbacks cannot change inference or cancellation. */ }
    }
}
