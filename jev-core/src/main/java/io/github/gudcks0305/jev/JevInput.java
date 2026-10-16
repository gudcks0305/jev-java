package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.JsonSupport;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable state plus explicitly attached experimental TypeSafe images. Pass this
 * as the state argument of evaluate/evaluateAsync, including record and Reactor APIs.
 * Every image must be referenced in a string value in state, instructions, or criteria.
 * Using this input opts that request into TypeSafe's experimental images header.
 */
public final class JevInput {
    private final JsonNode state;
    private final List<JevImage> images;

    private JevInput(Object state, JevImage[] images) {
        this.state = JsonSupport.materialize(JsonSupport.content(state, "State"));
        Objects.requireNonNull(images, "Images");
        if (images.length == 0) throw new IllegalArgumentException("At least one image is required");
        Map<String, JevImage> unique = new LinkedHashMap<>();
        Arrays.stream(images).forEach(image -> {
            Objects.requireNonNull(image, "Image");
            unique.putIfAbsent(image.reference(), image);
        });
        // Generated references are 36 ASCII bytes, subject to the API's total key limit.
        if ((long) unique.size() * 36 > 65_536) {
            throw new IllegalArgumentException("Total image reference size exceeds 65536 bytes");
        }
        this.images = List.copyOf(unique.values());
    }

    public static JevInput withImages(Object state, JevImage... images) { return new JevInput(state, images); }
    public JsonNode state() { return state.deepCopy(); }
    public List<JevImage> images() { return images; }

    @Override public String toString() { return "JevInput[state=<redacted>, images=" + images.size() + "]"; }
}
