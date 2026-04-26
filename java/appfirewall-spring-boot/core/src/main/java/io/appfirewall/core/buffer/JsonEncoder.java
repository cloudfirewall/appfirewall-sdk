package io.appfirewall.core.buffer;

import java.util.Collection;
import java.util.Map;

/**
 * Tiny JSON encoder for event maps. Supports {@code null}, {@link Boolean},
 * {@link Number}, {@link String}, {@link Map} (objects), and
 * {@link Collection} / arrays (arrays). Anything else is encoded as its
 * {@code toString()} as a JSON string.
 *
 * <p>Hand-rolled rather than depending on Jackson because the {@code core}
 * module deliberately ships with no runtime deps. Spec §10 calls this out as
 * a goal: {@code core/} should be reusable from a hypothetical plain-Java or
 * Micronaut SDK without dragging Spring or a JSON library along.
 */
final class JsonEncoder {

    private JsonEncoder() {}

    /** Encode a single value. Output is compact (no whitespace). */
    static String encode(Object value) {
        StringBuilder sb = new StringBuilder(64);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            // Avoid scientific notation for ints; trust toString for floats.
            String s = n.toString();
            sb.append(s);
        } else if (value instanceof Map<?, ?> m) {
            writeObject(sb, m);
        } else if (value instanceof Collection<?> c) {
            writeArray(sb, c);
        } else if (value instanceof Object[] arr) {
            writeArrayBoxed(sb, arr);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Collection<?> coll) {
        sb.append('[');
        boolean first = true;
        for (Object item : coll) {
            if (!first) sb.append(',');
            first = false;
            write(sb, item);
        }
        sb.append(']');
    }

    private static void writeArrayBoxed(StringBuilder sb, Object[] arr) {
        sb.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            write(sb, arr[i]);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
