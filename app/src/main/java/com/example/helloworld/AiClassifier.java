package com.example.helloworld;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AiClassifier {

    private static final String TAG = "AiClassifier";

    public static final List<String> BUILTIN_SCENES = Arrays.asList(
            "跑步", "冥想");

    private static final Map<String, String> SCENE_DESCRIPTIONS;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("跑步", "节奏快有动感（BPM 130+）");
        m.put("散步", "节奏中等放松");
        m.put("专注", "安静柔和或纯音乐不抢戏");
        m.put("冥想", "极慢氛围 / 纯乐");
        m.put("健身", "高能量节奏强力量感");
        m.put("睡前", "温柔安静助眠");
        SCENE_DESCRIPTIONS = Collections.unmodifiableMap(m);
    }

    public enum Provider {
        ZHIPU("智谱 GLM-4-Flash", "免费",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "glm-4-flash",
                "https://open.bigmodel.cn/usercenter/proj-mgmt/apikeys"),
        MOONSHOT("Moonshot Kimi", "付费",
                "https://api.moonshot.cn/v1/chat/completions",
                "moonshot-v1-8k",
                "https://platform.moonshot.cn/console/api-keys"),
        DEEPSEEK("DeepSeek", "便宜",
                "https://api.deepseek.com/v1/chat/completions",
                "deepseek-chat",
                "https://platform.deepseek.com/api_keys"),
        QWEN("通义千问 Qwen", "有免费额度",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "qwen-turbo",
                "https://dashscope.console.aliyun.com/apiKey"),
        SILICONFLOW("硅基流动 SiliconFlow", "有免费模型",
                "https://api.siliconflow.cn/v1/chat/completions",
                "Qwen/Qwen2.5-7B-Instruct",
                "https://cloud.siliconflow.cn/account/ak"),
        OPENROUTER("OpenRouter", "聚合 (含免费模型)",
                "https://openrouter.ai/api/v1/chat/completions",
                "meta-llama/llama-3.3-70b-instruct:free",
                "https://openrouter.ai/keys"),
        CUSTOM("自定义 OpenAI 兼容", "需手填 URL / 模型",
                null, null, null);

        public final String label;
        public final String pricing;
        public final String url;
        public final String model;
        public final String signupUrl;

        Provider(String label, String pricing, String url, String model, String signupUrl) {
            this.label = label;
            this.pricing = pricing;
            this.url = url;
            this.model = model;
            this.signupUrl = signupUrl;
        }
    }

    public static class Item {
        public final String key;
        public final String title;
        public final String artist;

        public Item(String key, String title, String artist) {
            this.key = key;
            this.title = title;
            this.artist = artist;
        }
    }

    public interface BatchCallback {
        void onResult(Map<String, Set<String>> results, String error);
    }

    public interface TestCallback {
        void onResult(boolean ok, String message);
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final String url;
    private final String model;
    private final String apiKey;
    private final List<String> sceneNames;
    private final String tag;

    public AiClassifier(String url, String model, String apiKey, List<String> sceneNames, String tag) {
        this.url = url;
        this.model = model;
        this.apiKey = apiKey;
        this.sceneNames = sceneNames == null || sceneNames.isEmpty()
                ? new ArrayList<>(BUILTIN_SCENES)
                : new ArrayList<>(sceneNames);
        this.tag = tag == null ? "" : tag;
    }

    public String tag() { return tag; }

    public void test(TestCallback cb) {
        exec.execute(() -> {
            try {
                if (url == null || url.isEmpty() || model == null || model.isEmpty()) {
                    throw new Exception("URL/模型未配置");
                }
                if (apiKey == null || apiKey.isEmpty()) {
                    throw new Exception("Key 为空");
                }
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("temperature", 0);
                body.put("max_tokens", 5);
                JSONArray messages = new JSONArray();
                JSONObject msg = new JSONObject();
                msg.put("role", "user");
                msg.put("content", "ping");
                messages.put(msg);
                body.put("messages", messages);

                long t0 = System.currentTimeMillis();
                String resp = httpPost(url, body.toString());
                long ms = System.currentTimeMillis() - t0;

                JSONObject root = new JSONObject(resp);
                JSONArray choices = root.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    throw new Exception("响应无 choices: "
                            + (resp.length() > 200 ? resp.substring(0, 200) + "…" : resp));
                }
                String content = choices.getJSONObject(0)
                        .optJSONObject("message")
                        .optString("content", "");
                if (content.length() > 40) content = content.substring(0, 40) + "…";
                final String preview = content.replaceAll("\\s+", " ").trim();
                final String okMsg = ms + " ms · 返回: " + (preview.isEmpty() ? "(空)" : preview);
                ui.post(() -> cb.onResult(true, okMsg));
            } catch (Exception e) {
                String em = e.getMessage();
                if (em == null) em = e.getClass().getSimpleName();
                final String errMsg = em.length() > 200 ? em.substring(0, 200) + "…" : em;
                ui.post(() -> cb.onResult(false, errMsg));
            }
        });
    }

    public void classifyBatch(List<Item> items, BatchCallback cb) {
        exec.execute(() -> {
            try {
                Map<String, Set<String>> results = doClassify(items);
                ui.post(() -> cb.onResult(results, null));
            } catch (Exception e) {
                Log.w(TAG, tag + " classify failed", e);
                ui.post(() -> cb.onResult(Collections.emptyMap(), e.getMessage()));
            }
        });
    }

    private Map<String, Set<String>> doClassify(List<Item> items) throws Exception {
        if (items.isEmpty()) return Collections.emptyMap();
        if (url == null || url.isEmpty() || model == null || model.isEmpty()) {
            throw new Exception("URL/模型未配置");
        }

        StringBuilder userMsg = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            userMsg.append(i + 1).append(". ").append(it.title == null ? "" : it.title);
            if (it.artist != null && !it.artist.isEmpty()) {
                userMsg.append("  -  ").append(it.artist);
            }
            userMsg.append("\n");
        }

        StringBuilder sceneList = new StringBuilder();
        for (int i = 0; i < sceneNames.size(); i++) {
            if (i > 0) sceneList.append("、");
            sceneList.append(sceneNames.get(i));
        }

        StringBuilder descLine = new StringBuilder();
        StringBuilder customHint = new StringBuilder();
        for (String s : sceneNames) {
            String desc = SCENE_DESCRIPTIONS.get(s);
            if (desc != null) {
                if (descLine.length() > 0) descLine.append("，");
                descLine.append(s).append("=").append(desc);
            } else {
                if (customHint.length() == 0) {
                    customHint.append("\n额外的自定义场景（根据场景名常识判断）：");
                } else {
                    customHint.append("、");
                }
                customHint.append(s);
            }
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.3);

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content",
                "你是音乐场景分类助手。用户会给你一批歌曲（每行：序号. 歌名 - 歌手），" +
                "请判断每首适合哪些场景。可选场景（必须只从下列中选）：" +
                sceneList + "。\n" +
                (descLine.length() > 0 ? "判断标准：" + descLine + "。" : "") +
                customHint + "\n" +
                "每首可以多选（一般 1-3 个）。如果你完全不熟这首歌，宁可空数组也不要乱猜。\n" +
                "只输出一个 JSON 对象，严格遵循格式：" +
                "{\"r\":[{\"i\":1,\"s\":[\"" + (sceneNames.isEmpty() ? "跑步" : sceneNames.get(0)) +
                "\"]},{\"i\":2,\"s\":[]}]}\n" +
                "其中 i 是输入的序号，s 是适合的场景数组。不要输出 markdown 代码块、不要解释，只输出 JSON。");
        messages.put(sys);

        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", userMsg.toString());
        messages.put(user);

        body.put("messages", messages);

        try {
            JSONObject rf = new JSONObject();
            rf.put("type", "json_object");
            body.put("response_format", rf);
        } catch (Exception ignored) {
        }

        String resp = httpPost(url, body.toString());
        JSONObject root = new JSONObject(resp);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("响应无 choices: " + resp);
        }
        String content = choices.getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "");
        String jsonText = extractJson(content);
        JSONObject parsed = new JSONObject(jsonText);
        JSONArray r = parsed.optJSONArray("r");
        if (r == null) r = parsed.optJSONArray("results");
        if (r == null) return Collections.emptyMap();

        Map<String, Set<String>> map = new HashMap<>();
        for (int i = 0; i < r.length(); i++) {
            JSONObject o = r.optJSONObject(i);
            if (o == null) continue;
            int idx = o.optInt("i", -1) - 1;
            if (idx < 0 || idx >= items.size()) continue;
            JSONArray s = o.optJSONArray("s");
            if (s == null) s = o.optJSONArray("scenes");
            Set<String> scenes = new HashSet<>();
            if (s != null) {
                for (int j = 0; j < s.length(); j++) {
                    String name = s.optString(j, "").trim();
                    if (sceneNames.contains(name)) scenes.add(name);
                }
            }
            map.put(items.get(idx).key, scenes);
        }
        for (Item it : items) {
            if (!map.containsKey(it.key)) {
                map.put(it.key, Collections.emptySet());
            }
        }
        return map;
    }

    private static String extractJson(String content) {
        if (content == null) return "{}";
        String s = content.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            int fence = s.lastIndexOf("```");
            if (fence > 0) s = s.substring(0, fence);
            s = s.trim();
        }
        int lb = s.indexOf('{');
        int rb = s.lastIndexOf('}');
        if (lb >= 0 && rb > lb) s = s.substring(lb, rb + 1);
        return s;
    }

    private String httpPost(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            }
            String resp = baos.toString("UTF-8");
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " - " + resp);
            }
            return resp;
        } finally {
            c.disconnect();
        }
    }
}
