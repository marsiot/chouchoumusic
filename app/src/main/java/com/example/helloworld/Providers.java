package com.example.helloworld;

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
