package io.github.gudcks0305.jev.examples;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.gudcks0305.jev.Answer;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevClient;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.NoulAnswer;
import io.github.gudcks0305.jev.NoulQuestion;
import io.github.gudcks0305.jev.Question;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LabeledEvaluationExampleTest {
    @Test void thresholdAndTieChangeAcceptedSet() {
        var predictions = List.of(
                new LabeledEvaluationExample.Prediction(true, 0.5, null),
                new LabeledEvaluationExample.Prediction(false, 0.5, null),
                new LabeledEvaluationExample.Prediction(true, 0.8, null),
                new LabeledEvaluationExample.Prediction(false, 0.2, null));
        var low = LabeledEvaluationExample.metrics(predictions, 0.5);
        assertEquals(4, low.accepted());
        assertEquals(3, low.correct());
        assertEquals(0.75, low.acceptedAccuracy());
        var high = LabeledEvaluationExample.metrics(predictions, 0.7);
        assertEquals(2, high.accepted());
        assertEquals(2, high.abstentions());
        assertEquals(1.0, high.acceptedAccuracy());
        assertEquals(0.5, high.coverage());
        assertThrows(IllegalArgumentException.class, () -> LabeledEvaluationExample.metrics(predictions, 0.49));
        var bounds = LabeledEvaluationExample.metrics(List.of(
                new LabeledEvaluationExample.Prediction(false, 0.1, null),
                new LabeledEvaluationExample.Prediction(true, 0.9, null)), 0.9);
        assertEquals(2, bounds.accepted());
        assertEquals(2, bounds.correct());
    }

    @Test void failuresLowerCoverageAndZeroAcceptedIsNotAccuracy() {
        var predictions = List.of(
                new LabeledEvaluationExample.Prediction(true, 0.6, null),
                new LabeledEvaluationExample.Prediction(false, null, "TIMEOUT"));
        var m = LabeledEvaluationExample.metrics(predictions, 0.9);
        assertEquals(2, m.total());
        assertEquals(1, m.successful());
        assertEquals(1, m.errors());
        assertEquals(1, m.abstentions());
        assertEquals(0, m.accepted());
        assertEquals(0, m.coverage());
        assertNull(m.acceptedAccuracy());
    }

    @Test void datasetRejectsMalformedAndOutOfRangeValues() {
        for (String json : List.of("{}", "{bad}",
                "{\"state\":\"x\",\"label\":true,\"offline_probability\":1.01}",
                "{\"state\":\"x\",\"label\":true,\"offline_probability\":\"NaN\"}",
                "{\"state\":\"x\",\"label\":true,\"offline_probability\":1e9999}",
                "{\"state\":\"x\",\"label\":true,\"label\":false,\"offline_probability\":0.5}",
                "{\"state\":\"x\",\"label\":true,\"offline_probability\":0.5} false")) {
            assertThrows(IllegalArgumentException.class,
                    () -> LabeledEvaluationExample.readRows(new BufferedReader(new StringReader(json)), false));
        }
        assertThrows(IllegalArgumentException.class, () -> LabeledEvaluationExample.readRows(
                new BufferedReader(new StringReader("{\"state\":\"x\",\"label\":true}")), false));
        assertDoesNotThrow(() -> LabeledEvaluationExample.readRows(
                new BufferedReader(new StringReader("{\"state\":\"x\",\"label\":true}")), true));
    }

    @Test void liveSendsOnlyStateAndCountsBoundedFailureKind() {
        List<Object> states = new ArrayList<>();
        JevClient fake = new JevClient() {
            @Override public CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions) {
                states.add(state);
                if (states.size() == 2) return CompletableFuture.failedFuture(
                        new JevException(JevException.Kind.TIMEOUT, "sensitive response"));
                var question = (NoulQuestion) questions[0];
                return CompletableFuture.completedFuture(new Evaluation("test", null,
                        Map.of(question.id(), question), Map.<String, Answer>of(question.id(), new NoulAnswer(0.9)),
                        JsonNodeFactory.instance.objectNode()));
            }
            @Override public void close() {}
        };
        var rows = List.of(new LabeledEvaluationExample.Row("refund request", true, null),
                new LabeledEvaluationExample.Row("login request", false, null));
        var predictions = LabeledEvaluationExample.predictLive(rows, fake);
        assertEquals(List.of("refund request", "login request"), states);
        assertEquals("TIMEOUT", predictions.get(1).errorKind());
        assertEquals(1, LabeledEvaluationExample.metrics(predictions, 0.7).errors());
    }
}
