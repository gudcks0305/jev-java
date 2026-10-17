package io.github.gudcks0305.jev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable experimental Jev image asset. Put {@link #reference()} in state,
 * instructions, or criteria, then attach this image with {@link JevInput#withImages}.
 * The caller prepares upright, still PNG/JPEG pixels; the server validates dimensions
 * and image contents. No resizing, URL fetching, or EXIF rotation is performed.
 */
public final class JevImage {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final String reference = UUID.randomUUID().toString();
    private final String format;
    private final String dataBase64;

    private JevImage(byte[] data, String format) {
        Objects.requireNonNull(data, "Image data");
        if (!("png".equals(format) || "jpg".equals(format) || "jpeg".equals(format))) {
            throw new IllegalArgumentException("Image format must be png, jpg, or jpeg");
        }
        if (data.length == 0 || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("Image must contain between 1 byte and 16 MiB");
        }
        this.format = format;
        this.dataBase64 = Base64.getEncoder().encodeToString(data);
    }

    public static JevImage fromBytes(byte[] data, String format) { return new JevImage(data, format); }

    /** Reads at most 16 MiB plus one byte; file names and paths are not sent to the API. */
    public static JevImage fromPath(Path path, String format) throws IOException {
        try (var stream = Files.newInputStream(Objects.requireNonNull(path, "path"))) {
            return fromBytes(stream.readNBytes(MAX_BYTES + 1), format);
        }
    }

    public String reference() { return reference; }
    public String format() { return format; }
    public String dataBase64() { return dataBase64; }

    @Override public String toString() { return "JevImage[format=" + format + ", data=<redacted>]"; }
}
