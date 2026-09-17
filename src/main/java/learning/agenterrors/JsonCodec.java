package learning.agenterrors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonCodec {
    private JsonCodec() {}

    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return '"' + escape(text) + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                out.append(encode(String.valueOf(entry.getKey()))).append(':').append(encode(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable<?> values) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(',');
                first = false;
                out.append(encode(item));
            }
            return out.append(']').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    static Map<String, Object> decodeObject(String json) {
        Object value = new Parser(json).parseDocument();
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Expected a JSON object");
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) { this.source = source; }

        private Object parseDocument() {
            Object value = parseValue();
            whitespace();
            if (index != source.length()) fail("Trailing content");
            return value;
        }

        private Object parseValue() {
            whitespace();
            if (index >= source.length()) return fail("Missing value");
            return switch (source.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> values = new LinkedHashMap<>();
            index++;
            whitespace();
            if (take('}')) return values;
            do {
                whitespace();
                String key = parseString();
                whitespace();
                expect(':');
                values.put(key, parseValue());
                whitespace();
            } while (take(','));
            expect('}');
            return values;
        }

        private List<Object> parseArray() {
            List<Object> values = new ArrayList<>();
            index++;
            whitespace();
            if (take(']')) return values;
            do {
                values.add(parseValue());
                whitespace();
            } while (take(','));
            expect(']');
            return values;
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (index < source.length()) {
                char c = source.charAt(index++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (index >= source.length()) fail("Incomplete escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) fail("Incomplete unicode escape");
                        out.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> fail("Invalid escape");
                }
            }
            return fail("Unterminated string");
        }

        private Object parseNumber() {
            int start = index;
            if (take('-')) {}
            while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            if (take('.')) while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            if (index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if (index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) index++;
            }
            if (start == index) return fail("Invalid value");
            String token = source.substring(start, index);
            return token.contains(".") || token.contains("e") || token.contains("E")
                    ? Double.valueOf(token) : Long.valueOf(token);
        }

        private Object literal(String token, Object value) {
            if (!source.startsWith(token, index)) return fail("Invalid literal");
            index += token.length();
            return value;
        }

        private void whitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }

        private boolean take(char expected) {
            if (index < source.length() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) fail("Expected " + expected);
        }

        private <T> T fail(String message) {
            throw new IllegalArgumentException(message + " at character " + index);
        }
    }
}
