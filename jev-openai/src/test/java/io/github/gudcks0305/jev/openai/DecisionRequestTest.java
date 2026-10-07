package io.github.gudcks0305.jev.openai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DecisionRequestTest {
    private static final DecisionQuestion.Predicate QUESTION = new DecisionQuestion.Predicate("");
    private static final String IMAGE = "data:image/png;base64,AQ==";

    @Test
    void emptyStringsAndRepeatedNamesRemainValidAndOnlyEmptyQuestionCollectionsFail() {
        var predicate = new DecisionQuestion.Predicate("", "");
        var choice = new DecisionQuestion.Choice("", "", List.of(new DecisionQuestion.Option("", "")));
        var score = new DecisionQuestion.Score("", "", List.of(new DecisionQuestion.Level("", "")));
        var request = new DecisionRequest(DecisionInput.text(""), List.of(predicate, choice, score), "", "");
        assertEquals("", request.model());
        assertEquals("", request.safetyIdentifier());
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Choice("", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Score("", List.of()));
        assertDoesNotThrow(() -> new DecisionRequest(DecisionInput.messages(List.of()), List.of(QUESTION)));
        assertDoesNotThrow(() -> new DecisionInput.UserMessage(List.of()));
    }

    @Test
    void choicesCompareExactTypeAndValueWhileScoreLevelsUsePosition() {
        var choices = new DecisionQuestion.Choice("", List.of(
                new DecisionQuestion.Option("true"), new DecisionQuestion.Option(true),
                new DecisionQuestion.Option("false"), new DecisionQuestion.Option(false)));
        assertEquals(4, choices.choices().size());
        assertNotEquals(DecisionValue.text("true"), DecisionValue.bool(true));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Choice("", List.of(
                new DecisionQuestion.Option("same"), new DecisionQuestion.Option("same", "different description"))));
        assertThrows(IllegalArgumentException.class, () -> new DecisionQuestion.Choice("", List.of(
                new DecisionQuestion.Option(false), new DecisionQuestion.Option(false))));
        assertDoesNotThrow(() -> new DecisionQuestion.Score("", List.of(
                new DecisionQuestion.Level("same"), new DecisionQuestion.Level("same"))));
    }

    @Test
    void safetyIdentifierLimitCountsUnicodeCodePoints() {
        String supplementary = "\uD83D\uDE00";
        var request = DecisionRequest.builder().input("").questions(QUESTION)
                .safetyIdentifier(supplementary.repeat(128)).build();
        assertEquals(256, request.safetyIdentifier().length());
        assertThrows(IllegalArgumentException.class, () -> DecisionRequest.builder().input("").questions(QUESTION)
                .safetyIdentifier(supplementary.repeat(129)).build());
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(DecisionInput.text(""),
                List.of(QUESTION), null, "a".repeat(129)));
    }

    @Test
    void enforcesImageLimitAcrossAllMessagesBeforeEncoding() {
        var first = new DecisionInput.UserMessage(Collections.nCopies(64, new DecisionInput.ImagePart(IMAGE)));
        var second = new DecisionInput.UserMessage(Collections.nCopies(64, new DecisionInput.ImagePart(IMAGE)));
        assertDoesNotThrow(() -> new DecisionRequest(DecisionInput.messages(first, second), List.of(QUESTION)));
        var last = new DecisionInput.UserMessage(List.of(new DecisionInput.ImagePart(IMAGE)));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRequest(
                DecisionInput.messages(first, second, last), List.of(QUESTION)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/image.png", "file:///image.png", "file-image", "data:audio/wav;base64,AQ==",
            "data:text/plain;base64,AQ==", "data:image/png,AQ==", "data:image/;base64,AQ==",
            "data:image/png;base64,", "data:image/png;base64,A", "data:image/png;base64,A===",
            "data:image/png;base64,=AAA", "data:image/png;base64,AQ=", "data:image/png;base64,AQ$=",
            "data:image/png;base64,AQ==\n", "data:image/png;base64,AQ%3D%3D",
            "data:image/png;base64,AB==", "data:image/png;base64,AAB="
    })
    void rejectsInvalidOrNonInlineImagesWithoutLeakingInput(String image) {
        var error = assertThrows(IllegalArgumentException.class, () -> new DecisionInput.ImagePart(image));
        assertFalse(error.getMessage().contains(image));
    }

    @ParameterizedTest
    @ValueSource(strings = {"data:image/png;base64,AQ==", "data:image/jpeg;base64,AQI=",
            "data:image/webp;base64,AQID", "data:image/gif;base64,AQ", "data:image/png;base64,AQI"})
    void acceptsPaddedAndUnpaddedBase64WithoutDecoding(String image) {
        assertEquals(image, new DecisionInput.ImagePart(image).imageUrl());
    }

    @Test
    void snapshotsAllNestedCollectionsAndBuilderValues() {
        var parts = new ArrayList<DecisionInput.Part>(List.of(new DecisionInput.TextPart("secret")));
        var message = new DecisionInput.UserMessage(parts);
        var messages = new ArrayList<>(List.of(message));
        var input = DecisionInput.messages(messages);
        var options = new ArrayList<>(List.of(new DecisionQuestion.Option(true)));
        var choice = new DecisionQuestion.Choice("", options);
        var levels = new ArrayList<>(List.of(new DecisionQuestion.Level("secret")));
        var score = new DecisionQuestion.Score("", levels);
        var questions = new ArrayList<DecisionQuestion>(List.of(choice, score));
        var builder = DecisionRequest.builder().input(input).questions(questions);
        parts.clear();
        messages.clear();
        options.clear();
        levels.clear();
        questions.clear();
        var request = builder.build();
        builder.input("changed").questions(QUESTION);
        assertSame(input, request.input());
        assertEquals(2, request.questions().size());
        assertEquals(1, input.messages().size());
        assertEquals(1, ((DecisionInput.ContentParts) message.content()).parts().size());
        assertEquals(1, choice.choices().size());
        assertEquals(1, score.levels().size());
        assertThrows(UnsupportedOperationException.class, () -> request.questions().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.messages().clear());
        assertThrows(UnsupportedOperationException.class, () -> ((DecisionInput.ContentParts) message.content()).parts().clear());
        assertThrows(UnsupportedOperationException.class, () -> choice.choices().clear());
        assertThrows(UnsupportedOperationException.class, () -> score.levels().clear());
    }

    @Test
    void debugStringsHideTextImagesQuestionsAndSafetyIdentifier() {
        String secret = "sensitive-content";
        var text = new DecisionInput.TextPart(secret);
        var image = new DecisionInput.ImagePart(IMAGE);
        var message = new DecisionInput.UserMessage(List.of(text, image));
        var input = DecisionInput.messages(message);
        var predicate = new DecisionQuestion.Predicate(secret, secret);
        var choice = new DecisionQuestion.Choice(secret, secret, List.of(new DecisionQuestion.Option(secret, secret)));
        var score = new DecisionQuestion.Score(secret, secret, List.of(new DecisionQuestion.Level(secret, secret)));
        var request = new DecisionRequest(input, List.of(predicate, choice, score), secret, secret);
        for (Object object : List.of(DecisionInput.text(secret), text, image, message, input, predicate,
                choice, choice.choices().get(0), score, score.levels().get(0), request, DecisionValue.text(secret))) {
            assertFalse(object.toString().contains(secret));
            assertFalse(object.toString().contains(IMAGE));
        }
    }
}
