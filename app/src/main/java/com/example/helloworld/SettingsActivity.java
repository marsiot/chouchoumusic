package com.example.helloworld;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences sp;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = Providers.prefs(this);
        Providers.migrateLegacyPrefs(sp);

        container = findViewById(R.id.providersContainer);

        TextView intro = new TextView(this);
        intro.setText("启用多个服务商可在前一个调用失败时自动切到下一个。Key 仅保存在本机，不会上传。");
        intro.setTextColor(0x99FFFFFF & 0x99FFFFFF | 0xFF000000);
        intro.setTextColor(Color.parseColor("#FF666666"));
        intro.setTextSize(13f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.bottomMargin = dp(12);
        intro.setLayoutParams(introLp);
        container.addView(intro);

        for (AiClassifier.Provider p : AiClassifier.Provider.values()) {
            container.addView(buildProviderCard(p));
        }

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private View buildProviderCard(AiClassifier.Provider p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#FFFFFFFF"));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(p.label);
        label.setTextColor(Color.parseColor("#FF222222"));
        label.setTextSize(16f);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.weight = 1;
        label.setLayoutParams(labelLp);
        titleRow.addView(label);

        Switch sw = new Switch(this);
        sw.setChecked(Providers.isEnabled(sp, p));
        titleRow.addView(sw);

        card.addView(titleRow);

        TextView sub = new TextView(this);
        String modelText = p == AiClassifier.Provider.CUSTOM
                ? (p.pricing)
                : (p.pricing + " · " + p.model);
        sub.setText(modelText);
        sub.setTextColor(Color.parseColor("#FF888888"));
        sub.setTextSize(12f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        card.addView(sub);

        final EditText urlInput;
        final EditText modelInput;
        if (p == AiClassifier.Provider.CUSTOM) {
            urlInput = makeField("Endpoint URL（OpenAI 兼容）",
                    "https://your-host/v1/chat/completions",
                    Providers.getCustomUrl(sp), InputType.TYPE_TEXT_VARIATION_URI);
            urlInput.addTextChangedListener(new SimpleWatcher(s ->
                    Providers.setCustomUrl(sp, s)));
            card.addView(urlInput);

            modelInput = makeField("模型名", "gpt-4o-mini",
                    Providers.getCustomModel(sp), InputType.TYPE_CLASS_TEXT);
            modelInput.addTextChangedListener(new SimpleWatcher(s ->
                    Providers.setCustomModel(sp, s)));
            card.addView(modelInput);
        } else {
            urlInput = null;
            modelInput = null;
        }

        final EditText keyInput = makeField("API Key", "sk-…",
                Providers.getKey(sp, p), InputType.TYPE_CLASS_TEXT);
        keyInput.addTextChangedListener(new SimpleWatcher(s ->
                Providers.setKey(sp, p, s)));
        card.addView(keyInput);

        if (p.signupUrl != null) {
            LinearLayout helpRow = new LinearLayout(this);
            helpRow.setOrientation(LinearLayout.HORIZONTAL);
            helpRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            helpLp.topMargin = dp(6);
            helpRow.setLayoutParams(helpLp);

            TextView linkTv = new TextView(this);
            linkTv.setText("→ 去注册 / 获取 Key");
            linkTv.setTextColor(Color.parseColor("#FF3F51B5"));
            linkTv.setTextSize(13f);
            LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            linkLp.weight = 1;
            linkTv.setLayoutParams(linkLp);
            linkTv.setOnClickListener(v -> openUrl(p.signupUrl));
            helpRow.addView(linkTv);

            TextView hostTv = new TextView(this);
            hostTv.setText("(" + extractHost(p.signupUrl) + ")");
            hostTv.setTextColor(Color.parseColor("#FF888888"));
            hostTv.setTextSize(11f);
            helpRow.addView(hostTv);

            card.addView(helpRow);
        }

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams testRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testRowLp.topMargin = dp(8);
        testRow.setLayoutParams(testRowLp);

        final Button btnTest = new Button(this);
        btnTest.setText("测试连接");
        btnTest.setMinWidth(0);
        testRow.addView(btnTest);

        final TextView resultTv = new TextView(this);
        resultTv.setTextSize(12f);
        resultTv.setTextColor(Color.parseColor("#FF666666"));
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        resultLp.weight = 1;
        resultLp.leftMargin = dp(8);
        resultTv.setLayoutParams(resultLp);
        testRow.addView(resultTv);

        card.addView(testRow);

        btnTest.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            if (k.isEmpty()) {
                resultTv.setText("请先填 Key");
                resultTv.setTextColor(Color.parseColor("#FFC62828"));
                return;
            }
            String u = (p == AiClassifier.Provider.CUSTOM && urlInput != null)
                    ? urlInput.getText().toString().trim() : p.url;
            String m = (p == AiClassifier.Provider.CUSTOM && modelInput != null)
                    ? modelInput.getText().toString().trim() : p.model;
            if (u == null || u.isEmpty() || m == null || m.isEmpty()) {
                resultTv.setText("URL/模型不能为空");
                resultTv.setTextColor(Color.parseColor("#FFC62828"));
                return;
            }
            btnTest.setEnabled(false);
            btnTest.setText("测试中…");
            resultTv.setText("等待响应…");
            resultTv.setTextColor(Color.parseColor("#FF666666"));

            AiClassifier tester = new AiClassifier(u, m, k,
                    AiClassifier.BUILTIN_SCENES, p.label);
            tester.test((ok, msg) -> {
                btnTest.setEnabled(true);
                btnTest.setText("测试连接");
                if (ok) {
                    resultTv.setText("✓ " + msg);
                    resultTv.setTextColor(Color.parseColor("#FF2E7D32"));
                } else {
                    resultTv.setText("✗ " + msg);
                    resultTv.setTextColor(Color.parseColor("#FFC62828"));
                }
            });
        });

        sw.setOnCheckedChangeListener((CompoundButton btn, boolean checked) ->
                Providers.setEnabled(sp, p, checked));

        return card;
    }

    private EditText makeField(String hint, String exampleHint, String value, int inputType) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);

        EditText et = new EditText(this);
        et.setHint(exampleHint == null ? hint : exampleHint);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setText(value == null ? "" : value);
        et.setTextColor(Color.parseColor("#FF222222"));
        et.setHintTextColor(Color.parseColor("#FFBBBBBB"));
        et.setTextSize(13f);
        et.setLayoutParams(lp);
        return et;
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器：" + url, Toast.LENGTH_LONG).show();
        }
    }

    private static String extractHost(String url) {
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private interface ChangeSink {
        void accept(String value);
    }

    private static class SimpleWatcher implements TextWatcher {
        private final ChangeSink sink;
        SimpleWatcher(ChangeSink sink) { this.sink = sink; }
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { sink.accept(s.toString().trim()); }
    }
}
