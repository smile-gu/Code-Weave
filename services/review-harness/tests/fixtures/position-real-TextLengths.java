package acceptance;

import java.util.Objects;

public final class TextLengths {
    private TextLengths() {}

    /** Null is an allowed input and must return zero. */
    public static int nullableLength(String text) {
        return text.length();
    }

    /** Null is invalid and must throw NullPointerException. */
    public static int requiredLength(String text) {
        Objects.requireNonNull(text, "text");
        int length = text.length();
        return length;
    }
}
