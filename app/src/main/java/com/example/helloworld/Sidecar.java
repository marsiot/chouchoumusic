package com.example.helloworld;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Sidecar {

    private static final String TAG = "Sidecar";

    public static class Data {
        public List<LrcParser.Line> lyrics = new ArrayList<>();
        public Set<String> scenes = new LinkedHashSet<>();
        public boolean classified = false;
        public boolean lyricsAttempted = false;
        public boolean manualOverride = false;
        public final List<String> headerLines = new ArrayList<>();
    }

    private static final Pattern TIME_RE = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?\\]");
    private static final Pattern CHOUCHOU_RE = Pattern.compile("\\[chouchou:([^\\]]*)\\]");
    private static final Pattern HEADER_RE = Pattern.compile("\\[([A-Za-z][^:\\]]*):([^\\]]*)\\]");

    private Sidecar() {}

    public static File sidecarFor(String audioPath) {
        if (audioPath == null) return null;
        int dot = audioPath.lastIndexOf('.');
        int slash = Math.max(audioPath.lastIndexOf('/'), audioPath.lastIndexOf('\\'));
        if (dot > slash + 1) {
            return new File(audioPath.substring(0, dot) + ".lrc");
        }
        return new File(audioPath + ".lrc");
    }

    public static Data read(File file) {
        Data d = new Data();
        if (file == null || !file.exists() || !file.isFile()) return d;

        long len = file.length();
        if (len <= 0 || len > 2_097_152) return d;

        byte[] bytes;
        try (FileInputStream in = new FileInputStream(file)) {
            bytes = new byte[(int) len];
            int off = 0;
            while (off < bytes.length) {
                int n = in.read(bytes, off, bytes.length - off);
                if (n < 0) break;
                off += n;
            }
        } catch (IOException e) {
            return d;
        }

        String content = decode(bytes);
        for (String line : content.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            Matcher cm = CHOUCHOU_RE.matcher(line);
            if (cm.find()) {
                parseChouchouSpec(cm.group(1), d);
                continue;
            }
            if (parseTimedLyric(line, d)) continue;
            Matcher hm = HEADER_RE.matcher(line);
            if (hm.matches()) {
                d.headerLines.add(line);
            }
        }
        Collections.sort(d.lyrics, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return d;
    }

    private static boolean parseTimedLyric(String line, Data d) {
        Matcher tm = TIME_RE.matcher(line);
        if (!tm.find() || tm.start() != 0) return false;
        tm.reset();
        List<Long> times = new ArrayList<>();
        int end = 0;
        while (tm.find()) {
            if (tm.start() != end) break;
            int min = parseInt(tm.group(1));
            int sec = parseInt(tm.group(2));
            int ms = 0;
            String frac = tm.group(3);
            if (frac != null) {
                if (frac.length() == 1) ms = parseInt(frac) * 100;
                else if (frac.length() == 2) ms = parseInt(frac) * 10;
                else ms = parseInt(frac.substring(0, 3));
            }
            times.add((min * 60L + sec) * 1000L + ms);
            end = tm.end();
        }
        if (times.isEmpty()) return false;
        String text = line.substring(end).trim();
        for (long t : times) d.lyrics.add(new LrcParser.Line(t, text));
        return true;
    }

    private static void parseChouchouSpec(String spec, Data d) {
        for (String pair : spec.split(";")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            switch (k) {
                case "scenes":
                    d.scenes = new LinkedHashSet<>();
                    if (!v.isEmpty()) {
                        for (String s : v.split(",")) {
                            String t = s.trim();
                            if (!t.isEmpty()) d.scenes.add(t);
                        }
                    }
                    break;
                case "classified":
                    d.classified = "1".equals(v);
                    break;
                case "lyrics":
                    d.lyricsAttempted = "1".equals(v);
                    break;
                case "manual":
                    d.manualOverride = "1".equals(v);
                    break;
            }
        }
    }

    public static boolean write(File file, Data data) {
        if (file == null || data == null) return false;
        File parent = file.getParentFile();
        if (parent == null) return false;
        if (!parent.exists() && !parent.mkdirs()) return false;

        StringBuilder spec = new StringBuilder();
        if (data.classified) {
            spec.append("scenes=");
            boolean first = true;
            for (String s : data.scenes) {
                if (!first) spec.append(",");
                spec.append(s);
                first = false;
            }
            spec.append(";classified=1");
            if (data.manualOverride) spec.append(";manual=1");
        }
        if (data.lyricsAttempted) {
            if (spec.length() > 0) spec.append(";");
            spec.append("lyrics=1");
        }

        StringBuilder sb = new StringBuilder();
        if (spec.length() > 0) {
            sb.append("[chouchou:").append(spec).append("]\n");
        }
        for (String h : data.headerLines) {
            sb.append(h).append("\n");
        }
        for (LrcParser.Line l : data.lyrics) {
            long ms = Math.max(0, l.timeMs);
            long min = ms / 60000;
            long sec = (ms / 1000) % 60;
            long centi = (ms % 1000) / 10;
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]%s\n",
                    min, sec, centi, l.text == null ? "" : l.text));
        }

        File tmp = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (file.exists() && !file.delete()) {
                tmp.delete();
                return false;
            }
            return tmp.renameTo(file);
        } catch (IOException e) {
            Log.w(TAG, "write failed: " + file, e);
            tmp.delete();
            return false;
        }
    }

    private static int parseInt(String s) {
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
}
