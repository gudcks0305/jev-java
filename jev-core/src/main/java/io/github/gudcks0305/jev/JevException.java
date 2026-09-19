package io.github.gudcks0305.jev;

/** A service, transport, or response-contract failure. Messages never include request bodies or keys. */
public class JevException extends RuntimeException {
    public enum Kind { AUTHENTICATION, VALIDATION, RATE_LIMIT, SERVER, HTTP, CONNECTION, TIMEOUT, PROTOCOL, CLOSED }
    private final Kind kind;
    private final int statusCode;

    public JevException(Kind kind, String message) { this(kind, message, 0); }
    public JevException(Kind kind, String message, int statusCode) {
        super(message);
        this.kind = kind;
        this.statusCode = statusCode;
    }
    public JevException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.statusCode = 0;
    }
    public Kind kind() { return kind; }
    public int statusCode() { return statusCode; }
}
