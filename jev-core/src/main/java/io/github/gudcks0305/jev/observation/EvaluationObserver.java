package io.github.gudcks0305.jev.observation;

/**
 * Receives one completion event for each accepted logical evaluation.
 *
 * <p>Callbacks may run on a transport or caller thread, concurrently for separate requests.
 * Implementations should return quickly and avoid blocking. Callback failures are ignored and
 * never change inference results or cancellation.</p>
 */
@FunctionalInterface
public interface EvaluationObserver {
    void onEvaluation(EvaluationEvent event);
}
