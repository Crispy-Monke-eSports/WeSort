package com.porter.wesort.snbt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR2 (#224/#225): shared, lossless, canonical SNBT codec.
 *
 * <p>Pure-Java model of the NBT tag tree (ids 1-12, excluding TAG_End as a value)
 * with a strict parser and a canonical writer. No Minecraft dependency: the 26.3
 * client adapts native {@code Tag}/{@code ItemStack} to/from this model, and the
 * Manager parses/validates the same grammar without Minecraft on the classpath.
 *
 * <p>Canonical profile "WeSort v1":
 * <ul>
 *   <li>UTF-8 without BOM; duplicate compound keys are rejected (fail-closed).</li>
 *   <li>No whitespace outside strings; double-quoted strings/keys with deterministic escapes.</li>
 *   <li>Decimal signed numbers with explicit suffix ({@code b/s/i/L/f/d}); no bare
 *       doubles without suffix in canonical form.</li>
 *   <li>Typed arrays {@code [B;..]}, {@code [I;..]}, {@code [L;..]} with uppercase prefix.</li>
 *   <li>List/array order is significant and always preserved; compound keys are
 *       sorted by UTF-16 code units (Java {@link String} ordering, documented).</li>
 *   <li>Resource limits: max depth, max nodes, max bytes — enforced by the parser.</li>
 * </ul>
 *
 * <p>Digests are SHA-256 over UTF-8 canonical bytes, prefixed with an explicit
 * domain string and contract version (never bare {@code sha1(x).substring(0,8)}).
 * Each identity domain has its own digest function so a content hash can never
 * be confused with a stack, snapshot, slot-path or proof hash.
 */
public final class SnbtCodec {
    private SnbtCodec() {}

    /** WeSort SNBT contract version. Bump for any canonical-form change. */
    public static final String CONTRACT_VERSION = "wesort-snbt:1";

    /** Minecraft data version this contract was pinned against. */
    public static final String MINECRAFT_DATA = "26.3";

    public static final int MAX_DEPTH = 64;
    public static final int MAX_NODES = 65536;
    public static final long MAX_BYTES = 4L * 1024L * 1024L;

    /** NBT tag ids 1-12 (TAG_End == 0 is never a value). */
    public enum TagType {
        BYTE(1),
        SHORT(2),
        INT(3),
        LONG(4),
        FLOAT(5),
        DOUBLE(6),
        BYTE_ARRAY(7),
        STRING(8),
        LIST(9),
        COMPOUND(10),
        INT_ARRAY(11),
        LONG_ARRAY(12);

        public final int id;

        TagType(int id) {
            this.id = id;
        }

        public static TagType ofId(int id) {
            for (TagType t : values()) if (t.id == id) return t;
            throw new SnbtException("unknown tag id: " + id);
        }
    }

    /** Immutable typed tag value. */
    public sealed interface Tag
            permits TByte,
                    TShort,
                    TInt,
                    TLong,
                    TFloat,
                    TDouble,
                    TByteArray,
                    TString,
                    TList,
                    TCompound,
                    TIntArray,
                    TLongArray {
        TagType type();
    }

    public record TByte(byte value) implements Tag {
        public TagType type() {
            return TagType.BYTE;
        }
    }

    public record TShort(short value) implements Tag {
        public TagType type() {
            return TagType.SHORT;
        }
    }

    public record TInt(int value) implements Tag {
        public TagType type() {
            return TagType.INT;
        }
    }

    public record TLong(long value) implements Tag {
        public TagType type() {
            return TagType.LONG;
        }
    }

    public record TFloat(float value) implements Tag {
        public TagType type() {
            return TagType.FLOAT;
        }
    }

    public record TDouble(double value) implements Tag {
        public TagType type() {
            return TagType.DOUBLE;
        }
    }

    public record TByteArray(byte[] value) implements Tag {
        public TagType type() {
            return TagType.BYTE_ARRAY;
        }
    }

    public record TString(String value) implements Tag {
        public TagType type() {
            return TagType.STRING;
        }
    }

    public record TList(List<Tag> value) implements Tag {
        public TList { value = List.copyOf(value); }

        public TagType type() {
            return TagType.LIST;
        }
    }

    public record TCompound(Map<String, Tag> value) implements Tag {
        public TCompound { value = Map.copyOf(value); }

        public Tag get(String key) { return value.get(key); }

        public TagType type() {
            return TagType.COMPOUND;
        }
    }

    public record TIntArray(int[] value) implements Tag {
        public TagType type() {
            return TagType.INT_ARRAY;
        }
    }

    public record TLongArray(long[] value) implements Tag {
        public TagType type() {
            return TagType.LONG_ARRAY;
        }
    }

    public static final class SnbtException extends IllegalArgumentException {
        public SnbtException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Canonical writer
    // ------------------------------------------------------------------

    /** Canonical SNBT text for a tag. Deterministic: same tree -> same bytes. */
    public static String write(Tag tag) {
        StringBuilder sb = new StringBuilder();
        writeTag(sb, tag);
        return sb.toString();
    }

    public static TCompound compound(String snbt) {
        if (parse(snbt) instanceof TCompound compound) return compound;
        throw new SnbtException("Expected SNBT compound");
    }

    public static String canonicalize(String snbt) {
        return write(parse(snbt));
    }

    public static String fingerprint(String domain, Tag value) {
        return sha256Hex(domain, write(value));
    }

    private static void writeTag(StringBuilder sb, Tag tag) {
        if (tag instanceof TByte b) sb.append(b.value()).append('b');
        else if (tag instanceof TShort s) sb.append(s.value()).append('s');
        else if (tag instanceof TInt i) sb.append(i.value());
        else if (tag instanceof TLong l) sb.append(l.value()).append('L');
        else if (tag instanceof TFloat f) sb.append(canonicalFloat(f.value())).append('f');
        else if (tag instanceof TDouble d) sb.append(canonicalDouble(d.value())).append('d');
        else if (tag instanceof TByteArray a) {
            sb.append("[B;");
            for (int i = 0; i < a.value().length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a.value()[i]).append('b');
            }
            sb.append(']');
        } else if (tag instanceof TString s) writeQuoted(sb, s.value());
        else if (tag instanceof TList l) {
            sb.append('[');
            for (int i = 0; i < l.value().size(); i++) {
                if (i > 0) sb.append(',');
                writeTag(sb, l.value().get(i));
            }
            sb.append(']');
        } else if (tag instanceof TCompound c) {
            sb.append('{');
            // Documented: UTF-16 code-unit ordering (Java String ordering).
            Map<String, Tag> sorted = new TreeMap<>(c.value());
            boolean first = true;
            for (Map.Entry<String, Tag> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeQuoted(sb, e.getKey());
                sb.append(':');
                writeTag(sb, e.getValue());
            }
            sb.append('}');
        } else if (tag instanceof TIntArray a) {
            sb.append("[I;");
            for (int i = 0; i < a.value().length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a.value()[i]);
            }
            sb.append(']');
        } else if (tag instanceof TLongArray a) {
            sb.append("[L;");
            for (int i = 0; i < a.value().length; i++) {
                if (i > 0) sb.append(',');
                sb.append(a.value()[i]).append('L');
            }
            sb.append(']');
        } else throw new SnbtException("unhandled tag: " + tag.getClass());
    }

    private static String canonicalFloat(float v) {
        if (!Float.isFinite(v)) throw new SnbtException("non-finite float has no canonical form");
        if (v == 0.0f) return Float.floatToIntBits(v) == 0x80000000 ? "-0.0" : "0.0";
        return Float.toString(v);
    }

    private static String canonicalDouble(double v) {
        if (!Double.isFinite(v)) throw new SnbtException("non-finite double has no canonical form");
        if (v == 0.0d) return Double.doubleToLongBits(v) == 0x8000000000000000L ? "-0.0" : "0.0";
        return Double.toString(v);
    }

    private static void writeQuoted(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04X", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Strict parser (full consumption, limits, duplicate-key rejection)
    // ------------------------------------------------------------------

    /** Parse canonical (or compatible) SNBT strictly. Fails closed on any deviation. */
    public static Tag parse(String snbt) {
        if (snbt == null) throw new SnbtException("null SNBT");
        byte[] bytes = snbt.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new SnbtException("SNBT exceeds max bytes: " + bytes.length);
        Parser p = new Parser(snbt);
        Tag tag = p.parseTag(0);
        p.skipWs();
        if (!p.eof()) throw new SnbtException("trailing content after SNBT value at " + p.pos);
        return tag;
    }

    private static final class Parser {
        final String s;
        int pos;
        int nodes;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        void skipWs() {
            while (!eof()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        Tag parseTag(int depth) {
            if (depth > MAX_DEPTH) throw new SnbtException("SNBT exceeds max depth");
            if (++nodes > MAX_NODES) throw new SnbtException("SNBT exceeds max nodes");
            skipWs();
            if (eof()) throw new SnbtException("unexpected end of SNBT");
            char c = s.charAt(pos);
            if (c == '{') return parseCompound(depth);
            if (c == '[') return parseListOrArray(depth);
            if (c == '"' || c == '\'') return new TString(parseQuoted());
            return parseScalar();
        }

        TCompound parseCompound(int depth) {
            expect('{');
            Map<String, Tag> map = new LinkedHashMap<>();
            skipWs();
            if (peek('}')) {
                pos++;
                return new TCompound(map);
            }
            while (true) {
                skipWs();
                String key;
                if (!eof() && (s.charAt(pos) == '"' || s.charAt(pos) == '\'')) key = parseQuoted();
                else key = parseUnquotedKey();
                skipWs();
                expect(':');
                Tag value = parseTag(depth + 1);
                if (map.containsKey(key)) throw new SnbtException("duplicate compound key: " + key);
                map.put(key, value);
                skipWs();
                if (eof()) throw new SnbtException("unterminated compound");
                char d = s.charAt(pos++);
                if (d == '}') break;
                if (d != ',') throw new SnbtException("expected ',' or '}' in compound at " + (pos - 1));
            }
            return new TCompound(map);
        }

        Tag parseListOrArray(int depth) {
            expect('[');
            skipWs();
            // Typed arrays [B;..] [I;..] [L;..]
            if (pos + 1 < s.length()
                    && (s.charAt(pos) == 'B' || s.charAt(pos) == 'I' || s.charAt(pos) == 'L')
                    && s.charAt(pos + 1) == ';') {
                char kind = s.charAt(pos);
                pos += 2;
                List<String> raw = new ArrayList<>();
                skipWs();
                if (peek(']')) {
                    pos++;
                    return emptyArray(kind);
                }
                while (true) {
                    if (++nodes > MAX_NODES) throw new SnbtException("SNBT exceeds max nodes");
                    int start = pos;
                    while (!eof() && s.charAt(pos) != ',' && s.charAt(pos) != ']') pos++;
                    if (eof()) throw new SnbtException("unterminated typed array");
                    raw.add(s.substring(start, pos).trim());
                    skipWs();
                    char d = s.charAt(pos++);
                    if (d == ']') break;
                    if (d != ',') throw new SnbtException("expected ',' or ']' in typed array");
                    skipWs();
                }
                return buildArray(kind, raw);
            }
            skipWs();
            if (peek(']')) {
                pos++;
                return new TList(List.of());
            }
            List<Tag> items = new ArrayList<>();
            while (true) {
                // Trailing commas are rejected (canonical form never emits them).
                skipWs();
                if (peek(']')) throw new SnbtException("trailing comma in list");
                items.add(parseTag(depth + 1));
                skipWs();
                if (eof()) throw new SnbtException("unterminated list");
                char d = s.charAt(pos++);
                if (d == ']') break;
                if (d != ',') throw new SnbtException("expected ',' or ']' in list at " + (pos - 1));
            }
            return new TList(List.copyOf(items));
        }

        String parseQuoted() {
            char quote = s.charAt(pos++);
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new SnbtException("unterminated string");
                char c = s.charAt(pos++);
                if (c == quote) break;
                if (c == '\\') {
                    if (eof()) throw new SnbtException("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\'' -> sb.append('\'');
                        case '\\' -> sb.append('\\');
                        case 'b' -> sb.append('\b');
                        case 's' -> sb.append(' ');
                        case 't' -> sb.append('\t');
                        case 'n' -> sb.append('\n');
                        case 'f' -> sb.append('\f');
                        case 'r' -> sb.append('\r');
                        case 'x' -> sb.append((char) parseHex(2));
                        case 'u' -> sb.append((char) parseHex(4));
                        case 'U' -> sb.appendCodePoint(parseHex(8));
                        case 'N' -> sb.append(parseNamedEscape());
                        default -> throw new SnbtException("unknown escape: \\" + e);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        int parseHex(int digits) {
            if (pos + digits > s.length()) throw new SnbtException("truncated hex escape");
            int v = 0;
            for (int i = 0; i < digits; i++) {
                int d = Character.digit(s.charAt(pos++), 16);
                if (d < 0) throw new SnbtException("invalid hex escape");
                v = (v << 4) | d;
            }
            return v;
        }

        String parseNamedEscape() {
            // \N{name} — accept the braces, resolve nothing: the name must be
            // a valid non-empty identifier; full Unicode-name resolution is out
            // of scope for the v1 codec (fail closed on empty/garbage).
            expect('{');
            int start = pos;
            while (!eof() && s.charAt(pos) != '}') pos++;
            if (eof()) throw new SnbtException("unterminated \\N{} escape");
            String name = s.substring(start, pos);
            pos++; // consume '}'
            if (name.isEmpty()
                    || !name.chars().allMatch(ch -> ch == ' ' || ch == '-' || Character.isLetterOrDigit(ch))) {
                throw new SnbtException("invalid \\N{} escape name: " + name);
            }
            throw new SnbtException("\\N{name} escapes have no canonical mapping in " + CONTRACT_VERSION);
        }

        String parseUnquotedKey() {
            int start = pos;
            while (!eof()) {
                char c = s.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+') pos++;
                else break;
            }
            if (start == pos) throw new SnbtException("expected compound key at " + pos);
            return s.substring(start, pos);
        }

        Tag parseScalar() {
            int start = pos;
            while (!eof()) {
                char c = s.charAt(pos);
                if (c == ',' || c == ']' || c == '}' || c == ':' || Character.isWhitespace(c)) break;
                pos++;
            }
            String tok = s.substring(start, pos);
            if (tok.isEmpty()) throw new SnbtException("expected value at " + pos);
            return scalarToken(tok);
        }

        Tag scalarToken(String tok) {
            // Booleans are bytes in NBT (via NbtOps): true -> 1b, false -> 0b.
            if (tok.equals("true")) return new TByte((byte) 1);
            if (tok.equals("false")) return new TByte((byte) 0);
            // Non-finite tokens are never values (fail closed, never bare strings).
            if (tok.equals("NaN") || tok.equals("Infinity") || tok.equals("-Infinity") || tok.equals("+Infinity")) {
                throw new SnbtException("non-finite value has no SNBT form: " + tok);
            }
            // Two-letter unsigned byte suffix first (e.g. 255ub).
            if (tok.length() > 2 && (tok.endsWith("ub") || tok.endsWith("UB"))) {
                String digits = tok.substring(0, tok.length() - 2);
                return new TByte(Byte.parseByte(unsignedByteToSigned(digits)));
            }
            int len = tok.length();
            char last = tok.charAt(len - 1);
            try {
                if ((last == 'b' || last == 'B') && len > 1) {
                    return new TByte(Byte.parseByte(unsignedToSigned(tok.substring(0, len - 1), 8)));
                }
                if ((last == 's' || last == 'S') && len > 1) {
                    return new TShort(Short.parseShort(unsignedToSigned(tok.substring(0, len - 1), 16)));
                }
                if ((last == 'l' || last == 'L') && len > 1) {
                    return new TLong(parseLongChecked(tok.substring(0, len - 1)));
                }
                if ((last == 'f' || last == 'F') && len > 1) {
                    float f = Float.parseFloat(tok.substring(0, len - 1));
                    if (!Float.isFinite(f)) throw new SnbtException("non-finite float: " + tok);
                    return new TFloat(f);
                }
                if ((last == 'd' || last == 'D') && len > 1) {
                    double d = Double.parseDouble(tok.substring(0, len - 1));
                    if (!Double.isFinite(d)) throw new SnbtException("non-finite double: " + tok);
                    return new TDouble(d);
                }
                // Suffix-less: integer if it parses as int/long, double if it has ./e.
                if (tok.contains(".") || tok.contains("e") || tok.contains("E")) {
                    double d = Double.parseDouble(tok);
                    if (!Double.isFinite(d)) throw new SnbtException("non-finite double: " + tok);
                    return new TDouble(d);
                }
                try {
                    return new TInt(Integer.parseInt(stripSeparators(tok)));
                } catch (NumberFormatException notInt) {
                    return new TLong(parseLongChecked(tok));
                }
            } catch (SnbtException e) {
                throw e;
            } catch (RuntimeException e) {
                // Not a number -> bare string (unquoted). Canonical writer always
                // quotes strings, but the parser accepts bare words for compat.
                return new TString(tok);
            }
        }

        void expect(char c) {
            skipWs();
            if (eof() || s.charAt(pos) != c) throw new SnbtException("expected '" + c + "' at " + pos);
            pos++;
        }

        boolean peek(char c) {
            skipWs();
            return !eof() && s.charAt(pos) == c;
        }
    }

    private static Tag emptyArray(char kind) {
        return switch (kind) {
            case 'B' -> new TByteArray(new byte[0]);
            case 'I' -> new TIntArray(new int[0]);
            case 'L' -> new TLongArray(new long[0]);
            default -> throw new SnbtException("unknown array kind: " + kind);
        };
    }

    private static Tag buildArray(char kind, List<String> raw) {
        try {
            return switch (kind) {
                case 'B' -> {
                    byte[] out = new byte[raw.size()];
                    for (int i = 0; i < raw.size(); i++) out[i] = parseByteElement(raw.get(i));
                    yield new TByteArray(out);
                }
                case 'I' -> {
                    int[] out = new int[raw.size()];
                    for (int i = 0; i < raw.size(); i++) out[i] = parseIntElement(raw.get(i));
                    yield new TIntArray(out);
                }
                case 'L' -> {
                    long[] out = new long[raw.size()];
                    for (int i = 0; i < raw.size(); i++) out[i] = parseLongElement(raw.get(i));
                    yield new TLongArray(out);
                }
                default -> throw new SnbtException("unknown array kind: " + kind);
            };
        } catch (SnbtException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SnbtException("invalid typed array element: " + e.getMessage());
        }
    }

    private static String stripNumberSuffix(String num, char... suffixes) {
        String n = stripSeparators(num).trim();
        // Two-letter unsigned byte suffix.
        if (n.length() > 2 && (n.endsWith("ub") || n.endsWith("UB"))) {
            return n.substring(0, n.length() - 2);
        }
        if (!n.isEmpty()) {
            char last = n.charAt(n.length() - 1);
            for (char s : suffixes) {
                if ((last == s || last == Character.toUpperCase(s) || last == Character.toLowerCase(s))
                        && n.length() > 1) {
                    return n.substring(0, n.length() - 1);
                }
            }
        }
        return n;
    }

    private static byte parseByteElement(String num) {
        String n = num.trim();
        if (n.length() > 2 && (n.endsWith("ub") || n.endsWith("UB"))) {
            return Byte.parseByte(unsignedByteToSigned(n.substring(0, n.length() - 2)));
        }
        return Byte.parseByte(unsignedToSigned(stripNumberSuffix(n, 'b'), 8));
    }

    private static int parseIntElement(String num) {
        return parseIntChecked(stripNumberSuffix(num.trim(), 'i'));
    }

    private static long parseLongElement(String num) {
        return parseLongChecked(stripNumberSuffix(num.trim(), 'l'));
    }

    private static String unsignedByteToSigned(String digits) {
        String d = stripSeparators(digits).trim();
        try {
            int v = Integer.parseInt(d);
            if (v < 0 || v > 255) throw new SnbtException("ub out of range: " + digits + "ub");
            return Byte.toString((byte) v);
        } catch (NumberFormatException e) {
            throw new SnbtException("invalid number: " + digits + "ub");
        }
    }

    private static String stripSeparators(String num) {
        if (num.indexOf('_') < 0) return num;
        StringBuilder sb = new StringBuilder(num.length());
        for (int i = 0; i < num.length(); i++) {
            char c = num.charAt(i);
            if (c != '_') sb.append(c);
        }
        return sb.toString();
    }

    private static String unsignedToSigned(String num, int bits) {
        String n = stripSeparators(num);
        boolean ub = n.endsWith("ub") || n.endsWith("UB");
        if (!ub) return n;
        String digits = n.substring(0, n.length() - 2);
        try {
            if (bits == 8) {
                int v = Integer.parseInt(digits);
                if (v < 0 || v > 255) throw new SnbtException("ub out of range: " + num);
                return Byte.toString((byte) v);
            }
            throw new SnbtException("unsupported unsigned suffix: " + num);
        } catch (NumberFormatException e) {
            throw new SnbtException("invalid number: " + num);
        }
    }

    private static int parseIntChecked(String num) {
        String n = stripSeparators(num);
        if (n.startsWith("0x") || n.startsWith("0X")) return Integer.parseUnsignedInt(n.substring(2), 16);
        if (n.startsWith("0b") || n.startsWith("0B")) return Integer.parseUnsignedInt(n.substring(2), 2);
        if (n.startsWith("0") && n.length() > 1 && n.chars().skip(1).allMatch(Character::isDigit)) {
            return Integer.parseUnsignedInt(n.substring(1), 8);
        }
        return Integer.parseInt(n);
    }

    private static long parseLongChecked(String num) {
        String n = stripSeparators(num);
        if (n.startsWith("0x") || n.startsWith("0X")) return Long.parseUnsignedLong(n.substring(2), 16);
        if (n.startsWith("0b") || n.startsWith("0B")) return Long.parseUnsignedLong(n.substring(2), 2);
        if (n.startsWith("0") && n.length() > 1 && n.chars().skip(1).allMatch(Character::isDigit)) {
            return Long.parseUnsignedLong(n.substring(1), 8);
        }
        return Long.parseLong(n);
    }

    // ------------------------------------------------------------------
    // Domain-separated digests (SHA-256 over UTF-8 canonical bytes)
    // ------------------------------------------------------------------

    public static String sha256Hex(String domain, String canonicalSnbt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(domain.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(CONTRACT_VERSION.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(canonicalSnbt.getBytes(StandardCharsets.UTF_8));
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Exact item content (id + full count-independent component SNBT). */
    public static String contentDigest(String canonicalItemSnbt) {
        return sha256Hex("content-v1", canonicalItemSnbt);
    }

    /** Stack = variant + count (slot-independent). */
    public static String stackDigest(String canonicalItemSnbt, int count) {
        return sha256Hex("stack-v1", canonicalItemSnbt + "\ncount:" + count);
    }

    /** Snapshot = ordered slot list + capacity. */
    public static String snapshotDigest(String canonicalSlotsSnbt) {
        return sha256Hex("snapshot-v1", canonicalSlotsSnbt);
    }

    /** Slot-path proof (source + path + snapshot digest). */
    public static String slotProofDigest(String source, String path, String snapshotDigest) {
        return sha256Hex("slot-proof-v1", source + "\n" + path + "\n" + snapshotDigest);
    }
}
