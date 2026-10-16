package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gudcks0305.jev.internal.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JevInputTest {
    @TempDir Path directory;

    @Test void snapshotsBytesStateAndImageCollectionWithoutLeakingDataInToString() {
        byte[] bytes = {1, 2, 3}; // Synthetic bytes; decoding belongs to the provider.
        var image = JevImage.fromBytes(bytes, "png");
        bytes[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, Base64.getDecoder().decode(image.dataBase64()));
        var state = JsonSupport.object().put("photo", image.reference()).put("secret", "private");
        var images = new JevImage[]{image, image};
        var input = JevInput.withImages(state, images);
        images[0] = null;
        state.removeAll();
        ((ObjectNode) input.state()).removeAll();
        assertEquals(image.reference(), input.state().path("photo").asText());
        assertEquals(1, input.images().size());
        assertThrows(UnsupportedOperationException.class, () -> input.images().clear());
        assertFalse(image.toString().contains(image.dataBase64()));
        assertFalse(input.toString().contains("private"));
    }

    @Test void readsFileWithoutExposingItsPath() throws Exception {
        var path = directory.resolve("private-photo.jpg");
        Files.write(path, new byte[]{1, 2});
        var image = JevImage.fromPath(path, "jpeg");
        assertEquals("jpeg", image.format());
        assertEquals("AQI=", image.dataBase64());
        assertFalse(image.toString().contains(path.toString()));
    }

    @Test void snapshotsPojoValuesInsideJsonNodes() {
        var image = JevImage.fromBytes(new byte[]{1}, "png");
        var photos = new ArrayList<>(java.util.List.of(image.reference()));
        var state = JsonSupport.object().putPOJO("photos", photos);
        var input = JevInput.withImages(state, image);
        photos.clear();
        assertEquals(image.reference(), input.state().at("/photos/0").textValue());
        assertTrue(input.state().path("photos").isArray());
    }

    @Test void rejectsInvalidImageAndInputShapes() {
        assertThrows(IllegalArgumentException.class, () -> JevImage.fromBytes(new byte[]{1}, "webp"));
        assertThrows(IllegalArgumentException.class, () -> JevImage.fromBytes(new byte[]{1}, "PNG"));
        assertThrows(IllegalArgumentException.class, () -> JevImage.fromBytes(new byte[0], "png"));
        assertThrows(IllegalArgumentException.class, () -> JevImage.fromBytes(new byte[16 * 1024 * 1024 + 1], "png"));
        assertThrows(NullPointerException.class, () -> JevImage.fromBytes(null, "png"));
        assertThrows(IllegalArgumentException.class, () -> JevInput.withImages("state"));
        assertThrows(NullPointerException.class, () -> JevInput.withImages("state", (JevImage) null));
        assertThrows(IllegalArgumentException.class, () -> JevInput.withImages(123, JevImage.fromBytes(new byte[]{1}, "png")));
        assertNotEquals(JevImage.fromBytes(new byte[]{1}, "png").reference(),
                JevImage.fromBytes(new byte[]{1}, "png").reference());
    }
}
