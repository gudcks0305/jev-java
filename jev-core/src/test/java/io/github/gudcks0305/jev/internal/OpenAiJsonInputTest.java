package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiJsonInputTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    record Evidence(String ticket, List<Integer> values) {}

    @Test void snapshotsRecordsObjectsAndArraysWithoutInterpretingTheirFields() {
        var values = new ArrayList<>(List.of(1, 2));
        JsonNode record = OpenAiJsonInput.snapshot(new Evidence("hello", values));
        values.add(3);
        assertEquals(2, record.path("values").size());
        assertEquals("hello", record.path("ticket").textValue());

        var source = JsonSupport.object().put("role", "system").put("type", "input_image")
                .put("image_url", "https://example.invalid/private-image");
        source.putArray("nested").addNull().add(true).add(1).add("text").addArray();
        JsonNode snapshot = OpenAiJsonInput.snapshot(source);
        source.put("role", "changed");
        assertEquals("system", snapshot.path("role").textValue());
        assertEquals("input_image", snapshot.path("type").textValue());
        assertTrue(snapshot.path("nested").get(0).isNull());
        assertEquals(0, OpenAiJsonInput.snapshot(List.of()).size());
        assertTrue(OpenAiJsonInput.snapshot(List.of(Map.of("a", 1))).get(0).isObject());
    }

    @Test void materializesEmbeddedPojoAndRawJsonWithoutLosingNumericPrecision() {
        var source = JsonSupport.object();
        source.putPOJO("pojo", new Evidence("hello", List.of(1)));
        source.putRawValue("raw", new RawValue("{\"fraction\":0.12345678901234567890123456789}"));
        BigDecimal decimal = new BigDecimal("0.12345678901234567890123456789");
        BigInteger integer = new BigInteger("123456789012345678901234567890");
        source.put("decimal", decimal);
        source.put("integer", integer);
        source.put("largeDecimal", new BigDecimal("1e1000"));
        JsonNode snapshot = OpenAiJsonInput.snapshot(source);
        assertFalse(snapshot.path("pojo").isPojo());
        assertEquals("hello", snapshot.at("/pojo/ticket").textValue());
        assertEquals(0, decimal.compareTo(snapshot.at("/raw/fraction").decimalValue()));
        assertEquals(0, decimal.compareTo(snapshot.path("decimal").decimalValue()));
        assertEquals(integer, snapshot.path("integer").bigIntegerValue());
        assertEquals(0, new BigDecimal("1e1000").compareTo(snapshot.path("largeDecimal").decimalValue()));
    }

    @Test void snapshotsNestedByteArraysAsOrdinaryBase64JsonStrings() {
        byte[] mapBytes = {1, 2, 3};
        byte[] nodeBytes = {1, 2, 3};
        var source = JsonSupport.object().put("bytes", nodeBytes);
        source.putArray("nested").add(nodeBytes);
        JsonNode mapSnapshot = OpenAiJsonInput.snapshot(Map.of("bytes", mapBytes));
        JsonNode nodeSnapshot = OpenAiJsonInput.snapshot(source);
        assertTrue(mapSnapshot.path("bytes").isTextual());
        assertTrue(nodeSnapshot.path("bytes").isTextual());
        assertTrue(nodeSnapshot.at("/nested/0").isTextual());
        mapBytes[0] = 4;
        nodeBytes[0] = 4;
        assertEquals("AQID", mapSnapshot.path("bytes").textValue());
        assertEquals("AQID", nodeSnapshot.path("bytes").textValue());
        assertEquals("AQID", nodeSnapshot.at("/nested/0").textValue());
        assertTrue(source.path("bytes").isBinary());
        assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(mapBytes));
        assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(JSON.getNodeFactory().binaryNode(nodeBytes)));
    }

    @Test void includesBase64ExpansionInCompactUtf8ByteLimit() {
        // 196599 bytes become 262132 base64 characters; {"bytes":""} adds 12 bytes.
        var exact = Map.of("bytes", new byte[196_599]);
        assertEquals(256 * 1024, JsonSupport.utf8Bytes(OpenAiJsonInput.snapshot(exact)).length);
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiJsonInput.snapshot(Map.of("bytes", new byte[196_600])));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiJsonInput.snapshot(JsonSupport.object().put("bytes", new byte[196_600])));
    }

    @Test void rejectsUnsupportedTopLevelShapesAndNonfiniteNestedNumbers() {
        for (Object input : Arrays.asList(null, "text", 1, true, List.of("text"), List.of(List.of()),
                Arrays.asList(Map.of("a", 1), null), JSON.nullNode(), JSON.missingNode())) {
            assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(input));
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(Map.of("number", value)));
            assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(
                    JsonSupport.object().put("number", value)));
        }
    }

    @Test void enforcesExactlyThirtyTwoContainerLevels() {
        assertDoesNotThrow(() -> OpenAiJsonInput.snapshot(nestedObjects(32)));
        assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(nestedObjects(33)));
        ObjectNode alternating = JsonSupport.object();
        ObjectNode cursor = alternating;
        for (int i = 0; i < 15; i++) cursor = cursor.putArray("a").addObject();
        cursor.putArray("a"); // Object + 15 array/object pairs + array = 32 containers.
        assertDoesNotThrow(() -> OpenAiJsonInput.snapshot(alternating));
        ((com.fasterxml.jackson.databind.node.ArrayNode) cursor.path("a")).addObject();
        assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(alternating));
    }

    @Test void countsContainersAndEveryNestedValueButNotObjectKeys() {
        var source = JsonSupport.object();
        var values = source.putArray("values");
        for (int i = 0; i < 9_998; i++) values.addNull();
        assertDoesNotThrow(() -> OpenAiJsonInput.snapshot(source));
        values.addNull();
        assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(source));
    }

    @Test void measuresCompactUtf8BytesIncludingEscapesAndMultibyteCharacters() {
        var exact = Map.of("s", "a".repeat(256 * 1024 - 8));
        assertEquals(256 * 1024, JsonSupport.utf8Bytes(OpenAiJsonInput.snapshot(exact)).length);
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiJsonInput.snapshot(Map.of("s", "a".repeat(256 * 1024 - 7))));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiJsonInput.snapshot(Map.of("s", "가".repeat(87_379))));
        assertThrows(IllegalArgumentException.class,
                () -> OpenAiJsonInput.snapshot(Map.of("s", "\n".repeat(131_069))));
    }

    @Test void conversionAndValidationFailuresDoNotExposeApplicationDataOrCauses() {
        var malformed = JsonSupport.object().putRawValue("private-field", new RawValue("SECRET-BROKEN-JSON"));
        var malformedTrailing = JsonSupport.object().putRawValue("private-field", new RawValue("1 SECRET-TRAILING"));
        var unsupported = new LinkedHashMap<String, Object>();
        unsupported.put("private-field", new Object() {
            public String getSecret() { throw new IllegalStateException("SECRET-GETTER"); }
        });
        for (Object input : List.of(malformed, malformedTrailing, unsupported,
                List.of("SECRET-INVALID-SHAPE"), Map.of("SECRET-NUMBER", Double.NaN))) {
            var failure = assertThrows(IllegalArgumentException.class, () -> OpenAiJsonInput.snapshot(input));
            assertFalse(failure.getMessage().contains("SECRET"));
            assertFalse(failure.getMessage().contains("private-field"));
            assertNull(failure.getCause());
        }
    }

    private static ObjectNode nestedObjects(int levels) {
        ObjectNode root = JsonSupport.object();
        ObjectNode cursor = root;
        for (int i = 1; i < levels; i++) cursor = cursor.putObject("child");
        return root;
    }
}
