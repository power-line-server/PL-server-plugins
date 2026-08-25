package termbridge;

import java.util.*;

/** 极简 JSON 解析/生成（无外部依赖）：对象/数组/字符串/数字/布尔/null */
public final class Json {
    private Json() {}

    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String s) { writeString(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Number n) { sb.append(n.toString()); return; }
        if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
            return;
        }
        writeString(sb, v.toString());
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** 解析 JSON 字符串 → Map/List/String/Double/Boolean/null */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object v = p.parseValue();
        p.skipWs();
        if (!p.eof()) throw new IllegalArgumentException("trailing data at " + p.pos);
        return v;
    }

    private static final class Parser {
        final String s;
        int pos = 0;
        Parser(String s) { this.s = s; }

        boolean eof() { return pos >= s.length(); }
        void skipWs() { while (!eof() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object parseValue() {
            skipWs();
            if (eof()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> { expect("true"); yield Boolean.TRUE; }
                case 'f' -> { expect("false"); yield Boolean.FALSE; }
                case 'n' -> { expect("null"); yield null; }
                default -> parseNumber();
            };
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("bad literal at " + pos);
            pos += lit.length();
        }

        Map<String, Object> parseObject() {
            pos++; // {
            Map<String, Object> m = new LinkedHashMap<>();
            skipWs();
            if (!eof() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (eof() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                m.put(key, parseValue());
                skipWs();
                if (eof()) throw new IllegalArgumentException("unexpected end");
                char c = s.charAt(pos++);
                if (c == ',') continue;
                if (c == '}') break;
                throw new IllegalArgumentException("bad object at " + pos);
            }
            return m;
        }

        List<Object> parseArray() {
            pos++; // [
            List<Object> l = new ArrayList<>();
            skipWs();
            if (!eof() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                l.add(parseValue());
                skipWs();
                if (eof()) throw new IllegalArgumentException("unexpected end");
                char c = s.charAt(pos++);
                if (c == ',') continue;
                if (c == ']') break;
                throw new IllegalArgumentException("bad array at " + pos);
            }
            return l;
        }

        String parseString() {
            if (eof() || s.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c == '\\') {
                    if (eof()) throw new IllegalArgumentException("bad escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("bad unicode");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = pos;
            while (!eof()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            String num = s.substring(start, pos);
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0)
                    return Double.parseDouble(num);
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number at " + start);
            }
        }
    }

    // 便捷访问
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object v) { return (Map<String, Object>) v; }
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object v) { return (List<Object>) v; }
    public static String str(Map<String, Object> m, String key) { Object v = m.get(key); return v == null ? null : String.valueOf(v); }
    public static Long lng(Map<String, Object> m, String key) { Object v = m.get(key); return v instanceof Number n ? n.longValue() : null; }
    public static Boolean bool(Map<String, Object> m, String key) { Object v = m.get(key); return v instanceof Boolean b ? b : null; }
}
