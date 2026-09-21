package io.github.gudcks0305.jev.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.internal.EvaluationCodec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class JevSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    enum Team {
        @JevLabel("Payments and refunds") BILLING { @Override public String toString() { return "not-a-wire-label"; } },
        TECHNICAL
    }
    record Risk(@JevBoolean(value = "Does the request require review?", threshold = .75) boolean review) {}
    record Ticket(
            @JevBoolean(value = "Does this need an urgent reply?", threshold = .8) boolean urgent,
            @JevChoice("Which team should handle the request?") Team department,
            @JevProbability("Is the request fraudulent?") Double fraud,
            @JevScore(value = "How severe is the problem?", levels = {"Low", "Medium", "High"}) double severity,
            Risk risk,
            @JevLabels(value = "Does the request concern this team's area?", threshold = .9) Set<Team> tags,
            @JevChoice(value = "Choose an escalation team, if any", noneDescription = "No escalation is needed") Optional<Team> escalation) {}
    record Flag(@JevBoolean(value = "Is the condition true?", threshold = .8) Boolean value) {}
    record Reused(Risk left, Risk right) {}
    record Labels(@JevLabels(value = "Does this label apply?", threshold = .5) List<Team> labels) {}
    record OptionalChoice(@JevChoice("Choose a team or no match") Optional<Team> team) {}
    record Rejecting(@JevBoolean(value = "True?", threshold = .5) boolean value) {
        Rejecting { if (value) throw new IllegalArgumentException("private-constructor-detail"); }
    }

    @Test void mapsEverySupportedShapeInOneBatchAndPreservesFullEvaluation() throws Exception {
        FakeClient client = new FakeClient(ticketResponse());
        JevSchema<Ticket> schema = JevSchema.of(Ticket.class);
        TypedEvaluation<Ticket> result = client.evaluate(Map.of("ticket", "duplicate charge"), schema);
        assertEquals(1, client.calls.get());
        assertEquals(8, schema.questions().size());
        assertEquals(List.of("urgent", "department", "fraud", "severity", "risk.review", "tags.BILLING", "tags.TECHNICAL", "escalation"),
                schema.questions().stream().map(Question::id).toList());
        Ticket ticket = result.value();
        assertTrue(ticket.urgent()); // equality at the explicit threshold is accepted
        assertEquals(Team.BILLING, ticket.department());
        assertEquals(.65, ticket.fraud());
        assertEquals(1.4, ticket.severity()); // no rounding to an integer rubric level
        assertFalse(ticket.risk().review());
        assertEquals(Set.of(Team.BILLING), ticket.tags());
        assertTrue(ticket.escalation().isEmpty());
        assertSame(client.latestEvaluation, result.evaluation());
        assertEquals(.74, ((NoulAnswer) result.evaluation().answers().get("risk.review")).probability());
        assertEquals(.7, ((ChoiceAnswer<?>) result.evaluation().answers().get("department")).confidence().orElseThrow());
        assertEquals(99, result.evaluation().usage().inputTokens().orElseThrow());
        assertEquals("kept", result.evaluation().rawResponse().path("provider_extension").asText());
        assertEquals("Payments and refunds", client.request.at("/questions/department/criteria/BILLING").asText());
        assertEquals("BILLING", client.request.at("/questions/tags.BILLING/instructions/label").asText());
        assertEquals("No escalation is needed", client.request.at("/questions/escalation/criteria/<none>").asText());
        assertThrows(UnsupportedOperationException.class, () -> ticket.tags().add(Team.TECHNICAL));
        assertThrows(UnsupportedOperationException.class, () -> schema.questions().clear());
    }

    @Test void optionalEnumSelectedAndNoMatchRemainDistinct() throws Exception {
        for (String label : List.of("BILLING", "<none>")) {
            var body = JSON.readTree("""
                    {"answers":{"team":{"type":"choice","choice":"BILLING","probabilities":{"BILLING":0.8,"TECHNICAL":0.1,"<none>":0.1}}}}
                    """);
            ((ObjectNode) body.at("/answers/team")).put("choice", label);
            Optional<Team> result = new FakeClient(body).evaluate("state", OptionalChoice.class).value().team();
            assertEquals(label.equals("<none>") ? Optional.empty() : Optional.of(Team.BILLING), result);
        }
    }

    @Test void reusedNestedRecordTypesHaveIndependentPaths() {
        var result = new FakeClient(nouls(Map.of("left.review", .9, "right.review", .2)))
                .evaluate("state", Reused.class);
        assertTrue(result.value().left().review());
        assertFalse(result.value().right().review());
    }

    @Test void multilabelUsesIndependentProbabilitiesAndStableImmutableOrder() {
        var result = new FakeClient(nouls(Map.of("labels.BILLING", .5, "labels.TECHNICAL", .99)))
                .evaluate("state", Labels.class);
        assertEquals(List.of(Team.BILLING, Team.TECHNICAL), result.value().labels());
        assertThrows(UnsupportedOperationException.class, () -> result.value().labels().clear());
        var empty = new FakeClient(nouls(Map.of("labels.BILLING", .1, "labels.TECHNICAL", .1)))
                .evaluate("state", Labels.class);
        assertTrue(empty.value().labels().isEmpty());
    }

    @Test void schemaCanBeReusedConcurrentlyWithoutMixingResults() {
        JevSchema<Flag> schema = JevSchema.of(Flag.class);
        AtomicInteger successes = new AtomicInteger();
        IntStream.range(0, 32).parallel().forEach(index -> {
            boolean expected = index % 2 == 0;
            var value = new FakeClient(nouls(Map.of("value", expected ? .8 : .79)))
                    .evaluate("state", schema).value().value();
            if (value == expected) successes.incrementAndGet();
        });
        assertEquals(32, successes.get());
    }

    @Test void sameIdsFromUnrelatedQuestionsCannotBeDecoded() {
        var schema = JevSchema.of(Flag.class);
        var client = new FakeClient(nouls(Map.of("value", .8)));
        Evaluation unrelated = client.evaluate("state", NoulQuestion.of("value", "Different meaning"));
        assertThrows(IllegalArgumentException.class, () -> schema.decode(unrelated));
    }

    @Test void recordConstructorRejectionIsExplicitAndNotRetried() {
        FakeClient client = new FakeClient(nouls(Map.of("value", .9)));
        JevException error = assertThrows(JevException.class, () -> client.evaluate("state", Rejecting.class));
        assertEquals(JevException.Kind.PROTOCOL, error.kind());
        assertFalse(error.getMessage().contains("private-constructor-detail"));
        assertEquals(1, client.calls.get());
    }

    @Test void asyncCancellationAndFailurePropagateBothWays() throws Exception {
        FakeClient client = new FakeClient(null);
        var typed = client.evaluateAsync("state", Flag.class);
        assertTrue(typed.cancel(true));
        assertTrue(client.pending.isCancelled());
        var next = client.evaluateAsync("state", JevSchema.of(Flag.class));
        client.pending.cancel(true);
        assertTrue(next.isCancelled());
        var failed = client.evaluateAsync("state", Flag.class);
        var cause = new JevException(JevException.Kind.CONNECTION, "failure");
        client.pending.completeExceptionally(cause);
        assertSame(cause, assertThrows(CompletionException.class, failed::join).getCause());
        var mapped = client.evaluateAsync("state", Flag.class);
        client.complete(nouls(Map.of("value", .8)));
        assertTrue(mapped.get(1, TimeUnit.SECONDS).value().value());
        var bad = client.evaluateAsync("state", Rejecting.class);
        client.complete(nouls(Map.of("value", .8)));
        assertInstanceOf(JevException.class, assertThrows(CompletionException.class, bad::join).getCause());
    }

    record Missing(boolean value) {}
    record Ambiguous(@JevBoolean(value = "True?", threshold = .5) @JevProbability("True?") double value) {}
    record BadThreshold(@JevBoolean(value = "True?", threshold = 1.1) boolean value) {}
    record NanThreshold(@JevLabels(value = "Applies?", threshold = Double.NaN) List<Team> labels) {}
    record WrongNumber(@JevScore(value = "Rate", levels = {"Low", "High"}) int value) {}
    record Text(@JevChoice("Choose") String value) {}
    record Blank(@JevProbability(" ") double value) {}
    record Generic<T>(@JevProbability("True?") double value) {}
    record Loop(Loop value) {}
    record Empty() {}
    record OptionalNested(@JevChoice("Choose") Optional<Risk> value) {}
    @SuppressWarnings("rawtypes") record Raw(@JevLabels(value = "Applies?", threshold = .5) List value) {}
    record Wildcard(@JevLabels(value = "Applies?", threshold = .5) Set<? extends Team> value) {}
    record AnnotatedNested(@JevProbability("True?") Risk value) {}
    record ShortRubric(@JevScore(value = "Rate", levels = {"Only"}) double value) {}
    record LongRubric(@JevScore(value = "Rate", levels = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"}) double value) {}
    record BlankRubric(@JevScore(value = "Rate", levels = {"Low", " "}) double value) {}

    static Stream<Class<?>> invalidRecords() {
        return Stream.of(String.class, Missing.class, Ambiguous.class, BadThreshold.class, NanThreshold.class,
                WrongNumber.class, Text.class, Blank.class, Generic.class, Loop.class, Empty.class,
                OptionalNested.class, Raw.class, Wildcard.class, AnnotatedNested.class, ShortRubric.class,
                LongRubric.class, BlankRubric.class);
    }

    @ParameterizedTest @MethodSource("invalidRecords")
    void rejectsInvalidSchemasBeforeAnyClientRequest(Class<?> type) {
        FakeClient client = new FakeClient(null);
        assertThrows(IllegalArgumentException.class, () -> client.evaluate("state", type));
        assertThrows(IllegalArgumentException.class, () -> client.evaluateAsync("state", type));
        assertEquals(0, client.calls.get());
    }

    private static JsonNode ticketResponse() throws Exception {
        return JSON.readTree("""
                {"model":"jev-test","provider_extension":"kept","usage":{"input_tokens":99,"output_tokens":12},"answers":{
                  "urgent":{"type":"noul","noul":0.8},
                  "department":{"type":"choice","choice":"BILLING","probabilities":{"BILLING":0.8,"TECHNICAL":0.2},"confidence":0.7},
                  "fraud":{"type":"noul","noul":0.65},
                  "severity":{"type":"score","score":1.4,"probabilities":{"0":0.1,"1":0.4,"2":0.5}},
                  "risk.review":{"type":"noul","noul":0.74},
                  "tags.BILLING":{"type":"noul","noul":0.9},
                  "tags.TECHNICAL":{"type":"noul","noul":0.1},
                  "escalation":{"type":"choice","choice":"<none>","probabilities":{"BILLING":0.1,"TECHNICAL":0.1,"<none>":0.8}}
                }}
                """);
    }

    private static ObjectNode nouls(Map<String, Double> probabilities) {
        ObjectNode response = JSON.createObjectNode();
        ObjectNode answers = response.putObject("answers");
        probabilities.forEach((id, value) -> answers.putObject(id).put("type", "noul").put("noul", value));
        return response;
    }

    private static final class FakeClient implements JevClient {
        final AtomicInteger calls = new AtomicInteger();
        final JsonNode response;
        ObjectNode request;
        Evaluation latestEvaluation;
        CompletableFuture<Evaluation> pending;
        Map<String, Question<?>> indexed;
        FakeClient(JsonNode response) { this.response = response; }
        @Override public CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions) {
            calls.incrementAndGet();
            indexed = new LinkedHashMap<>();
            Arrays.stream(questions).forEach(q -> indexed.put(q.id(), q));
            request = EvaluationCodec.request(state, indexed, "test", false);
            pending = new CompletableFuture<>();
            if (response != null) complete(response);
            return pending;
        }
        void complete(JsonNode body) {
            latestEvaluation = EvaluationCodec.response(body, indexed, "test", false);
            pending.complete(latestEvaluation);
        }
        @Override public void close() { if (pending != null) pending.cancel(true); }
    }
}
