package com.example.helloworld;

import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClassifierPool {

    private static final String TAG = "ClassifierPool";

    public interface Callback {
        void onResult(Map<String, Set<String>> results, String error);
    }

    private final List<AiClassifier> classifiers;

    public ClassifierPool(List<AiClassifier> classifiers) {
        this.classifiers = classifiers;
    }

    public boolean isEmpty() { return classifiers.isEmpty(); }

    public int size() { return classifiers.size(); }

    public void classifyBatch(List<AiClassifier.Item> items, Callback cb) {
        tryFrom(items, 0, new StringBuilder(), cb);
    }

    private void tryFrom(final List<AiClassifier.Item> items, final int idx,
                         final StringBuilder errors, final Callback cb) {
        if (idx >= classifiers.size()) {
            cb.onResult(Collections.emptyMap(),
                    errors.length() == 0 ? "无可用 AI 服务商" : errors.toString());
            return;
        }
        AiClassifier c = classifiers.get(idx);
        c.classifyBatch(items, (results, error) -> {
            if (error == null) {
                cb.onResult(results, null);
            } else {
                Log.w(TAG, c.tag() + " failed: " + error);
                if (errors.length() > 0) errors.append("\n");
                errors.append(c.tag()).append("：").append(briefError(error));
                tryFrom(items, idx + 1, errors, cb);
            }
        });
    }

    private static String briefError(String e) {
        if (e == null) return "";
        if (e.length() > 120) return e.substring(0, 120) + "…";
        return e;
    }
}
