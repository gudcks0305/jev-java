package io.github.gudcks0305.jev;

/** Box edges as fractions of image height (top/bottom) and width (left/right). */
public record BoxCoordinates(double top, double bottom, double left, double right) {
    public BoxCoordinates {
        edge(top);
        edge(bottom);
        edge(left);
        edge(right);
        if (top >= bottom || left >= right) {
            throw new IllegalArgumentException("Box edges must satisfy top < bottom and left < right");
        }
    }

    private static void edge(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("Box edges must be finite values between 0 and 1");
        }
    }
}
