package com.chouchou.music;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class Providers {

    public static final String PREFS = "chouchou_prefs";

    public static final String KEY_ENABLED_PREFIX = "provider_enabled_";
    public static final String KEY_API_KEY_PREFIX = "api_key_";
    public static final String KEY_CUSTOM_URL = "provider_custom_url";
    public static final String KEY_CUSTOM_MODEL = "provider_custom_model";
    public static final String KEY_SCAN_DIR = "scan_root_dir";
    public static final String DEFAULT_SCAN_DIR = "Music";

    public static final String KEY_RESCAN_PENDING = "pending_rescan";
    public static final String KEY_RESCAN_ALL_PENDING = "pending_rescan_all";
    public static final String KEY_SCENE_ORDER_PREFIX = "scene_order_";
    public static final String KEY_FOLDER_ORDER_PREFIX = "folder_order_";
    public static final String KEY_SCENE_COUNT_PREFIX = "scene_count_";
    public static final String KEY_SCENE_EVICTED_PREFIX = "scene_evicted_";

    private Providers() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(SharedPreferences sp, AiClassifier.Provider p) {
        return sp.getBoolean(KEY_ENABLED_PREFIX + p.name(), false);
    }

    public static void setEnabled(SharedPreferences sp, AiClassifier.Provider p, boolean v) {
        sp.edit().putBoolean(KEY_ENABLED_PREFIX + p.name(), v).apply();
    }

    public static String getKey(SharedPreferences sp, AiClassifier.Provider p) {
        return sp.getString(KEY_API_KEY_PREFIX + p.name(), "");
    }

    public static void setKey(SharedPreferences sp, AiClassifier.Provider p, String key) {
        sp.edit().putString(KEY_API_KEY_PREFIX + p.name(), key == null ? "" : key).apply();
    }

    public static String getCustomUrl(SharedPreferences sp) {
        return sp.getString(KEY_CUSTOM_URL, "");
    }

    public static String getCustomModel(SharedPreferences sp) {
        return sp.getString(KEY_CUSTOM_MODEL, "");
    }

    public static void setCustomUrl(SharedPreferences sp, String url) {
        sp.edit().putString(KEY_CUSTOM_URL, url == null ? "" : url).apply();
    }

    public static void setCustomModel(SharedPreferences sp, String model) {
        sp.edit().putString(KEY_CUSTOM_MODEL, model == null ? "" : model).apply();
    }

    public static String getScanDir(SharedPreferences sp) {
        String s = sp.getString(KEY_SCAN_DIR, DEFAULT_SCAN_DIR);
        s = s == null ? DEFAULT_SCAN_DIR : s.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? DEFAULT_SCAN_DIR : s;
    }

    public static List<String> getSceneOrder(SharedPreferences sp, String scene) {
        if (scene == null) return new ArrayList<>();
        String json = sp.getString(KEY_SCENE_ORDER_PREFIX + scene, "[]");
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setSceneOrder(SharedPreferences sp, String scene, List<String> order) {
        if (scene == null) return;
        org.json.JSONArray arr = new org.json.JSONArray(order == null ? new ArrayList<>() : order);
        sp.edit().putString(KEY_SCENE_ORDER_PREFIX + scene, arr.toString()).apply();
    }

    public static List<String> getFolderOrder(SharedPreferences sp, String folder) {
        if (folder == null) return new ArrayList<>();
        String json = sp.getString(KEY_FOLDER_ORDER_PREFIX + folder, "[]");
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setFolderOrder(SharedPreferences sp, String folder, List<String> order) {
        if (folder == null) return;
        org.json.JSONArray arr = new org.json.JSONArray(order == null ? new ArrayList<>() : order);
        sp.edit().putString(KEY_FOLDER_ORDER_PREFIX + folder, arr.toString()).apply();
    }

    public static int getSceneCount(SharedPreferences sp, String scene) {
        if (scene == null) return 0;
        return Math.max(0, sp.getInt(KEY_SCENE_COUNT_PREFIX + scene, 0));
    }

    public static void setSceneCount(SharedPreferences sp, String scene, int count) {
        if (scene == null) return;
        sp.edit().putInt(KEY_SCENE_COUNT_PREFIX + scene, Math.max(0, count)).apply();
    }

    public static java.util.Set<String> getSceneEvicted(SharedPreferences sp, String scene) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (scene == null) return out;
        String json = sp.getString(KEY_SCENE_EVICTED_PREFIX + scene, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, null);
                if (s != null) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setSceneEvicted(SharedPreferences sp, String scene,
                                       java.util.Collection<String> qks) {
        if (scene == null) return;
        org.json.JSONArray arr = new org.json.JSONArray(
                qks == null ? java.util.Collections.emptyList() : qks);
        sp.edit().putString(KEY_SCENE_EVICTED_PREFIX + scene, arr.toString()).apply();
    }

    public static void clearSceneEvicted(SharedPreferences sp, String scene) {
        if (scene == null) return;
        sp.edit().remove(KEY_SCENE_EVICTED_PREFIX + scene).apply();
    }

    public static boolean takeRescanPending(SharedPreferences sp) {
        boolean v = sp.getBoolean(KEY_RESCAN_PENDING, false);
        if (v) sp.edit().remove(KEY_RESCAN_PENDING).apply();
        return v;
    }

    public static void setRescanPending(SharedPreferences sp) {
        sp.edit().putBoolean(KEY_RESCAN_PENDING, true).apply();
    }

    public static boolean takeRescanAllPending(SharedPreferences sp) {
        boolean v = sp.getBoolean(KEY_RESCAN_ALL_PENDING, false);
        if (v) sp.edit().remove(KEY_RESCAN_ALL_PENDING).apply();
        return v;
    }

    public static void setRescanAllPending(SharedPreferences sp) {
        sp.edit().putBoolean(KEY_RESCAN_ALL_PENDING, true).apply();
    }

    public static void setScanDir(SharedPreferences sp, String dir) {
        String s = dir == null ? "" : dir.trim();
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) s = DEFAULT_SCAN_DIR;
        sp.edit().putString(KEY_SCAN_DIR, s).apply();
    }

    public static String resolveUrl(SharedPreferences sp, AiClassifier.Provider p) {
        return p == AiClassifier.Provider.CUSTOM ? getCustomUrl(sp) : p.url;
    }

    public static String resolveModel(SharedPreferences sp, AiClassifier.Provider p) {
        return p == AiClassifier.Provider.CUSTOM ? getCustomModel(sp) : p.model;
    }

    public static boolean isConfigured(SharedPreferences sp, AiClassifier.Provider p) {
        if (!isEnabled(sp, p)) return false;
        if (TextUtils.isEmpty(getKey(sp, p))) return false;
        if (p == AiClassifier.Provider.CUSTOM) {
            return !TextUtils.isEmpty(getCustomUrl(sp))
                    && !TextUtils.isEmpty(getCustomModel(sp));
        }
        return true;
    }

    public static ClassifierPool buildPool(Context ctx, List<String> scenes) {
        SharedPreferences sp = prefs(ctx);
        List<AiClassifier> list = new ArrayList<>();
        for (AiClassifier.Provider p : AiClassifier.Provider.values()) {
            if (!isConfigured(sp, p)) continue;
            String url = resolveUrl(sp, p);
            String model = resolveModel(sp, p);
            String key = getKey(sp, p);
            list.add(new AiClassifier(url, model, key, scenes, p.label));
        }
        return new ClassifierPool(list);
    }

    public static void migrateLegacyPrefs(SharedPreferences sp) {
        String legacy = sp.getString("ai_provider", null);
        if (legacy != null) {
            try {
                AiClassifier.Provider p = AiClassifier.Provider.valueOf(legacy);
                String k = sp.getString(KEY_API_KEY_PREFIX + p.name(), "");
                if (!TextUtils.isEmpty(k)) {
                    sp.edit().putBoolean(KEY_ENABLED_PREFIX + p.name(), true).apply();
                }
            } catch (IllegalArgumentException ignored) {
            }
            sp.edit().remove("ai_provider").apply();
        }
    }
}
