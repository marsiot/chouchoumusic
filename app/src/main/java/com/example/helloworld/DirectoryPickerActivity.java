package com.example.helloworld;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class DirectoryPickerActivity extends AppCompatActivity {

    public static final String EXTRA_INITIAL = "initial_path";
    public static final String RESULT_PATH = "selected_path";

    private static final String STORAGE_LABEL = "内部存储";

    private File rootDir;
    private File currentDir;
    private TextView pathText;
    private ListView listView;
    private final List<File> entries = new ArrayList<>();
    private ArrayAdapter<File> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dir_picker);

        rootDir = Environment.getExternalStorageDirectory();
        currentDir = rootDir;

        pathText = findViewById(R.id.pickerPath);
        listView = findViewById(R.id.pickerList);

        adapter = new ArrayAdapter<File>(this, 0, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView;
                if (v == null) {
                    v = LayoutInflater.from(getContext())
                            .inflate(R.layout.item_dir, parent, false);
                }
                File f = getItem(position);
                ((TextView) v.findViewById(R.id.dirName))
                        .setText(f == null ? "" : f.getName());
                ((ImageView) v.findViewById(R.id.dirIcon))
                        .setImageResource(R.drawable.ic_folder);
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> {
            File f = entries.get(pos);
            if (f != null && f.isDirectory()) {
                currentDir = f;
                refresh();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(v -> navigateUp());
        findViewById(R.id.btnSelectHere).setOnClickListener(v -> selectCurrent());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                navigateUp();
            }
        });

        refresh();
    }

    private void navigateUp() {
        if (currentDir == null || currentDir.equals(rootDir)) {
            finish();
            return;
        }
        File parent = currentDir.getParentFile();
        if (parent == null || !parent.getAbsolutePath().startsWith(rootDir.getAbsolutePath())) {
            finish();
            return;
        }
        currentDir = parent;
        refresh();
    }

    private void refresh() {
        String rel = relativePath();
        pathText.setText(STORAGE_LABEL + (rel.isEmpty() ? "/" : "/" + rel + "/"));

        entries.clear();
        File[] subs = currentDir.listFiles(
                f -> f.isDirectory() && !f.getName().startsWith("."));
        if (subs != null) {
            Arrays.sort(subs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            entries.addAll(Arrays.asList(subs));
        }
        adapter.notifyDataSetChanged();
        listView.smoothScrollToPosition(0);
    }

    private String relativePath() {
        String full = currentDir.getAbsolutePath();
        String r = rootDir.getAbsolutePath();
        if (!full.startsWith(r)) return "";
        String s = full.substring(r.length());
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void selectCurrent() {
        Intent out = new Intent();
        out.putExtra(RESULT_PATH, relativePath());
        setResult(RESULT_OK, out);
        finish();
    }
}
