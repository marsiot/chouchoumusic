package com.chouchou.music;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final int COLOR_BG = 0xFF0A0A0A;
    private static final int COLOR_ELEVATED = 0xFF141414;
    private static final int COLOR_PRIMARY = 0xFFF2F0EC;
    private static final int COLOR_SECONDARY = 0xFF8B847B;
    private static final int COLOR_TERTIARY = 0xFF4A4744;
    private static final int COLOR_ACCENT = 0xFFC9A876;
    private static final int COLOR_DIVIDER = 0xFF1A1A1A;
    private static final int COLOR_OK = 0xFF8FBC8F;
    private static final int COLOR_ERR = 0xFFD08770;

    private SharedPreferences sp;
    private LinearLayout container;
    private TextView scanDirValue;
    private ActivityResultLauncher<Intent> dirPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sp = Providers.prefs(this);
        Providers.migrateLegacyPrefs(sp);

        dirPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String path = result.getData().getStringExtra(
                                DirectoryPickerActivity.RESULT_PATH);
                        Providers.setScanDir(sp, path);
                        if (scanDirValue != null) {
                            scanDirValue.setText("内部存储/" + Providers.getScanDir(sp));
                        }
                    }
                });

        container = findViewById(R.id.providersContainer);
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());

        container.addView(makeSectionTitle("扫描目录", 0));
        container.addView(buildScanDirCard());

        container.addView(makeSectionTitle("AI 服务商", dp(18)));

        TextView intro = new TextView(this);
        intro.setText("启用多个平台时，前一个失败会自动切到下一个。Key 仅保存本机。");
        intro.setTextColor(COLOR_SECONDARY);
        intro.setTextSize(12f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.bottomMargin = dp(12);
        intro.setLayoutParams(introLp);
        container.addView(intro);

        for (AiClassifier.Provider p : AiClassifier.Provider.values()) {
            container.addView(buildProviderCard(p));
        }

        container.addView(makeSectionTitle("扫描歌曲", dp(18)));
        container.addView(buildRescanCard());
    }

    private View buildRescanCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_ELEVATED);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        TextView hint = new TextView(this);
        hint.setText("新加进 /Music 的歌曲会被自动扫到，但 AI 分析需要手动触发。");
        hint.setTextColor(COLOR_SECONDARY);
        hint.setTextSize(11f);
        card.addView(hint);

        Button btnScan = new Button(this);
        btnScan.setText("扫描未分析的歌曲");
        btnScan.setAllCaps(false);
        btnScan.setTextSize(14f);
        btnScan.setTextColor(COLOR_BG);
        btnScan.setBackgroundTintList(ColorStateList.valueOf(COLOR_ACCENT));
        btnScan.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scanLp.topMargin = dp(12);
        btnScan.setLayoutParams(scanLp);
        btnScan.setOnClickListener(v -> {
            Providers.setRescanPending(sp);
            Toast.makeText(this, "返回主页面开始扫描", Toast.LENGTH_SHORT).show();
            finish();
        });
        card.addView(btnScan);

        TextView destruct = new TextView(this);
        destruct.setText("重新分析所有歌曲（清除已有标签）");
        destruct.setTextColor(0xFFD08770);
        destruct.setTextSize(13f);
        destruct.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        destruct.setBackground(ContextCompat.getDrawable(this,
                android.R.drawable.list_selector_background));
        destruct.setClickable(true);
        destruct.setFocusable(true);
        destruct.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams destLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        destLp.topMargin = dp(6);
        destruct.setLayoutParams(destLp);
        destruct.setOnClickListener(v -> confirmRescanAll());
        card.addView(destruct);

        return card;
    }

    private void confirmRescanAll() {
        new AlertDialog.Builder(this)
                .setTitle("重新分析所有歌曲")
                .setMessage("将清除所有歌曲已有的场景标签（手动设置的也会清掉），重新调 AI 重头分析一遍。可能耗时几分钟。")
                .setPositiveButton("确认重新分析", (d, w) -> {
                    Providers.setRescanAllPending(sp);
                    Toast.makeText(this, "返回主页面开始重新分析", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private TextView makeSectionTitle(String text, int topMargin) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_PRIMARY);
        tv.setTextSize(22f);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        lp.bottomMargin = dp(12);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View buildScanDirCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_ELEVATED);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, DirectoryPickerActivity.class);
            i.putExtra(DirectoryPickerActivity.EXTRA_INITIAL, Providers.getScanDir(sp));
            dirPickerLauncher.launch(i);
        });
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        colLp.weight = 1;
        textCol.setLayoutParams(colLp);

        TextView label = new TextView(this);
        label.setText("音乐根目录");
        label.setTextColor(COLOR_PRIMARY);
        label.setTextSize(15f);
        textCol.addView(label);

        scanDirValue = new TextView(this);
        scanDirValue.setText("内部存储/" + Providers.getScanDir(sp));
        scanDirValue.setTextColor(COLOR_ACCENT);
        scanDirValue.setTextSize(12f);
        scanDirValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valLp.topMargin = dp(4);
        scanDirValue.setLayoutParams(valLp);
        textCol.addView(scanDirValue);

        card.addView(textCol);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron);
        LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        chevLp.leftMargin = dp(10);
        chev.setLayoutParams(chevLp);
        card.addView(chev);

        return card;
    }

    private View buildProviderCard(AiClassifier.Provider p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_ELEVATED);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);

        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        card.setLayoutParams(cardLp);

        // Title row: name | pricing | switch
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(p.label);
        label.setTextColor(COLOR_PRIMARY);
        label.setTextSize(15f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.weight = 1;
        label.setLayoutParams(labelLp);
        titleRow.addView(label);

        Switch sw = new Switch(this);
        sw.setChecked(Providers.isEnabled(sp, p));
        tintSwitch(sw);
        titleRow.addView(sw);

        card.addView(titleRow);

        TextView sub = new TextView(this);
        String modelText = p == AiClassifier.Provider.CUSTOM
                ? p.pricing
                : p.pricing + "  ·  " + p.model;
        sub.setText(modelText);
        sub.setTextColor(COLOR_SECONDARY);
        sub.setTextSize(11f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        card.addView(sub);

        final EditText urlInput;
        final EditText modelInput;
        if (p == AiClassifier.Provider.CUSTOM) {
            urlInput = makeField("Endpoint URL", Providers.getCustomUrl(sp),
                    InputType.TYPE_TEXT_VARIATION_URI);
            urlInput.addTextChangedListener(new SimpleWatcher(s ->
                    Providers.setCustomUrl(sp, s)));
            card.addView(urlInput);

            modelInput = makeField("模型名", Providers.getCustomModel(sp),
                    InputType.TYPE_CLASS_TEXT);
            modelInput.addTextChangedListener(new SimpleWatcher(s ->
                    Providers.setCustomModel(sp, s)));
            card.addView(modelInput);
        } else {
            urlInput = null;
            modelInput = null;
        }

        final EditText keyInput = makeField("API Key", Providers.getKey(sp, p),
                InputType.TYPE_CLASS_TEXT);
        keyInput.addTextChangedListener(new SimpleWatcher(s -> Providers.setKey(sp, p, s)));
        card.addView(keyInput);

        // Help link row
        if (p.signupUrl != null) {
            TextView link = new TextView(this);
            link.setText("获取 Key  →  " + extractHost(p.signupUrl));
            link.setTextColor(COLOR_ACCENT);
            link.setTextSize(12f);
            LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            linkLp.topMargin = dp(10);
            link.setLayoutParams(linkLp);
            link.setOnClickListener(v -> openUrl(p.signupUrl));
            card.addView(link);
        }

        // Test row
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams testRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        testRowLp.topMargin = dp(12);
        testRow.setLayoutParams(testRowLp);

        final Button btnTest = new Button(this);
        btnTest.setText("测试连接");
        btnTest.setTextSize(12f);
        btnTest.setAllCaps(false);
        btnTest.setTextColor(COLOR_BG);
        btnTest.setBackgroundTintList(ColorStateList.valueOf(COLOR_ACCENT));
        btnTest.setMinWidth(0);
        btnTest.setMinHeight(0);
        btnTest.setPadding(dp(14), dp(6), dp(14), dp(6));
        testRow.addView(btnTest);

        final TextView resultTv = new TextView(this);
        resultTv.setTextSize(11f);
        resultTv.setTextColor(COLOR_SECONDARY);
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        resultLp.weight = 1;
        resultLp.leftMargin = dp(12);
        resultTv.setLayoutParams(resultLp);
        testRow.addView(resultTv);

        card.addView(testRow);

        btnTest.setOnClickListener(v -> {
            String k = keyInput.getText().toString().trim();
            if (k.isEmpty()) {
                resultTv.setText("请先填 Key");
                resultTv.setTextColor(COLOR_ERR);
                return;
            }
            String u = (p == AiClassifier.Provider.CUSTOM && urlInput != null)
                    ? urlInput.getText().toString().trim() : p.url;
            String m = (p == AiClassifier.Provider.CUSTOM && modelInput != null)
                    ? modelInput.getText().toString().trim() : p.model;
            if (u == null || u.isEmpty() || m == null || m.isEmpty()) {
                resultTv.setText("URL / 模型不能为空");
                resultTv.setTextColor(COLOR_ERR);
                return;
            }
            btnTest.setEnabled(false);
            btnTest.setText("测试中…");
            resultTv.setText("等待响应…");
            resultTv.setTextColor(COLOR_SECONDARY);

            AiClassifier tester = new AiClassifier(u, m, k,
                    AiClassifier.BUILTIN_SCENES, p.label);
            tester.test((ok, msg) -> {
                btnTest.setEnabled(true);
                btnTest.setText("测试连接");
                if (ok) {
                    resultTv.setText("通过  ·  " + msg);
                    resultTv.setTextColor(COLOR_OK);
                } else {
                    resultTv.setText("失败  ·  " + msg);
                    resultTv.setTextColor(COLOR_ERR);
                }
            });
        });

        sw.setOnCheckedChangeListener((CompoundButton btn, boolean checked) ->
                Providers.setEnabled(sp, p, checked));

        return card;
    }

    private EditText makeField(String hint, String value, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setText(value == null ? "" : value);
        et.setTextColor(COLOR_PRIMARY);
        et.setHintTextColor(COLOR_TERTIARY);
        et.setTextSize(13f);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_BG);
        bg.setCornerRadius(dp(8));
        bg.setStroke(1, COLOR_DIVIDER);
        et.setBackground(bg);

        et.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        et.setLayoutParams(lp);

        return et;
    }

    private void tintSwitch(Switch sw) {
        int off = COLOR_TERTIARY;
        int on = COLOR_ACCENT;
        ColorStateList thumb = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{on, COLOR_SECONDARY});
        ColorStateList track = new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{(on & 0x00FFFFFF) | 0x66000000, off});
        sw.setThumbTintList(thumb);
        sw.setTrackTintList(track);
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
