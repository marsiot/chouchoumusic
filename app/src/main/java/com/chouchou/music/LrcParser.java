package com.chouchou.music;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LrcParser {

    public static final class Line {
        public final long timeMs;
        public final String text;

        Line(long t, String s) {
            this.timeMs = t;
            this.text = s;
        }
    }

    private static final Pattern TAG = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");

    private LrcParser() {}

    public static List<Line> parse(File file) {
        if (file == null || !file.exists() || !file.isFile()) return Collections.emptyList();
        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file)) {
            long len = file.length();
            if (len <= 0 || len > 2 * 1024 * 1024) return Collections.emptyList();
            bytes = new byte[(int) len];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        return parseText(decode(bytes));
    }

    public static List<Line> parseText(String content) {
        if (content == null) return Collections.emptyList();
        List<Line> out = new ArrayList<>();
        for (String raw : content.split("\\r?\\n")) {
            Matcher m = TAG.matcher(raw);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (m.find()) {
                if (m.start() != end) {
                    end = m.start();
                    break;
                }
                int min = parseIntSafe(m.group(1));
                int sec = parseIntSafe(m.group(2));
                int ms = 0;
                String frac = m.group(3);
                if (frac != null) {
                    if (frac.length() == 1) ms = parseIntSafe(frac) * 100;
                    else if (frac.length() == 2) ms = parseIntSafe(frac) * 10;
                    else ms = parseIntSafe(frac.substring(0, 3));
                }
                long t = (min * 60L + sec) * 1000L + ms;
                times.add(t);
                end = m.end();
            }
            if (times.isEmpty()) continue;
            String text = raw.substring(end).trim();
            for (long t : times) out.add(new Line(t, text));
        }
        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return out;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String decode(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return dec.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            try {
                return new String(bytes, "GBK");
            } catch (UnsupportedEncodingException ue) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
    }

    public static int findIndex(List<Line> lines, long timeMs) {
        if (lines.isEmpty()) return -1;
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= timeMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}
