package com.example.helloworld;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class ClassificationCache {

    private static final String TAG = "ClassCache";

    private final File file;
    private final Map<String, Set<String>> data = new HashMap<>();

    public ClassificationCache(Context ctx) {
        file = new File(ctx.getFilesDir(), "song_scenes.json");
        load();
    }

    public synchronized Set<String> get(String path) {
        Set<String> s = data.get(path);
        return s == null ? null : Collections.unmodifiableSet(s);
    }

    public synchronized boolean has(String path) {
        return data.containsKey(path);
    }

    public synchronized void put(String path, Set<String> scenes) {
        data.put(path, scenes == null ? new HashSet<>() : new HashSet<>(scenes));
        save();
    }

    public synchronized void putBatch(Map<String, Set<String>> batch) {
        for (Map.Entry<String, Set<String>> e : batch.entrySet()) {
            data.put(e.getKey(), e.getValue() == null ? new HashSet<>() : new HashSet<>(e.getValue()));
        }
        save();
    }

    public synchronized int size() {
        return data.size();
    }

    public synchronized void clear() {
        data.clear();
        if (file.exists()) file.delete();
    }

    public synchronized void retainOnly(Set<String> keysToKeep) {
        if (keysToKeep == null || keysToKeep.isEmpty()) {
            clear();
            return;
        }
        boolean changed = data.keySet().retainAll(keysToKeep);
        if (changed) save();
    }

    public synchronized void renameTag(String oldTag, String newTag) {
        if (oldTag == null || newTag == null || oldTag.equals(newTag)) return;
        boolean changed = false;
        for (Set<String> scenes : data.values()) {
            if (scenes.remove(oldTag)) {
                scenes.add(newTag);
                changed = true;
            }
        }
        if (changed) save();
    }

    public synchronized void removeTag(String tag) {
        if (tag == null) return;
        boolean changed = false;
        for (Set<String> scenes : data.values()) {
            if (scenes.remove(tag)) changed = true;
        }
        if (changed) save();
    }

    private void load() {
        if (!file.exists()) return;
        try {
            byte[] bytes;
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
                bytes = baos.toByteArray();
            }
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray arr = root.optJSONArray(key);
                if (arr == null) continue;
                Set<String> scenes = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, null);
                    if (s != null) scenes.add(s);
                }
                data.put(key, scenes);
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
        }
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, Set<String>> e : data.entrySet()) {
                root.put(e.getKey(), new JSONArray(e.getValue()));
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | org.json.JSONException e) {
            Log.w(TAG, "save failed", e);
        }
    }
}
