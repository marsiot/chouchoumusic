package com.example.helloworld;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LyricsFetcher {

    private static final String TAG = "LyricsFetcher";

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/16.0 Safari/605.1.15";

    public interface Callback {
        void onResult(List<LrcParser.Line> lines, String source, String error);
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File cacheDir;

    public LyricsFetcher(Context ctx) {
        cacheDir = new File(ctx.getCacheDir(), "lyrics");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    public void fetchOverride(String fileName, String folderName,
                              String overrideTitle, String overrideArtist, Callback cb) {
        exec.execute(() -> {
            try {
                Result r = doFetchOverride(fileName, folderName, overrideTitle, overrideArtist);
                ui.post(() -> cb.onResult(r.lines, r.source, null));
            } catch (Exception e) {
                Log.w(TAG, "fetchOverride failed for " + fileName, e);
                ui.post(() -> cb.onResult(Collections.emptyList(), null, e.getMessage()));
            }
        });
    }

    private Result doFetchOverride(String fileName, String folderName,
                                   String title, String artist) throws Exception {
        String[] auto = extractTitleArtist(fileName, folderName);
        String cacheKey = sha1(queryKey(auto[0], auto[1]));
        File cache = new File(cacheDir, cacheKey + ".lrc");

        if (title == null || title.isEmpty()) {
            return new Result(Collections.emptyList(), null);
        }

        long songId = searchNeteaseSongId(title, artist);
        if (songId <= 0) return new Result(Collections.emptyList(), null);

        String lrcText = fetchNeteaseLyric(songId);
        if (lrcText == null || lrcText.isEmpty()) {
            return new Result(Collections.emptyList(), null);
        }

        File tmp = new File(cacheDir, cacheKey + ".lrc.tmp");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
            fos.write(lrcText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (cache.exists()) cache.delete();
        tmp.renameTo(cache);

        return new Result(LrcParser.parseText(lrcText), "网易云");
    }

    public void fetch(String fileName, String folderName, Callback cb) {
        exec.execute(() -> {
            try {
                Result r = doFetch(fileName, folderName);
                ui.post(() -> cb.onResult(r.lines, r.source, null));
            } catch (Exception e) {
                Log.w(TAG, "fetch failed for " + fileName, e);
                ui.post(() -> cb.onResult(Collections.emptyList(), null, e.getMessage()));
            }
        });
    }

    private static class Result {
        final List<LrcParser.Line> lines;
        final String source;
        Result(List<LrcParser.Line> l, String s) { lines = l; source = s; }
    }

    private Result doFetch(String fileName, String folderName) throws Exception {
        String[] preTa = extractTitleArtist(fileName, folderName);
        String key = sha1(queryKey(preTa[0], preTa[1]));
        File cache = new File(cacheDir, key + ".lrc");
        if (cache.exists() && cache.length() > 0) {
            List<LrcParser.Line> lines = LrcParser.parse(cache);
            if (!lines.isEmpty()) return new Result(lines, "缓存");
        }

        String title = preTa[0];
        String artist = preTa[1];
        if (title == null || title.isEmpty()) {
            return new Result(Collections.emptyList(), null);
        }

        long songId = searchNeteaseSongId(title, artist);
        if (songId <= 0) return new Result(Collections.emptyList(), null);

        String lrcText = fetchNeteaseLyric(songId);
        if (lrcText == null || lrcText.isEmpty()) {
            return new Result(Collections.emptyList(), null);
        }

        File tmp = new File(cacheDir, key + ".lrc.tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(lrcText.getBytes(StandardCharsets.UTF_8));
        }
        tmp.renameTo(cache);

        return new Result(LrcParser.parseText(lrcText), "网易云");
    }

    private long searchNeteaseSongId(String title, String artist) throws Exception {
        String query = title + (artist != null && !artist.isEmpty() ? " " + artist : "");
        String url = "https://music.163.com/api/search/get?s=" + urlEncode(query)
                + "&type=1&offset=0&limit=10";
        String resp = httpGet(url);
        JSONObject root = new JSONObject(resp);
        JSONObject result = root.optJSONObject("result");
        if (result == null) return 0;
        JSONArray songs = result.optJSONArray("songs");
        if (songs == null || songs.length() == 0) return 0;

        String tLower = title.toLowerCase();
        String aLower = artist == null ? "" : artist.toLowerCase();
        long bestId = 0;
        int bestScore = -1;
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            String name = s.optString("name", "").toLowerCase();
            JSONArray ars = s.optJSONArray("artists");
            String arName = "";
            if (ars != null && ars.length() > 0) {
                arName = ars.optJSONObject(0).optString("name", "").toLowerCase();
            }
            int score = 0;
            if (name.equals(tLower)) score += 10;
            else if (name.contains(tLower) || tLower.contains(name)) score += 5;
            if (!aLower.isEmpty()) {
                if (arName.equals(aLower)) score += 8;
                else if (arName.contains(aLower) || aLower.contains(arName)) score += 4;
            }
            if (score > bestScore) {
                bestScore = score;
                bestId = s.optLong("id", 0);
            }
        }
        if (bestId == 0 && songs.length() > 0) {
            bestId = songs.optJSONObject(0).optLong("id", 0);
        }
        return bestId;
    }

    private String fetchNeteaseLyric(long songId) throws Exception {
        String url = "https://music.163.com/api/song/lyric?id=" + songId
                + "&lv=-1&kv=-1&tv=-1";
        String resp = httpGet(url);
        JSONObject root = new JSONObject(resp);
        JSONObject lrc = root.optJSONObject("lrc");
        if (lrc == null) return null;
        return lrc.optString("lyric", null);
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Referer", "https://music.163.com/");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            if (in == null) return "";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(s.hashCode());
        }
    }

    public static String queryKey(String title, String artist) {
        String t = title == null ? "" : title.toLowerCase().trim().replaceAll("\\s+", " ");
        String a = artist == null ? "" : artist.toLowerCase().trim().replaceAll("\\s+", " ");
        return t + "|" + a;
    }

    static String[] extractTitleArtist(String fileName, String folderName) {
        String base = fileName == null ? "" : fileName;
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        base = base.replaceFirst("^\\s*\\d+\\s*[.\\-_]?\\s*", "").trim();
        int sep = base.indexOf(" - ");
        if (sep > 0) {
            String a = base.substring(0, sep).trim();
            String t = base.substring(sep + 3).trim();
            return new String[]{t, a};
        }
        sep = base.indexOf('-');
        if (sep > 0 && sep < base.length() - 1) {
            String left = base.substring(0, sep).trim();
            String right = base.substring(sep + 1).trim();
            if (!left.isEmpty() && !right.isEmpty()) {
                return new String[]{right, left};
            }
        }
        String artist = folderName;
        if ("(根目录)".equals(artist) || artist == null) artist = null;
        return new String[]{base, artist};
    }
}
