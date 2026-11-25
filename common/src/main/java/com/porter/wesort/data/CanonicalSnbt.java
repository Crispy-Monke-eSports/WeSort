package com.porter.wesort.data;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WeSort's versioned canonical SNBT profile. A typed tree, not a JSON conversion.
 * The native Minecraft parser remains the conformance oracle at the game boundary.
 */
public final class CanonicalSnbt {
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 4_000_000;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_NODES = 200_000;
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_+.-]+");
    private static final String DEC = "(?:0|[1-9](?:_*[0-9])*)";
    private static final String DIGITS = "[0-9](?:_*[0-9])*";
    private static final Pattern DECIMAL = Pattern.compile("([+-]?(?:" + DIGITS + "(?:\\.(?:" + DIGITS + ")?)?|\\."
            + DIGITS + ")(?:[eE][+-]?" + DIGITS + ")?)([fFdD]?)");
    private static final Pattern INTEGER = Pattern.compile("([+-]?)(" + DEC + ")([sSuU]?[bBsSiIlL])?");
    private static final Pattern HEX =
            Pattern.compile("([+-]?)0[xX]([0-9a-fA-F](?:_*[0-9a-fA-F])*)([sSuU][bBsSiIlL]|[sSiIlL])?");
    private static final Pattern BINARY = Pattern.compile("([+-]?)0[bB]([01](?:_*[01])*)([sSuU]?[bBsSiIlL])?");

    private CanonicalSnbt() {}

    public sealed interface Value permits Compound, Sequence, Text, NumberTag, ArrayTag {}

    public record Compound(Map<String, Value> entries) implements Value {
        public Compound {
            entries = Collections.unmodifiableMap(new TreeMap<>(entries));
            entries.forEach((k, v) -> {
                validateString(k);
                Objects.requireNonNull(v);
            });
        }

        public Value get(String key) {
            return entries.get(key);
        }
    }

    public record Sequence(List<Value> values) implements Value {
        public Sequence {
            values = List.copyOf(values);
        }
    }

    public record Text(String value) implements Value {
        public Text {
            validateString(value);
        }
    }

    public enum Type {
        BYTE(8, "b"),
        SHORT(16, "s"),
        INT(32, ""),
        LONG(64, "L"),
        FLOAT(0, "f"),
        DOUBLE(0, "d");

        private final int bits;
        private final String suffix;

        Type(int bits, String suffix) {
            this.bits = bits;
            this.suffix = suffix;
        }
    }

    /** Integral values use their signed representation; floating values retain signed zero. */
    public record NumberTag(Type type, String value) implements Value {
        public NumberTag {
            Objects.requireNonNull(type);
            Objects.requireNonNull(value);
            if (type.bits > 0) {
                BigInteger integer = new BigInteger(value);
                if (!fits(integer, type.bits)) throw invalid("Integer out of range");
                value = integer.toString();
            } else if (type == Type.FLOAT) {
                float number = Float.parseFloat(value);
                if (!Float.isFinite(number)) throw invalid("Non-finite float");
                value = Float.toString(number);
            } else {
                double number = Double.parseDouble(value);
                if (!Double.isFinite(number)) throw invalid("Non-finite double");
                value = Double.toString(number);
            }
        }

        public long longValue() {
            if (type.bits == 0) throw invalid("Expected integral tag");
            return Long.parseLong(value);
        }
    }

    public record ArrayTag(Type type, List<NumberTag> values) implements Value {
        public ArrayTag {
            if (type != Type.BYTE && type != Type.INT && type != Type.LONG) throw invalid("Invalid typed array");
            values = List.copyOf(values);
            for (NumberTag n : values) if (n.type != type) throw invalid("Array element type mismatch");
        }
    }

    public static Value parse(String input) {
        checkSize(input);
        Parser parser = new Parser(input);
        Value value = parser.value(0);
        parser.space();
        if (parser.at != input.length()) throw invalid("Trailing SNBT data");
        return value;
    }

    public static Compound compound(String input) {
        if (!(parse(input) instanceof Compound compound)) throw invalid("Expected SNBT compound");
        return compound;
    }

    public static String canonicalize(String input) {
        return write(parse(input));
    }

    public static String write(Value value) {
        StringBuilder out = new StringBuilder();
        emit(Objects.requireNonNull(value), out, 0, new int[1]);
        String result = out.toString();
        checkSize(result);
        return result;
    }

    /** Domain is a versioned identifier, not caller-controlled arbitrary content. */
    public static String fingerprint(String domain, Value value) {
        if (domain == null || !domain.matches("[a-z0-9-]{1,64}")) throw invalid("Invalid hash domain");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("wesort-snbt-" + VERSION + "\0" + domain + "\0").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(write(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void emit(Value value, StringBuilder out, int depth, int[] nodes) {
        limit(depth, ++nodes[0]);
        switch (value) {
            case Compound c -> {
                out.append('{');
                boolean first = true;
                for (var entry : c.entries.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    quote(entry.getKey(), out);
                    out.append(':');
                    emit(entry.getValue(), out, depth + 1, nodes);
                }
                out.append('}');
            }
            case Sequence s -> {
                out.append('[');
                boolean first = true;
                for (Value child : s.values) {
                    if (!first) out.append(',');
                    first = false;
                    emit(child, out, depth + 1, nodes);
                }
                out.append(']');
            }
            case Text s -> quote(s.value, out);
            case NumberTag n -> out.append(n.value).append(n.type.suffix);
            case ArrayTag a -> {
                out.append('[')
                        .append(
                                switch (a.type) {
                                    case BYTE -> 'B';
                                    case INT -> 'I';
                                    case LONG -> 'L';
                                    default -> throw invalid("Invalid array type");
                                })
                        .append(';');
                boolean first = true;
                for (NumberTag n : a.values) {
                    if (!first) out.append(',');
                    first = false;
                    emit(n, out, depth + 1, nodes);
                }
                out.append(']');
            }
        }
        if (out.length() > MAX_BYTES) throw invalid("SNBT output too large");
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32 || c == 127) {
                        out.append("\\u");
                        for (int shift = 12; shift >= 0; shift -= 4)
                            out.append(Character.forDigit((c >> shift) & 15, 16));
                    } else out.append(c);
                }
            }
            if (out.length() > MAX_BYTES) throw invalid("SNBT output too large");
        }
        out.append('"');
    }

    private static void validateString(String s) {
        Objects.requireNonNull(s);
        if (s.length() > MAX_BYTES) throw invalid("SNBT string too large");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
                    throw invalid("Unpaired UTF-16 surrogate");
            } else if (Character.isLowSurrogate(c)) throw invalid("Unpaired UTF-16 surrogate");
        }
    }

    private static void checkSize(String input) {
        validateString(input);
        if (input.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("SNBT too large");
    }

    private static void limit(int depth, int nodes) {
        if (depth > MAX_DEPTH || nodes > MAX_NODES) throw invalid("SNBT tree limit exceeded");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static boolean fits(BigInteger n, int bits) {
        return n.bitLength() < bits;
    }

    private static Type width(char c) {
        return switch (Character.toLowerCase(c)) {
            case 'b' -> Type.BYTE;
            case 's' -> Type.SHORT;
            case 'i' -> Type.INT;
            case 'l' -> Type.LONG;
            default -> throw invalid("Invalid integer suffix");
        };
    }

    private static NumberTag integer(Matcher m, int radix, Type defaultType) {
        String digits = m.group(2).replace("_", "");
        // Avoid allocating arbitrary-precision numbers for adversarial multi-megabyte tokens.
        if (digits.length() > 64) throw invalid("Integer literal too long");
        String suffix = m.group(3);
        Type type = suffix == null ? defaultType : width(suffix.charAt(suffix.length() - 1));
        boolean unsigned = suffix != null && suffix.length() == 2
                ? Character.toLowerCase(suffix.charAt(0)) == 'u'
                : radix != 10 && !m.group(1).equals("-");
        BigInteger n = new BigInteger(m.group(1) + digits, radix);
        if (unsigned) {
            if (n.signum() < 0 || n.bitLength() > type.bits) throw invalid("Unsigned integer out of range");
            if (n.testBit(type.bits - 1)) n = n.subtract(BigInteger.ONE.shiftLeft(type.bits));
        } else if (!fits(n, type.bits)) throw invalid("Signed integer out of range");
        return new NumberTag(type, n.toString());
    }

    private static NumberTag number(String token, Type defaultType) {
        if (token.length() > 256) throw invalid("Numeric literal too long");
        Matcher m = HEX.matcher(token);
        if (m.matches()) return integer(m, 16, defaultType);
        m = BINARY.matcher(token);
        if (m.matches()) return integer(m, 2, defaultType);
        m = INTEGER.matcher(token);
        if (m.matches()) return integer(m, 10, defaultType);
        m = DECIMAL.matcher(token);
        if (m.matches()) {
            String body = m.group(1).replace("_", "");
            String suffix = m.group(2);
            if (suffix.isEmpty() && body.indexOf('.') < 0 && body.indexOf('e') < 0 && body.indexOf('E') < 0)
                throw invalid("Invalid decimal integer");
            Type type = suffix.equalsIgnoreCase("f") ? Type.FLOAT : Type.DOUBLE;
            return new NumberTag(type, body);
        }
        throw invalid("Invalid numeric literal");
    }

    private static final class Parser {
        final String in;
        int at;
        int nodes;

        Parser(String in) {
            this.in = in;
        }

        void space() {
            while (at < in.length() && Character.isWhitespace(in.charAt(at))) at++;
        }

        boolean take(char c) {
            space();
            if (at >= in.length() || in.charAt(at) != c) return false;
            at++;
            return true;
        }

        void need(char c) {
            if (!take(c)) throw invalid("Expected '" + c + "' at " + at);
        }

        char peek() {
            space();
            if (at == in.length()) throw invalid("Unexpected end of SNBT");
            return in.charAt(at);
        }

        Value value(int depth) {
            limit(depth, ++nodes);
            char c = peek();
            if (c == '{') return compound(depth);
            if (c == '[') return sequence(depth);
            if (c == '"' || c == '\'') return new Text(string());
            String token = token();
            if (token.equals("true") || token.equals("false"))
                return new NumberTag(Type.BYTE, token.equals("true") ? "1" : "0");
            if ((token.equals("bool") || token.equals("uuid")) && take('(')) {
                Value arg = value(depth + 1);
                need(')');
                if (token.equals("bool") && arg instanceof NumberTag n)
                    return new NumberTag(Type.BYTE, Double.parseDouble(n.value) == 0 ? "0" : "1");
                if (token.equals("uuid") && arg instanceof Text s) {
                    UUID uuid = UUID.fromString(s.value);
                    if (!uuid.toString().equalsIgnoreCase(s.value)) throw invalid("Invalid UUID");
                    long hi = uuid.getMostSignificantBits(), lo = uuid.getLeastSignificantBits();
                    return new ArrayTag(
                            Type.INT,
                            List.of(intTag((int) (hi >>> 32)), intTag((int) hi), intTag((int) (lo >>> 32)), intTag((int)
                                    lo)));
                }
                throw invalid("Invalid SNBT operation argument");
            }
            if (numericStart(token.charAt(0))) return number(token, Type.INT);
            if (!WORD.matcher(token).matches()) throw invalid("Invalid unquoted string");
            return new Text(token);
        }

        Compound compound(int depth) {
            need('{');
            Map<String, Value> entries = new TreeMap<>();
            if (take('}')) return new Compound(entries);
            do {
                char c = peek();
                String key = c == '"' || c == '\'' ? string() : token();
                if (!WORD.matcher(key).matches() && c != '"' && c != '\'') throw invalid("Invalid compound key");
                need(':');
                if (entries.containsKey(key)) throw invalid("Duplicate compound key");
                entries.put(key, value(depth + 1));
                if (take('}')) return new Compound(entries);
                need(',');
                if (take('}')) return new Compound(entries);
            } while (true);
        }

        Value sequence(int depth) {
            need('[');
            if (take(']')) return new Sequence(List.of());
            int start = at;
            char c = peek();
            if (c == 'B' || c == 'I' || c == 'L') {
                at++;
                if (take(';')) return array(width(c), depth);
                at = start;
            }
            List<Value> values = new ArrayList<>();
            do {
                values.add(value(depth + 1));
                if (take(']')) return new Sequence(values);
                need(',');
                if (take(']')) return new Sequence(values);
            } while (true);
        }

        ArrayTag array(Type type, int depth) {
            List<NumberTag> values = new ArrayList<>();
            if (take(']')) return new ArrayTag(type, values);
            do {
                limit(depth + 1, ++nodes);
                String token = token();
                NumberTag n = number(token, type);
                if (n.type.bits == 0 || n.type.bits > type.bits) throw invalid("Invalid array element type");
                values.add(new NumberTag(type, n.value));
                if (take(']')) return new ArrayTag(type, values);
                need(',');
                if (take(']')) return new ArrayTag(type, values);
            } while (true);
        }

        String token() {
            space();
            int start = at;
            while (at < in.length()) {
                char c = in.charAt(at);
                if ((c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z')
                        || (c >= '0' && c <= '9')
                        || c == '_'
                        || c == '+'
                        || c == '-'
                        || c == '.') at++;
                else break;
            }
            if (start == at) throw invalid("Expected SNBT token at " + at);
            return in.substring(start, at);
        }

        String string() {
            char delimiter = peek();
            at++;
            StringBuilder s = new StringBuilder();
            while (at < in.length()) {
                char c = in.charAt(at++);
                if (c == delimiter) {
                    String result = s.toString();
                    validateString(result);
                    return result;
                }
                if (c != '\\') {
                    s.append(c);
                    continue;
                }
                if (at == in.length()) throw invalid("Truncated escape");
                switch (in.charAt(at++)) {
                    case '\\' -> s.append('\\');
                    case '"' -> s.append('"');
                    case '\'' -> s.append('\'');
                    case 'b' -> s.append('\b');
                    case 'f' -> s.append('\f');
                    case 'n' -> s.append('\n');
                    case 'r' -> s.append('\r');
                    case 's' -> s.append(' ');
                    case 't' -> s.append('\t');
                    case 'x' -> s.append((char) hex(2));
                    case 'u' -> s.append((char) hex(4));
                    case 'U' -> appendCodePoint(s, hex(8));
                    case 'N' -> {
                        if (at >= in.length() || in.charAt(at++) != '{') throw invalid("Invalid named escape");
                        int end = in.indexOf('}', at);
                        if (end < 0 || end - at > 128) throw invalid("Invalid named escape");
                        appendCodePoint(s, Character.codePointOf(in.substring(at, end)));
                        at = end + 1;
                    }
                    default -> throw invalid("Invalid SNBT escape");
                }
            }
            throw invalid("Unterminated SNBT string");
        }

        int hex(int length) {
            if (at + length > in.length()) throw invalid("Truncated hex escape");
            long n = 0;
            for (int i = 0; i < length; i++) {
                int digit = Character.digit(in.charAt(at++), 16);
                if (digit < 0) throw invalid("Invalid hex escape");
                n = n * 16 + digit;
            }
            if (n > Integer.MAX_VALUE) throw invalid("Invalid Unicode code point");
            return (int) n;
        }
    }

    private static void appendCodePoint(StringBuilder s, int c) {
        if (!Character.isValidCodePoint(c) || c >= 0xd800 && c <= 0xdfff) throw invalid("Invalid Unicode code point");
        s.appendCodePoint(c);
    }

    private static boolean numericStart(char c) {
        return c >= '0' && c <= '9' || c == '-' || c == '+' || c == '.';
    }

    private static NumberTag intTag(int value) {
        return new NumberTag(Type.INT, Integer.toString(value));
    }
}
