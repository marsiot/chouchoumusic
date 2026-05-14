package com.example.helloworld;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1001;
    private static final String ROOT_LABEL = "(根目录)";
    private static final String STORAGE_LABEL = "内部存储";
    private static final String PREFS = Providers.PREFS;
    private static final String KEY_CUSTOM_SCENES = "custom_scenes";
    private static final int BATCH_SIZE = 10;

    private final List<String> customScenes = new ArrayList<>();
    private String scanRootDir = Providers.DEFAULT_SCAN_DIR;

    private enum Mode { FOLDERS, SONGS, SCENE_SONGS }

    private enum PlayMode {
        LOOP_ALL(R.drawable.ic_mode_loop, "循环"),
        REPEAT_ONE(R.drawable.ic_mode_repeat_one, "单曲"),
        SHUFFLE(R.drawable.ic_mode_shuffle, "随机");

        final int iconRes;
        final String label;
        PlayMode(int iconRes, String label) { this.iconRes = iconRes; this.label = label; }
    }

    private TextView topTitle;
    private TextView sectionPath;
    private TextView sectionTitle;
    private TextView sectionMeta;
    private View playAllBar;
    private ImageView btnBack;
    private ImageView btnSettings;
    private RecyclerView listView;
    private LinearLayout miniPlayer;
    private TextView nowPlaying;
    private TextView nowMeta;
    private TextView currentLyric;
    private ImageView btnMode, btnPrev, btnPlay, btnNext;
    private SeekBar progressBar;
    private TextView timeCurrent;
    private TextView timeTotal;
    private boolean seekBarDragging = false;

    private View lyricsOverlay;
    private TextView lyricsTitle;
    private ScrollView lyricsScroll;
    private LinearLayout lyricsContainer;

    private final Map<String, List<Song>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> folderNames = new ArrayList<>();
    private final Map<String, Song> songByPath = new HashMap<>();
    private final Map<String, Sidecar.Data> sidecarByPath = new HashMap<>();
    private RowAdapter adapter;

    private Mode mode = Mode.FOLDERS;
    private String currentFolder = null;
    private String currentScene = null;

    private MediaPlayer player;
    private boolean isPrepared = false;
    private List<Song> playQueue = Collections.emptyList();
    private int playingIndex = -1;
    private String playSourceLabel = null;

    private PlayMode playMode = PlayMode.LOOP_ALL;
    private final Random random = new Random();

    private boolean selectionMode = false;
    private final Set<String> selectedPaths = new HashSet<>();

    private List<LrcParser.Line> lyrics = Collections.emptyList();
    private int currentLyricIndex = -1;
    private long lyricsRequestToken = 0;
    private LyricsFetcher fetcher;

    private ClassificationCache classCache;
    private ClassifierPool classifierPool;
    private boolean scanning = false;
    private int scanDone = 0;
    private int scanTotal = 0;
    private int totalSongs = 0;

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tickProgress();
            tickLyrics();
            tickHandler.postDelayed(this, 250);
        }
    };

    private static class Song {
        final String name;
        final Uri uri;
        final String path;
        final String folder;

        Song(String name, Uri uri, String path, String folder) {
            this.name = name;
            this.uri = uri;
            this.path = path;
            this.folder = folder;
        }
    }

    private static class Row {
        enum Kind { SCENE, FOLDER, ADD_SCENE, ADD_FOLDER, SONG, EMPTY_HINT }
        final Kind kind;
        final String primary;
        final String secondary;
        final String meta;
        final boolean showChevron;
        final boolean accent;
        final String key;
        final Song song;

        Row(Kind kind, String primary, String secondary, String meta,
            boolean showChevron, boolean accent, String key, Song song) {
            this.kind = kind;
            this.primary = primary;
            this.secondary = secondary;
            this.meta = meta;
            this.showChevron = showChevron;
            this.accent = accent;
            this.key = key;
            this.song = song;
        }
    }

    interface OnMoreClickListener { void onMore(Row row); }
    interface OnRowClickListener { void onClick(Row row, int position); }
    interface OnRowLongClickListener { boolean onLongClick(Row row, int position); }

    private static class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {
        private final int colorPrimary;
        private final int colorAccent;
        private final int colorSecondary;
        private final int colorTertiary;
        private final int colorSelected = 0x33C9A876;
        private final List<Row> items = new ArrayList<>();
        private Set<String> selectedKeys = null;
        private OnMoreClickListener moreListener;
        private OnRowClickListener clickListener;
        private OnRowLongClickListener longClickListener;
        private String playingPath;

        RowAdapter(Context c) {
            colorPrimary = ContextCompat.getColor(c, R.color.text_primary);
            colorAccent = ContextCompat.getColor(c, R.color.accent);
            colorSecondary = ContextCompat.getColor(c, R.color.text_secondary);
            colorTertiary = ContextCompat.getColor(c, R.color.text_tertiary);
            setHasStableIds(false);
        }

        // ArrayAdapter-compatible API so callers don't have to change much
        void clear() {
            int n = items.size();
            items.clear();
            if (n > 0) notifyItemRangeRemoved(0, n);
        }

        void add(Row r) {
            items.add(r);
            notifyItemInserted(items.size() - 1);
        }

        Row getItem(int pos) {
            return (pos < 0 || pos >= items.size()) ? null : items.get(pos);
        }

        int size() { return items.size(); }

        void move(int from, int to) {
            if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
            Row moved = items.remove(from);
            items.add(to, moved);
            notifyItemMoved(from, to);
        }

        void setOnRowClickListener(OnRowClickListener l) { clickListener = l; }
        void setOnRowLongClickListener(OnRowLongClickListener l) { longClickListener = l; }
        void setOnMoreClickListener(OnMoreClickListener l) { moreListener = l; }

        void setSelectedKeys(Set<String> keys) {
            this.selectedKeys = keys;
            notifyDataSetChanged();
        }

        void setPlayingPath(String path) {
            if ((path == null && playingPath == null)
                    || (path != null && path.equals(playingPath))) return;
            this.playingPath = path;
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            Row r = items.get(position);

            h.primary.setText(r.primary);
            boolean isPlayingRow = r.kind == Row.Kind.SONG && r.key != null
                    && r.key.equals(playingPath);
            if (r.kind == Row.Kind.EMPTY_HINT) {
                h.primary.setTextColor(colorTertiary);
            } else if (r.kind == Row.Kind.ADD_SCENE || r.kind == Row.Kind.ADD_FOLDER) {
                h.primary.setTextColor(colorSecondary);
            } else if (isPlayingRow) {
                h.primary.setTextColor(colorAccent);
            } else {
                h.primary.setTextColor(r.accent ? colorAccent : colorPrimary);
            }
            h.primary.setTypeface(android.graphics.Typeface.DEFAULT,
                    isPlayingRow ? android.graphics.Typeface.BOLD
                            : android.graphics.Typeface.NORMAL);

            if (r.secondary != null && !r.secondary.isEmpty()) {
                h.secondary.setVisibility(View.VISIBLE);
                h.secondary.setText(r.secondary);
            } else {
                h.secondary.setVisibility(View.GONE);
            }

            if (r.meta != null && !r.meta.isEmpty()) {
                h.meta.setVisibility(View.VISIBLE);
                h.meta.setText(r.meta);
            } else {
                h.meta.setVisibility(View.GONE);
            }

            h.chevron.setVisibility(r.showChevron ? View.VISIBLE : View.INVISIBLE);

            boolean inSelectionMode = selectedKeys != null;
            boolean isSongRow = r.kind == Row.Kind.SONG && r.song != null;
            boolean isFolderRow = r.kind == Row.Kind.FOLDER;

            if (isSongRow && inSelectionMode) {
                h.more.setVisibility(View.GONE);
                h.more.setOnClickListener(null);
                h.check.setVisibility(View.VISIBLE);
                boolean selected = r.key != null && selectedKeys.contains(r.key);
                h.check.setImageResource(selected
                        ? R.drawable.ic_check_on : R.drawable.ic_check_off);
            } else if (isSongRow || isFolderRow) {
                h.check.setVisibility(View.GONE);
                h.more.setVisibility(View.VISIBLE);
                final VH vh = h;
                h.more.setOnClickListener(view -> {
                    int p = vh.getBindingAdapterPosition();
                    if (p >= 0 && moreListener != null) {
                        moreListener.onMore(items.get(p));
                    }
                });
            } else {
                h.check.setVisibility(View.GONE);
                h.more.setVisibility(View.GONE);
                h.more.setOnClickListener(null);
            }

            boolean highlight = isSongRow && inSelectionMode
                    && r.key != null && selectedKeys.contains(r.key);
            h.itemView.setBackgroundColor(highlight ? colorSelected : 0);

            final VH vhRef = h;
            h.itemView.setOnClickListener(view -> {
                int p = vhRef.getBindingAdapterPosition();
                if (p >= 0 && clickListener != null) {
                    clickListener.onClick(items.get(p), p);
                }
            });
            h.itemView.setOnLongClickListener(view -> {
                int p = vhRef.getBindingAdapterPosition();
                if (p >= 0 && longClickListener != null) {
                    return longClickListener.onLongClick(items.get(p), p);
                }
                return false;
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView primary;
            final TextView secondary;
            final TextView meta;
            final ImageView chevron;
            final ImageView more;
            final ImageView check;

            VH(View v) {
                super(v);
                primary = v.findViewById(R.id.rowPrimary);
                secondary = v.findViewById(R.id.rowSecondary);
                meta = v.findViewById(R.id.rowMeta);
                chevron = v.findViewById(R.id.rowChevron);
                more = v.findViewById(R.id.rowMore);
                check = v.findViewById(R.id.rowCheck);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        topTitle = findViewById(R.id.topTitle);
        sectionPath = findViewById(R.id.sectionPath);
        sectionTitle = findViewById(R.id.sectionTitle);
        sectionMeta = findViewById(R.id.sectionMeta);
        playAllBar = findViewById(R.id.playAllBar);
        playAllBar.setOnClickListener(v -> playAll());
        btnBack = findViewById(R.id.btnBack);
        btnSettings = findViewById(R.id.btnSettings);
        listView = findViewById(R.id.songList);
        miniPlayer = findViewById(R.id.miniPlayer);
        nowPlaying = findViewById(R.id.nowPlaying);
        nowMeta = findViewById(R.id.nowMeta);
        currentLyric = findViewById(R.id.currentLyric);
        btnMode = findViewById(R.id.btnMode);
        btnPrev = findViewById(R.id.btnPrev);
        btnPlay = findViewById(R.id.btnPlay);
        btnNext = findViewById(R.id.btnNext);
        progressBar = findViewById(R.id.progressBar);
        timeCurrent = findViewById(R.id.timeCurrent);
        timeTotal = findViewById(R.id.timeTotal);
        progressBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null && isPrepared) {
                    try {
                        int d = player.getDuration();
                        if (d > 0) {
                            timeCurrent.setText(formatMs((int) ((p / 1000f) * d)));
                        }
                    } catch (IllegalStateException ignored) {}
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                seekBarDragging = true;
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                seekBarDragging = false;
                if (player != null && isPrepared) {
                    try {
                        int duration = player.getDuration();
                        if (duration > 0) {
                            int newPos = (int) ((sb.getProgress() / 1000f) * duration);
                            player.seekTo(newPos);
                        }
                    } catch (IllegalStateException ignored) {}
                }
            }
        });

        lyricsOverlay = findViewById(R.id.lyricsOverlay);
        lyricsTitle = findViewById(R.id.lyricsTitle);
        lyricsScroll = findViewById(R.id.lyricsScroll);
        lyricsContainer = findViewById(R.id.lyricsContainer);
        findViewById(R.id.btnCloseLyrics).setOnClickListener(v -> hideLyrics());
        findViewById(R.id.btnRelyrics).setOnClickListener(v -> showRelyricsDialog());

        fetcher = new LyricsFetcher(this);
        classCache = new ClassificationCache(this);
        Providers.migrateLegacyPrefs(Providers.prefs(this));
        scanRootDir = Providers.getScanDir(Providers.prefs(this));
        loadCustomScenes();

        adapter = new RowAdapter(this);
        adapter.setOnMoreClickListener(r -> {
            if (r.kind == Row.Kind.SONG && r.song != null) {
                showSongOptions(r.song);
            } else if (r.kind == Row.Kind.FOLDER && r.key != null) {
                confirmDeleteFolder(r.key);
            }
        });
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        adapter.setOnRowClickListener((r, position) -> {
            if (r == null) return;
            if (selectionMode) {
                if (r.kind == Row.Kind.SONG && r.song != null) toggleSelection(r.song);
                return;
            }
            switch (r.kind) {
                case SCENE: onSceneClicked(r.key); break;
                case FOLDER: openFolder(r.key); break;
                case ADD_SCENE: promptAddScene(); break;
                case ADD_FOLDER: promptNewFolder(); break;
                case SONG: {
                    if (mode == Mode.SONGS) {
                        List<Song> list = folders.get(currentFolder);
                        if (list != null && !list.isEmpty()) {
                            int idx = list.indexOf(r.song);
                            if (idx >= 0) play(list, idx, currentFolder);
                        }
                    } else if (mode == Mode.SCENE_SONGS) {
                        List<Song> list = songsForScene(currentScene);
                        int idx = list.indexOf(r.song);
                        if (idx >= 0) play(list, idx, currentScene);
                    }
                    break;
                }
                case EMPTY_HINT: break;
            }
        });

        adapter.setOnRowLongClickListener((r, position) -> {
            if (r == null) return false;
            if (r.kind == Row.Kind.SCENE && !AiClassifier.BUILTIN_SCENES.contains(r.key)) {
                promptDeleteScene(r.key);
                return true;
            }
            // SONG rows: long-press in SONGS mode = drag (ItemTouchHelper handles it);
            // in other modes do nothing (use ⋯ for actions).
            return false;
        });

        attachDragReorder();

        updateTopBar();

        // Tap on the text area of mini player → expand lyrics
        miniPlayer.findViewById(R.id.nowPlaying).setOnClickListener(v -> tryShowLyrics());
        miniPlayer.findViewById(R.id.nowMeta).setOnClickListener(v -> tryShowLyrics());
        miniPlayer.findViewById(R.id.currentLyric).setOnClickListener(v -> tryShowLyrics());

        btnMode.setOnClickListener(v -> cyclePlayMode());
        btnPrev.setOnClickListener(v -> playPrev());
        btnPlay.setOnClickListener(v -> togglePlayPause());
        btnNext.setOnClickListener(v -> playNext(false));

        updatePlayButton();
        updateModeButton();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selectionMode) {
                    exitSelectionMode();
                    return;
                }
                if (lyricsOverlay.getVisibility() == View.VISIBLE) {
                    hideLyrics();
                    return;
                }
                if (mode == Mode.SONGS || mode == Mode.SCENE_SONGS) {
                    showFolders();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (hasPermission()) {
            loadAndShow();
        } else {
            requestPermission();
        }

        if (!hasAllFilesAccess()) {
            promptAllFilesAccess();
        }
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || Environment.isExternalStorageManager();
    }

    private void promptAllFilesAccess() {
        new AlertDialog.Builder(this)
                .setTitle("文件访问权限")
                .setMessage("为了把歌词 (.lrc) 和场景标签保存到歌曲所在文件夹（这样 app 卸载后这些数据还在），需要授权「所有文件访问」。\n\n如果跳过，app 依然可以工作，但所有缓存只存在 app 私有目录，卸载会一起清除。")
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton("跳过", null)
                .show();
    }

    private boolean hasPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermission() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        ActivityCompat.requestPermissions(this, new String[]{perm}, REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAndShow();
            } else {
                Toast.makeText(this, "需要音频读取权限才能扫描音乐", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void loadAndShow() {
        loadSongs();
        int migrated = migrateClassCacheToSidecars();
        if (migrated > 0) {
            Toast.makeText(this,
                    "已把 " + migrated + " 首歌的场景标签写入 .lrc",
                    Toast.LENGTH_LONG).show();
        }
        showFolders();
        maybeStartClassifyScan();
    }

    private int migrateClassCacheToSidecars() {
        if (!hasAllFilesAccess()) return 0;
        int wrote = 0;
        for (Song s : songByPath.values()) {
            Sidecar.Data sd = sidecarByPath.get(s.path);
            if (sd != null && sd.classified) continue;
            String qk = queryKeyForSong(s);
            Set<String> cached = classCache.get(qk);
            if (cached == null) continue;
            File sf = Sidecar.sidecarFor(s.path);
            if (sf == null) continue;
            Sidecar.Data d = (sd != null) ? sd : Sidecar.read(sf);
            d.scenes = new LinkedHashSet<>(cached);
            d.classified = true;
            if (Sidecar.write(sf, d)) {
                sidecarByPath.put(s.path, d);
                wrote++;
            }
        }
        return wrote;
    }

    private void loadSongs() {
        folders.clear();
        folderNames.clear();
        songByPath.clear();
        sidecarByPath.clear();
        totalSongs = 0;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA
        };
        String selection = MediaStore.Audio.Media.DATA + " LIKE ? OR " + MediaStore.Audio.Media.DATA + " LIKE ?";
        String pat = "%/" + scanRootDir + "/%";
        String patLower = "%/" + scanRootDir.toLowerCase() + "/%";
        String[] args = patLower.equals(pat) ? new String[]{pat, pat} : new String[]{pat, patLower};
        String sort = MediaStore.Audio.Media.DATA + " ASC";

        try (Cursor c = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort)) {
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    String name = c.getString(nameCol);
                    String path = c.getString(dataCol);
                    String folder = extractFolder(path);
                    if (folder == null) continue;
                    Uri uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    Song song = new Song(name, uri, path, folder);
                    folders.computeIfAbsent(folder, k -> new ArrayList<>()).add(song);
                    songByPath.put(path, song);
                    totalSongs++;
                }
            }
        }

        if (hasAllFilesAccess()) {
            File musicDir = scanRoot();
            File[] subdirs = (musicDir != null && musicDir.isDirectory())
                    ? musicDir.listFiles(File::isDirectory) : null;
            if (subdirs != null) {
                for (File d : subdirs) {
                    String name = d.getName();
                    if (!folders.containsKey(name)) {
                        folders.put(name, new ArrayList<>());
                    }
                }
            }
        }

        folderNames.addAll(folders.keySet());

        SharedPreferences spOrder = Providers.prefs(this);
        for (String folder : folderNames) {
            List<Song> list = folders.get(folder);
            if (list == null || list.size() <= 1) continue;
            List<String> savedOrder = Providers.getFolderOrder(spOrder, folder);
            if (savedOrder.isEmpty()) continue;
            LinkedHashMap<String, Song> byPath = new LinkedHashMap<>();
            for (Song s : list) byPath.put(s.path, s);
            List<Song> reordered = new ArrayList<>(list.size());
            for (String p : savedOrder) {
                Song s = byPath.remove(p);
                if (s != null) reordered.add(s);
            }
            reordered.addAll(byPath.values());
            list.clear();
            list.addAll(reordered);
        }

        if (hasAllFilesAccess()) {
            for (Song s : songByPath.values()) {
                File sf = Sidecar.sidecarFor(s.path);
                if (sf != null && sf.exists()) {
                    sidecarByPath.put(s.path, Sidecar.read(sf));
                }
            }
        }

        if (folderNames.isEmpty()) {
            Toast.makeText(this,
                    "未找到音乐文件，请检查内部存储/" + scanRootDir + " 目录",
                    Toast.LENGTH_LONG).show();
        }
    }

    private File scanRoot() {
        return new File(Environment.getExternalStorageDirectory(), scanRootDir);
    }

    private String extractFolder(String path) {
        if (path == null) return null;
        String marker = "/" + scanRootDir + "/";
        int idx = path.indexOf(marker);
        if (idx < 0) {
            String lower = path.toLowerCase();
            idx = lower.indexOf(marker.toLowerCase());
        }
        if (idx < 0) return null;
        String rest = path.substring(idx + marker.length());
        int slash = rest.indexOf('/');
        if (slash < 0) return ROOT_LABEL;
        String name = rest.substring(0, slash);
        return name.isEmpty() ? ROOT_LABEL : name;
    }

    private List<String> allSceneKeys() {
        List<String> all = new ArrayList<>(AiClassifier.BUILTIN_SCENES);
        all.addAll(customScenes);
        return all;
    }

    private void loadCustomScenes() {
        customScenes.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String json = sp.getString(KEY_CUSTOM_SCENES, "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String name = arr.optString(i, "").trim();
                if (!name.isEmpty() && !AiClassifier.BUILTIN_SCENES.contains(name)
                        && !customScenes.contains(name)) {
                    customScenes.add(name);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void saveCustomScenes() {
        org.json.JSONArray arr = new org.json.JSONArray(customScenes);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_CUSTOM_SCENES, arr.toString())
                .apply();
    }

    private static String queryKeyForSong(Song s) {
        String[] ta = LyricsFetcher.extractTitleArtist(s.name, s.folder);
        return LyricsFetcher.queryKey(ta[0], ta[1]);
    }

    private Set<String> scenesForSong(Song s) {
        Sidecar.Data d = sidecarByPath.get(s.path);
        if (d != null && d.classified) return d.scenes;
        Set<String> fromAppCache = classCache.get(queryKeyForSong(s));
        return fromAppCache == null ? Collections.emptySet() : fromAppCache;
    }

    private boolean isClassified(Song s) {
        Sidecar.Data d = sidecarByPath.get(s.path);
        if (d != null && d.classified) return true;
        return classCache.has(queryKeyForSong(s));
    }

    private List<Song> songsForScene(String sceneKey) {
        if (sceneKey == null) return Collections.emptyList();
        LinkedHashMap<String, Song> tagged = new LinkedHashMap<>();
        for (Song s : songByPath.values()) {
            if (scenesForSong(s).contains(sceneKey)) tagged.put(s.path, s);
        }
        if (tagged.isEmpty()) return Collections.emptyList();
        List<String> customOrder = Providers.getSceneOrder(Providers.prefs(this), sceneKey);
        List<Song> out = new ArrayList<>(tagged.size());
        for (String p : customOrder) {
            Song s = tagged.remove(p);
            if (s != null) out.add(s);
        }
        out.addAll(tagged.values());
        return out;
    }

    private int countSongsInScene(String sceneKey) {
        int n = 0;
        for (Song s : songByPath.values()) {
            if (scenesForSong(s).contains(sceneKey)) n++;
        }
        return n;
    }

    private void showFolders() {
        mode = Mode.FOLDERS;
        currentFolder = null;
        currentScene = null;

        updateTopBar();
        sectionPath.setText(STORAGE_LABEL + "/" + scanRootDir + "/");
        sectionPath.setVisibility(View.VISIBLE);
        sectionTitle.setText("全部");
        playAllBar.setVisibility(View.GONE);
        updateSectionMeta();

        adapter.clear();
        for (String key : allSceneKeys()) {
            int count = countSongsInScene(key);
            adapter.add(new Row(Row.Kind.SCENE, key, null,
                    String.valueOf(count), true, count > 0, key, null));
        }
        adapter.add(new Row(Row.Kind.ADD_SCENE, "添加场景", null,
                null, false, false, null, null));
        for (String name : folderNames) {
            int count = folders.get(name).size();
            adapter.add(new Row(Row.Kind.FOLDER, name, null,
                    String.valueOf(count), true, false, name, null));
        }
        adapter.add(new Row(Row.Kind.ADD_FOLDER, "新建文件夹", null,
                null, false, false, null, null));
        adapter.notifyDataSetChanged();
        listView.smoothScrollToPosition(0);
    }

    private void updateSectionMeta() {
        StringBuilder sb = new StringBuilder();
        if (mode == Mode.FOLDERS) {
            sb.append(folderNames.size()).append(" 分类  ·  ")
                    .append(totalSongs).append(" 首");
            if (scanning) sb.append("    ●  AI 分类中 ")
                    .append(scanDone).append("/").append(scanTotal);
        } else if (mode == Mode.SONGS) {
            List<Song> l = folders.get(currentFolder);
            int n = l == null ? 0 : l.size();
            sb.append(n).append(" 首");
        } else if (mode == Mode.SCENE_SONGS) {
            List<Song> l = songsForScene(currentScene);
            sb.append(l.size()).append(" 首  ·  智能场景");
        }
        sectionMeta.setText(sb.toString());
    }

    private void openFolder(String folder) {
        mode = Mode.SONGS;
        currentFolder = folder;
        currentScene = null;

        updateTopBar();
        sectionPath.setText(STORAGE_LABEL + "/" + scanRootDir + "/" + folder + "/");
        sectionPath.setVisibility(View.VISIBLE);
        sectionTitle.setText(folder);
        List<Song> folderSongs = folders.get(folder);
        playAllBar.setVisibility(folderSongs != null && !folderSongs.isEmpty()
                ? View.VISIBLE : View.GONE);

        adapter.clear();
        List<Song> list = folders.get(folder);
        if (list != null) {
            for (Song s : list) {
                adapter.add(new Row(Row.Kind.SONG, s.name, null,
                        null, false, false, s.path, s));
            }
        }
        updateSectionMeta();
        adapter.notifyDataSetChanged();
        listView.smoothScrollToPosition(0);
    }

    private void attachDragReorder() {
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.Callback() {
            @Override
            public int getMovementFlags(RecyclerView rv, RecyclerView.ViewHolder vh) {
                if (selectionMode) return 0;
                if (mode != Mode.SONGS && mode != Mode.SCENE_SONGS) return 0;
                int p = vh.getBindingAdapterPosition();
                Row r = adapter.getItem(p);
                if (r == null || r.kind != Row.Kind.SONG) return 0;
                return makeMovementFlags(
                        ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
            }

            @Override
            public boolean onMove(RecyclerView rv,
                                  RecyclerView.ViewHolder from,
                                  RecyclerView.ViewHolder to) {
                int fromPos = from.getBindingAdapterPosition();
                int toPos = to.getBindingAdapterPosition();
                if (fromPos < 0 || toPos < 0 || fromPos == toPos) return false;
                Row r = adapter.getItem(fromPos);
                if (r == null || r.kind != Row.Kind.SONG) return false;

                if (mode == Mode.SONGS && currentFolder != null) {
                    adapter.move(fromPos, toPos);
                    moveSongInFolderList(currentFolder, fromPos, toPos);
                    return true;
                }
                if (mode == Mode.SCENE_SONGS && currentScene != null) {
                    adapter.move(fromPos, toPos);
                    moveSongInSceneList(currentScene, fromPos, toPos);
                    return true;
                }
                return false;
            }

            @Override
            public void onSwiped(RecyclerView.ViewHolder vh, int direction) {}

            @Override
            public boolean isLongPressDragEnabled() { return true; }
        });
        helper.attachToRecyclerView(listView);
    }

    private void moveSongInFolderList(String folder, int from, int to) {
        List<Song> list = folders.get(folder);
        if (list == null) return;
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size()) return;
        Song playingBefore = (playQueue == list
                && playingIndex >= 0 && playingIndex < list.size())
                ? list.get(playingIndex) : null;
        Song moved = list.remove(from);
        list.add(to, moved);
        if (playingBefore != null) {
            int newIdx = list.indexOf(playingBefore);
            if (newIdx >= 0) playingIndex = newIdx;
        }
        List<String> paths = new ArrayList<>(list.size());
        for (Song s : list) paths.add(s.path);
        Providers.setFolderOrder(Providers.prefs(this), folder, paths);
    }

    private void moveSongInSceneList(String scene, int from, int to) {
        List<Song> current = new ArrayList<>(songsForScene(scene));
        if (from < 0 || to < 0 || from >= current.size() || to >= current.size()) return;
        Song playingSong = (playingIndex >= 0 && playingIndex < playQueue.size())
                ? playQueue.get(playingIndex) : null;
        Song moved = current.remove(from);
        current.add(to, moved);
        List<String> paths = new ArrayList<>(current.size());
        for (Song s : current) paths.add(s.path);
        Providers.setSceneOrder(Providers.prefs(this), scene, paths);
        if (playingSong != null && scene.equals(playSourceLabel)) {
            playQueue = current;
            int newIdx = current.indexOf(playingSong);
            if (newIdx >= 0) playingIndex = newIdx;
        }
    }

    private void playAll() {
        if (mode == Mode.SONGS && currentFolder != null) {
            List<Song> list = folders.get(currentFolder);
            if (list != null && !list.isEmpty()) play(list, 0, currentFolder);
        } else if (mode == Mode.SCENE_SONGS && currentScene != null) {
            List<Song> list = songsForScene(currentScene);
            if (!list.isEmpty()) play(list, 0, currentScene);
        }
    }

    private void onSceneClicked(String sceneKey) {
        if (!Providers.buildPool(this, allSceneKeys()).isEmpty()) {
            openScene(sceneKey);
            return;
        }
        Toast.makeText(this,
                "智能场景需要先配置 AI 服务商，点右上角设置",
                Toast.LENGTH_LONG).show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openScene(String sceneKey) {
        mode = Mode.SCENE_SONGS;
        currentScene = sceneKey;
        currentFolder = null;

        updateTopBar();
        sectionPath.setText("智能场景 · AI 标签");
        sectionPath.setVisibility(View.VISIBLE);
        sectionTitle.setText(sceneKey);
        List<Song> sceneSongs = songsForScene(sceneKey);
        playAllBar.setVisibility(sceneSongs.isEmpty() ? View.GONE : View.VISIBLE);

        List<Song> list = songsForScene(sceneKey);
        adapter.clear();
        if (list.isEmpty()) {
            String hint;
            if (scanning) hint = "AI 分类中，请稍后回来";
            else if (classCache.size() == 0) hint = "尚未开始 AI 分类";
            else hint = "暂无歌曲符合此场景";
            adapter.add(new Row(Row.Kind.EMPTY_HINT, hint, null,
                    null, false, false, null, null));
        } else {
            for (Song s : list) {
                adapter.add(new Row(Row.Kind.SONG, s.name, s.folder,
                        null, false, false, s.path, s));
            }
        }
        updateSectionMeta();
        adapter.notifyDataSetChanged();
        listView.smoothScrollToPosition(0);
    }

    private void play(List<Song> queue, int position, String label) {
        if (queue == null || queue.isEmpty() || position < 0 || position >= queue.size()) {
            stopPlayer();
            nowPlaying.setText("未播放");
            nowMeta.setText("");
            playingIndex = -1;
            playSourceLabel = null;
            playQueue = Collections.emptyList();
            clearLyrics();
            updatePlayButton();
            adapter.setPlayingPath(null);
            progressBar.setProgress(0);
            timeCurrent.setText("--:--");
            timeTotal.setText("--:--");
            return;
        }

        playQueue = queue;
        playSourceLabel = label;
        playingIndex = position;
        Song song = queue.get(position);
        nowPlaying.setText(song.name);
        nowMeta.setText(label == null ? "" : label);
        progressBar.setProgress(0);
        timeCurrent.setText("00:00");
        timeTotal.setText("--:--");
        adapter.setPlayingPath(song.path);

        if (mode == Mode.SONGS && song.folder.equals(currentFolder)) {
            listView.smoothScrollToPosition(position);
        } else if (mode == Mode.SCENE_SONGS) {
            listView.smoothScrollToPosition(position);
        }

        loadLyricsFor(song);

        stopPlayer();
        player = new MediaPlayer();
        isPrepared = false;
        try {
            player.setDataSource(this, song.uri);
            player.setOnCompletionListener(mp -> {
                if (playMode == PlayMode.REPEAT_ONE) {
                    play(playQueue, playingIndex, playSourceLabel);
                } else {
                    playNext(true);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "播放失败：" + song.name, Toast.LENGTH_SHORT).show();
                playNext(true);
                return true;
            });
            player.setOnPreparedListener(mp -> {
                isPrepared = true;
                try {
                    int d = mp.getDuration();
                    timeTotal.setText(d > 0 ? formatMs(d) : "--:--");
                } catch (IllegalStateException ignored) {}
                mp.start();
                updatePlayButton();
                startTicking();
            });
            player.prepareAsync();
        } catch (IOException e) {
            Toast.makeText(this, "无法打开：" + song.name, Toast.LENGTH_SHORT).show();
            playNext(true);
        }
    }

    private void loadLyricsFor(Song song) {
        lyrics = Collections.emptyList();
        currentLyricIndex = -1;
        lyricsContainer.removeAllViews();

        Sidecar.Data d = sidecarByPath.get(song.path);
        if (d != null && d.lyricsAttempted) {
            if (!d.lyrics.isEmpty()) {
                lyrics = d.lyrics;
                populateLyricsContainer();
                currentLyric.setText("");
                currentLyric.setVisibility(View.GONE);
                tickLyrics();
            } else {
                currentLyric.setText("");
                currentLyric.setVisibility(View.GONE);
            }
            return;
        }

        currentLyric.setText("查找歌词中…");
        currentLyric.setVisibility(View.VISIBLE);

        final long myToken = ++lyricsRequestToken;
        final Song theSong = song;
        fetcher.fetch(song.name, song.folder, (lines, source, error) -> {
            if (myToken != lyricsRequestToken) return;

            if (hasAllFilesAccess()) {
                File sf = Sidecar.sidecarFor(theSong.path);
                if (sf != null) {
                    Sidecar.Data sd = sidecarByPath.get(theSong.path);
                    if (sd == null) sd = Sidecar.read(sf);
                    sd.lyrics = (lines == null) ? new ArrayList<>() : new ArrayList<>(lines);
                    sd.lyricsAttempted = true;
                    Sidecar.write(sf, sd);
                    sidecarByPath.put(theSong.path, sd);
                }
            }

            if (lines == null || lines.isEmpty()) {
                lyrics = Collections.emptyList();
                currentLyric.setText("");
                currentLyric.setVisibility(View.GONE);
                return;
            }
            lyrics = lines;
            populateLyricsContainer();
            currentLyric.setText("");
            currentLyric.setVisibility(View.GONE);
            tickLyrics();
        });
    }

    private void populateLyricsContainer() {
        lyricsContainer.removeAllViews();
        int dimColor = ContextCompat.getColor(this, R.color.text_tertiary);
        for (LrcParser.Line line : lyrics) {
            TextView tv = new TextView(this);
            tv.setText(line.text.isEmpty() ? " " : line.text);
            tv.setTextColor(dimColor);
            tv.setTextSize(17f);
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
            tv.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (10 * getResources().getDisplayMetrics().density);
            lp.bottomMargin = lp.topMargin;
            tv.setLayoutParams(lp);
            lyricsContainer.addView(tv);
        }
    }

    private void clearLyrics() {
        stopTicking();
        lyrics = Collections.emptyList();
        currentLyricIndex = -1;
        currentLyric.setText("");
        currentLyric.setVisibility(View.GONE);
        lyricsContainer.removeAllViews();
        lyricsTitle.setText("");
    }

    private void startTicking() {
        tickHandler.removeCallbacks(tickRunnable);
        tickHandler.post(tickRunnable);
    }

    private void stopTicking() {
        tickHandler.removeCallbacks(tickRunnable);
    }

    private void tickProgress() {
        if (player == null || !isPrepared || seekBarDragging) return;
        try {
            int duration = player.getDuration();
            if (duration <= 0) return;
            int pos = player.getCurrentPosition();
            int p = (int) ((pos / (float) duration) * 1000);
            progressBar.setProgress(p);
            timeCurrent.setText(formatMs(pos));
            timeTotal.setText(formatMs(duration));
        } catch (IllegalStateException ignored) {
        }
    }

    private static String formatMs(int ms) {
        if (ms < 0) ms = 0;
        int seconds = ms / 1000;
        int min = seconds / 60;
        int sec = seconds % 60;
        if (min >= 60) {
            int hr = min / 60;
            min = min % 60;
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hr, min, sec);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", min, sec);
    }

    private void tickLyrics() {
        if (player == null || !isPrepared || lyrics.isEmpty()) return;
        long pos;
        try { pos = player.getCurrentPosition(); }
        catch (IllegalStateException e) { return; }
        int idx = LrcParser.findIndex(lyrics, pos);
        if (idx == currentLyricIndex) return;
        currentLyricIndex = idx;
        if (idx >= 0) {
            String line = lyrics.get(idx).text;
            currentLyric.setText(line.isEmpty() ? " " : line);
            currentLyric.setVisibility(View.VISIBLE);
            highlightInContainer(idx);
        } else {
            currentLyric.setText("");
            currentLyric.setVisibility(View.GONE);
            highlightInContainer(-1);
        }
    }

    private void highlightInContainer(int idx) {
        int n = lyricsContainer.getChildCount();
        int dim = ContextCompat.getColor(this, R.color.text_tertiary);
        int hi = ContextCompat.getColor(this, R.color.text_primary);
        for (int i = 0; i < n; i++) {
            TextView tv = (TextView) lyricsContainer.getChildAt(i);
            if (i == idx) {
                tv.setTextColor(hi);
                tv.setTextSize(22f);
            } else {
                tv.setTextColor(dim);
                tv.setTextSize(17f);
            }
        }
        if (idx >= 0 && idx < n && lyricsOverlay.getVisibility() == View.VISIBLE) {
            scrollLyricToCenter(idx);
        }
    }

    private void scrollLyricToCenter(final int idx) {
        lyricsScroll.post(() -> {
            View child = lyricsContainer.getChildAt(idx);
            if (child == null) return;
            int target = child.getTop() + child.getHeight() / 2
                    - lyricsScroll.getHeight() / 2 + lyricsContainer.getPaddingTop();
            target = Math.max(0, target);
            lyricsScroll.smoothScrollTo(0, target);
        });
    }

    private void tryShowLyrics() {
        if (playingIndex < 0) return;
        Song s = currentSongOrNull();
        if (s == null) return;
        lyricsTitle.setText(s.name);
        if (lyrics.isEmpty()) {
            showEmptyLyricsHint();
        }
        lyricsOverlay.setVisibility(View.VISIBLE);
        if (currentLyricIndex >= 0) scrollLyricToCenter(currentLyricIndex);
    }

    private void showEmptyLyricsHint() {
        lyricsContainer.removeAllViews();
        TextView tv = new TextView(this);
        tv.setText("暂无歌词\n\n点击右上角搜索图标手动匹配");
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
        tv.setTextSize(15f);
        tv.setGravity(Gravity.CENTER);
        tv.setLineSpacing(0f, 1.4f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        lyricsContainer.addView(tv);
    }

    private void hideLyrics() {
        lyricsOverlay.setVisibility(View.GONE);
    }

    private void showRelyricsDialog() {
        Song song = currentSongOrNull();
        if (song == null) {
            Toast.makeText(this, "没有正在播放的歌", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] auto = LyricsFetcher.extractTitleArtist(song.name, song.folder);

        int dp = (int) getResources().getDisplayMetrics().density;

        EditText titleInput = new EditText(this);
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT);
        titleInput.setHint("歌名");
        titleInput.setText(auto[0] == null ? "" : auto[0]);
        titleInput.setSingleLine(true);
        titleInput.setTextColor(Color.parseColor("#FFF2F0EC"));
        titleInput.setHintTextColor(Color.parseColor("#FF6B6B6B"));

        EditText artistInput = new EditText(this);
        artistInput.setInputType(InputType.TYPE_CLASS_TEXT);
        artistInput.setHint("歌手（可留空）");
        artistInput.setText(auto[1] == null ? "" : auto[1]);
        artistInput.setSingleLine(true);
        artistInput.setTextColor(Color.parseColor("#FFF2F0EC"));
        artistInput.setHintTextColor(Color.parseColor("#FF6B6B6B"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(16 * dp, 8 * dp, 16 * dp, 0);
        wrap.addView(titleInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = 8 * dp;
        wrap.addView(artistInput, lp2);

        new AlertDialog.Builder(this)
                .setTitle("重新匹配歌词")
                .setMessage("修正歌名/歌手后再去网易云搜歌词。新结果会覆盖当前缓存。")
                .setView(wrap)
                .setPositiveButton("搜索", (d, w) -> {
                    String t = titleInput.getText().toString().trim();
                    String a = artistInput.getText().toString().trim();
                    if (t.isEmpty()) {
                        Toast.makeText(this, "歌名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    refetchLyrics(song, t, a);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refetchLyrics(Song song, String title, String artist) {
        Toast.makeText(this, "正在搜索…", Toast.LENGTH_SHORT).show();
        final long myToken = ++lyricsRequestToken;
        final Song theSong = song;
        fetcher.fetchOverride(song.name, song.folder, title, artist,
                (lines, source, error) -> {
                    if (myToken != lyricsRequestToken) return;

                    if (hasAllFilesAccess()) {
                        File sf = Sidecar.sidecarFor(theSong.path);
                        if (sf != null) {
                            Sidecar.Data sd = sidecarByPath.get(theSong.path);
                            if (sd == null) sd = Sidecar.read(sf);
                            sd.lyrics = (lines == null)
                                    ? new ArrayList<>()
                                    : new ArrayList<>(lines);
                            sd.lyricsAttempted = true;
                            Sidecar.write(sf, sd);
                            sidecarByPath.put(theSong.path, sd);
                        }
                    }

                    if (lines == null || lines.isEmpty()) {
                        lyrics = Collections.emptyList();
                        lyricsContainer.removeAllViews();
                        currentLyric.setText("没找到匹配");
                        currentLyric.setVisibility(View.VISIBLE);
                        Toast.makeText(this,
                                error != null ? "搜索失败：" + error : "没找到匹配的歌词",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    lyrics = lines;
                    currentLyricIndex = -1;
                    populateLyricsContainer();
                    currentLyric.setText("");
                    currentLyric.setVisibility(View.GONE);
                    tickLyrics();
                    Toast.makeText(this, "歌词已更新", Toast.LENGTH_SHORT).show();
                });
    }

    private Song currentSongOrNull() {
        if (playQueue.isEmpty() || playingIndex < 0 || playingIndex >= playQueue.size()) return null;
        return playQueue.get(playingIndex);
    }

    private void playNext(boolean autoFromCompletion) {
        if (playQueue.isEmpty()) {
            startFromDefault();
            return;
        }
        int n = playQueue.size();
        int newIdx;
        switch (playMode) {
            case LOOP_ALL:
                newIdx = (playingIndex + 1) % n;
                break;
            case REPEAT_ONE:
                newIdx = autoFromCompletion ? playingIndex : (playingIndex + 1) % n;
                break;
            case SHUFFLE:
                newIdx = pickRandomExcept(n, playingIndex);
                break;
            default:
                newIdx = (playingIndex + 1) % n;
        }
        play(playQueue, newIdx, playSourceLabel);
    }

    private void playPrev() {
        if (playQueue.isEmpty()) {
            startFromDefault();
            return;
        }
        int n = playQueue.size();
        int newIdx;
        if (playMode == PlayMode.SHUFFLE) {
            newIdx = pickRandomExcept(n, playingIndex);
        } else {
            newIdx = playingIndex - 1;
            if (newIdx < 0) newIdx = n - 1;
        }
        play(playQueue, newIdx, playSourceLabel);
    }

    private void startFromDefault() {
        if (mode == Mode.SONGS && currentFolder != null) {
            List<Song> list = folders.get(currentFolder);
            if (list != null && !list.isEmpty()) play(list, 0, currentFolder);
        } else if (mode == Mode.SCENE_SONGS && currentScene != null) {
            List<Song> list = songsForScene(currentScene);
            if (!list.isEmpty()) play(list, 0, currentScene);
        } else if (!folderNames.isEmpty()) {
            String f = folderNames.get(0);
            play(folders.get(f), 0, f);
        }
    }

    private int pickRandomExcept(int size, int except) {
        if (size <= 1) return 0;
        int r;
        do { r = random.nextInt(size); } while (r == except);
        return r;
    }

    private void togglePlayPause() {
        if (player == null || !isPrepared) {
            if (!playQueue.isEmpty() && playingIndex >= 0) {
                play(playQueue, playingIndex, playSourceLabel);
            } else {
                startFromDefault();
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                stopTicking();
            } else {
                player.start();
                startTicking();
            }
        } catch (IllegalStateException ignored) {
        }
        updatePlayButton();
    }

    private void cyclePlayMode() {
        PlayMode[] all = PlayMode.values();
        playMode = all[(playMode.ordinal() + 1) % all.length];
        updateModeButton();
        Toast.makeText(this, playMode.label, Toast.LENGTH_SHORT).show();
    }

    private void updateModeButton() {
        btnMode.setImageResource(playMode.iconRes);
    }

    private void updatePlayButton() {
        boolean playing = false;
        try {
            playing = player != null && isPrepared && player.isPlaying();
        } catch (IllegalStateException ignored) {}
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void stopPlayer() {
        stopTicking();
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
            isPrepared = false;
        }
    }

    private void maybeStartClassifyScan() {
        if (scanning) return;
        if (classifierPool == null || classifierPool.isEmpty()) {
            classifierPool = Providers.buildPool(this, allSceneKeys());
        }
        if (classifierPool.isEmpty()) return;

        LinkedHashMap<String, AiClassifier.Item> unique = new LinkedHashMap<>();
        for (Song s : songByPath.values()) {
            if (isClassified(s)) continue;
            String[] ta = LyricsFetcher.extractTitleArtist(s.name, s.folder);
            String qk = LyricsFetcher.queryKey(ta[0], ta[1]);
            if (unique.containsKey(qk)) continue;
            unique.put(qk, new AiClassifier.Item(qk, ta[0], ta[1]));
        }
        if (unique.isEmpty()) return;

        List<AiClassifier.Item> todo = new ArrayList<>(unique.values());
        scanning = true;
        scanDone = 0;
        scanTotal = todo.size();
        updateSectionMeta();
        runNextBatch(classifierPool, todo, 0);
    }

    private void runNextBatch(final ClassifierPool pool,
                              final List<AiClassifier.Item> all, final int from) {
        if (pool == null) {
            scanning = false;
            updateSectionMeta();
            return;
        }
        if (from >= all.size()) {
            scanning = false;
            updateSectionMeta();
            refreshCurrentView();
            Toast.makeText(this, "AI 分类完成", Toast.LENGTH_SHORT).show();
            return;
        }
        int to = Math.min(from + BATCH_SIZE, all.size());
        List<AiClassifier.Item> batch = all.subList(from, to);
        pool.classifyBatch(batch, (results, error) -> {
            if (error != null) {
                scanning = false;
                updateSectionMeta();
                Toast.makeText(this, "AI 失败：" + error, Toast.LENGTH_LONG).show();
                return;
            }
            classCache.putBatch(results);
            writeClassificationToSidecars(results);
            scanDone = to;
            updateSectionMeta();
            refreshCurrentView();
            runNextBatch(pool, all, to);
        });
    }

    private void promptAddScene() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("如：开车、午休、做饭");
        input.setTextColor(Color.parseColor("#FFF2F0EC"));
        input.setHintTextColor(Color.parseColor("#FF6B6B6B"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, 8 * dp, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("添加场景")
                .setMessage("AI 会根据歌名/歌手判断每首歌是否适合。\n添加后会重新分类所有歌曲。")
                .setView(wrap)
                .setPositiveButton("添加", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (name.length() > 12) {
                        Toast.makeText(this, "名称太长（限 12 字）", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.contains(",") || name.contains(";") || name.contains("|")
                            || name.contains("[") || name.contains("]")) {
                        Toast.makeText(this, "名称不能含 , ; | [ ] 等符号", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (allSceneKeys().contains(name)) {
                        Toast.makeText(this, "已存在同名场景", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    customScenes.add(name);
                    saveCustomScenes();
                    invalidateClassificationsAndRescan();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptDeleteScene(String name) {
        new AlertDialog.Builder(this)
                .setTitle("删除场景「" + name + "」")
                .setMessage("从所有歌曲中移除这个标签，不会重新调 AI。")
                .setPositiveButton("删除", (d, w) -> deleteCustomScene(name))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteCustomScene(String name) {
        if (!customScenes.remove(name)) return;
        saveCustomScenes();
        if (hasAllFilesAccess()) {
            for (Song s : songByPath.values()) {
                Sidecar.Data d = sidecarByPath.get(s.path);
                if (d != null && d.classified && d.scenes.remove(name)) {
                    File sf = Sidecar.sidecarFor(s.path);
                    Sidecar.write(sf, d);
                }
            }
        }
        classifierPool = null;
        if (mode == Mode.SCENE_SONGS && name.equals(currentScene)) {
            showFolders();
        } else {
            refreshCurrentView();
        }
        Toast.makeText(this, "已删除场景「" + name + "」", Toast.LENGTH_SHORT).show();
    }

    private void invalidateClassificationsAndRescan() {
        classCache.clear();
        if (hasAllFilesAccess()) {
            for (Song s : songByPath.values()) {
                Sidecar.Data d = sidecarByPath.get(s.path);
                if (d != null && d.classified) {
                    d.classified = false;
                    d.scenes.clear();
                    File sf = Sidecar.sidecarFor(s.path);
                    Sidecar.write(sf, d);
                }
            }
        } else {
            for (Song s : songByPath.values()) {
                Sidecar.Data d = sidecarByPath.get(s.path);
                if (d != null) {
                    d.classified = false;
                    d.scenes.clear();
                }
            }
        }
        classifierPool = null;
        Toast.makeText(this, "已添加场景，开始重新分类", Toast.LENGTH_SHORT).show();
        refreshCurrentView();
        maybeStartClassifyScan();
    }

    private void showSongOptions(Song song) {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View content = getLayoutInflater().inflate(R.layout.dialog_song_actions, null);

        ((TextView) content.findViewById(R.id.sheetTitle)).setText(song.name);
        ((TextView) content.findViewById(R.id.sheetSubtitle)).setText(song.folder);

        LinearLayout sceneTagsContainer = content.findViewById(R.id.sceneTagsContainer);
        View sceneTagsLabel = content.findViewById(R.id.sceneTagsLabel);
        View sceneTagsDivider = content.findViewById(R.id.sceneTagsDivider);
        List<String> allScenes = allSceneKeys();
        if (allScenes.isEmpty()) {
            sceneTagsLabel.setVisibility(View.GONE);
            sceneTagsContainer.setVisibility(View.GONE);
            sceneTagsDivider.setVisibility(View.GONE);
        } else {
            LinkedHashSet<String> current = new LinkedHashSet<>(scenesForSong(song));
            for (String key : allScenes) {
                sceneTagsContainer.addView(buildSceneTagRow(song, key, current));
            }
        }

        content.findViewById(R.id.actionRename).setOnClickListener(v -> {
            sheet.dismiss();
            promptRename(song);
        });
        content.findViewById(R.id.actionSelect).setOnClickListener(v -> {
            sheet.dismiss();
            enterSelectionMode(song);
        });
        content.findViewById(R.id.actionDelete).setOnClickListener(v -> {
            sheet.dismiss();
            confirmDeleteSingle(song);
        });

        sheet.setContentView(content);
        View parent = (View) content.getParent();
        parent.setBackgroundColor(Color.TRANSPARENT);
        parent.setElevation(0f);
        parent.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(parent);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
        if (sheet.getWindow() != null) {
            sheet.getWindow().setDimAmount(0f);
            sheet.getWindow().setBackgroundDrawable(null);
            sheet.getWindow().clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        sheet.show();
    }

    private View buildSceneTagRow(Song song, String sceneKey, LinkedHashSet<String> current) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = (int) (24 * getResources().getDisplayMetrics().density);
        row.setPadding(padH, 0, padH, 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (48 * getResources().getDisplayMetrics().density)));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        row.setClickable(true);
        row.setFocusable(true);

        TextView name = new TextView(this);
        name.setText(sceneKey);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        name.setTextSize(15);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, nameLp);

        ImageView check = new ImageView(this);
        int sz = (int) (22 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(sz, sz);
        row.addView(check, checkLp);

        final boolean[] on = { current.contains(sceneKey) };
        check.setImageResource(on[0] ? R.drawable.ic_check_on : R.drawable.ic_check_off);

        row.setOnClickListener(v -> {
            on[0] = !on[0];
            check.setImageResource(on[0] ? R.drawable.ic_check_on : R.drawable.ic_check_off);
            if (on[0]) current.add(sceneKey);
            else current.remove(sceneKey);
            applyManualTags(song, current, false);
        });
        return row;
    }

    private void showTagEditor(Song song) {
        List<String> scenes = allSceneKeys();
        if (scenes.isEmpty()) {
            Toast.makeText(this, "还没有可用的场景，先去添加", Toast.LENGTH_SHORT).show();
            return;
        }
        Set<String> current = scenesForSong(song);
        String[] labels = scenes.toArray(new String[0]);
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) checked[i] = current.contains(labels[i]);

        new AlertDialog.Builder(this)
                .setTitle("场景标签  ·  " + song.name)
                .setMultiChoiceItems(labels, checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    LinkedHashSet<String> newTags = new LinkedHashSet<>();
                    for (int i = 0; i < labels.length; i++) {
                        if (checked[i]) newTags.add(labels[i]);
                    }
                    applyManualTags(song, newTags);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyManualTags(Song song, Set<String> tags) {
        applyManualTags(song, tags, true);
    }

    private void applyManualTags(Song song, Set<String> tags, boolean toast) {
        if (hasAllFilesAccess()) {
            File sf = Sidecar.sidecarFor(song.path);
            if (sf != null) {
                Sidecar.Data d = sidecarByPath.get(song.path);
                if (d == null) d = Sidecar.read(sf);
                d.scenes = new LinkedHashSet<>(tags);
                d.classified = true;
                Sidecar.write(sf, d);
                sidecarByPath.put(song.path, d);
            }
        } else {
            Sidecar.Data d = sidecarByPath.get(song.path);
            if (d == null) d = new Sidecar.Data();
            d.scenes = new LinkedHashSet<>(tags);
            d.classified = true;
            sidecarByPath.put(song.path, d);
        }
        classCache.put(queryKeyForSong(song), tags);
        refreshCurrentView();
        if (toast) Toast.makeText(this, "标签已更新", Toast.LENGTH_SHORT).show();
    }

    private void promptRename(Song song) {
        int dp = (int) getResources().getDisplayMetrics().density;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(song.name);
        input.setSelection(0, Math.max(0, song.name.lastIndexOf('.')));
        input.setTextColor(Color.parseColor("#FFF2F0EC"));
        input.setHintTextColor(Color.parseColor("#FF6B6B6B"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(16 * dp, 8 * dp, 16 * dp, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(song.name)) return;
                    if (newName.contains("/") || newName.contains("\\")) {
                        Toast.makeText(this, "名称不能含 / \\", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String oldExt = extOf(song.name);
                    String newExt = extOf(newName);
                    if (!oldExt.equalsIgnoreCase(newExt)) {
                        Toast.makeText(this, "扩展名不能改（." + oldExt + "）",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    renameSong(song, newName);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1);
    }

    private void renameSong(Song song, String newName) {
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, "需要文件访问权限", Toast.LENGTH_SHORT).show();
            return;
        }
        File oldFile = new File(song.path);
        File parent = oldFile.getParentFile();
        if (parent == null) { Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show(); return; }
        File newFile = new File(parent, newName);
        if (newFile.exists()) {
            Toast.makeText(this, "同名文件已存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!oldFile.renameTo(newFile)) {
            Toast.makeText(this, "重命名失败", Toast.LENGTH_SHORT).show();
            return;
        }
        File oldSf = Sidecar.sidecarFor(song.path);
        File newSf = Sidecar.sidecarFor(newFile.getAbsolutePath());
        if (oldSf != null && oldSf.exists() && newSf != null && !oldSf.equals(newSf)) {
            oldSf.renameTo(newSf);
        }
        final String oldPath = song.path;
        final String newPath = newFile.getAbsolutePath();
        MediaScannerConnection.scanFile(this, new String[]{oldPath, newPath}, null,
                (path, uri) -> runOnUiThread(this::reloadAfterFsChange));
        Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show();
    }

    private void confirmDeleteSingle(Song song) {
        new AlertDialog.Builder(this)
                .setTitle("删除")
                .setMessage("删除「" + song.name + "」？\n文件和它的 .lrc 一起删除，不可恢复。")
                .setPositiveButton("删除", (d, w) ->
                        deleteSongs(Collections.singletonList(song)))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteSelected() {
        int n = selectedPaths.size();
        if (n == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("删除 " + n + " 首")
                .setMessage("选中的 " + n + " 首歌（含 .lrc）将被删除，不可恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    List<Song> toDelete = new ArrayList<>();
                    for (String p : selectedPaths) {
                        Song s = songByPath.get(p);
                        if (s != null) toDelete.add(s);
                    }
                    exitSelectionMode();
                    deleteSongs(toDelete);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSongs(List<Song> songs) {
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, "需要文件访问权限", Toast.LENGTH_SHORT).show();
            return;
        }
        int ok = 0;
        List<String> paths = new ArrayList<>();
        for (Song s : songs) {
            paths.add(s.path);
            File f = new File(s.path);
            if (f.exists() && f.delete()) ok++;
            File sf = Sidecar.sidecarFor(s.path);
            if (sf != null && sf.exists()) sf.delete();
            if (s.path.equals(currentSongOrNullPath())) {
                stopPlayer();
                nowPlaying.setText("未播放");
                nowMeta.setText("");
                playingIndex = -1;
                playQueue = Collections.emptyList();
                clearLyrics();
                updatePlayButton();
                adapter.setPlayingPath(null);
            }
        }
        MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null,
                (path, uri) -> runOnUiThread(this::reloadAfterFsChange));
        Toast.makeText(this, "已删除 " + ok + " 首", Toast.LENGTH_SHORT).show();
    }

    private String currentSongOrNullPath() {
        Song s = currentSongOrNull();
        return s == null ? null : s.path;
    }

    private void reloadAfterFsChange() {
        loadSongs();
        refreshCurrentView();
    }

    private void enterSelectionMode(Song initial) {
        if (mode != Mode.SONGS && mode != Mode.SCENE_SONGS) return;
        selectionMode = true;
        selectedPaths.clear();
        if (initial != null) selectedPaths.add(initial.path);
        updateTopBar();
        adapter.setSelectedKeys(selectedPaths);
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedPaths.clear();
        updateTopBar();
        adapter.setSelectedKeys(null);
    }

    private void toggleSelection(Song song) {
        if (!selectedPaths.add(song.path)) selectedPaths.remove(song.path);
        if (selectedPaths.isEmpty()) {
            exitSelectionMode();
        } else {
            updateTopBar();
            adapter.setSelectedKeys(selectedPaths);
        }
    }

    private void updateTopBar() {
        if (selectionMode) {
            btnBack.setVisibility(View.VISIBLE);
            btnBack.setImageResource(R.drawable.ic_close);
            btnBack.setOnClickListener(v -> exitSelectionMode());
            topTitle.setText(selectedPaths.size() + " 已选");
            btnSettings.setImageResource(R.drawable.ic_delete);
            btnSettings.setOnClickListener(v -> confirmDeleteSelected());
        } else {
            btnBack.setVisibility((mode == Mode.SONGS || mode == Mode.SCENE_SONGS)
                    ? View.VISIBLE : View.GONE);
            btnBack.setImageResource(R.drawable.ic_back);
            btnBack.setOnClickListener(v -> {
                if (mode == Mode.SONGS || mode == Mode.SCENE_SONGS) showFolders();
            });
            topTitle.setText(getString(R.string.app_name));
            btnSettings.setImageResource(R.drawable.ic_settings);
            btnSettings.setOnClickListener(v -> openSettings());
        }
    }

    private void confirmDeleteFolder(String name) {
        List<Song> inFolder = folders.get(name);
        int count = inFolder == null ? 0 : inFolder.size();
        String msg = count > 0
                ? "「" + name + "」里有 " + count + " 首歌，删除文件夹会一并删除所有歌曲和它们的 .lrc，不可恢复。"
                : "「" + name + "」是空文件夹，确认删除？";
        new AlertDialog.Builder(this)
                .setTitle("删除文件夹")
                .setMessage(msg)
                .setPositiveButton("删除", (d, w) -> deleteFolder(name))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteFolder(String name) {
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, "需要文件访问权限", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = new File(scanRoot(), name);
        if (!dir.exists()) {
            Toast.makeText(this, "文件夹已不存在", Toast.LENGTH_SHORT).show();
            reloadAfterFsChange();
            return;
        }

        Song current = currentSongOrNull();
        if (current != null && name.equals(current.folder)) {
            stopPlayer();
            nowPlaying.setText("未播放");
            nowMeta.setText("");
            playingIndex = -1;
            playQueue = Collections.emptyList();
            clearLyrics();
            updatePlayButton();
            adapter.setPlayingPath(null);
            progressBar.setProgress(0);
            timeCurrent.setText("--:--");
            timeTotal.setText("--:--");
        }

        if (mode == Mode.SONGS && name.equals(currentFolder)) {
            showFolders();
        }

        List<String> paths = new ArrayList<>();
        collectAllFiles(dir, paths);
        boolean ok = deleteRecursive(dir);

        if (!paths.isEmpty()) {
            MediaScannerConnection.scanFile(this, paths.toArray(new String[0]), null,
                    (path, uri) -> runOnUiThread(this::reloadAfterFsChange));
        } else {
            reloadAfterFsChange();
        }
        Toast.makeText(this, ok ? "已删除：" + name : "删除失败", Toast.LENGTH_SHORT).show();
    }

    private static void collectAllFiles(File dir, List<String> out) {
        if (dir == null || !dir.exists()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collectAllFiles(k, out);
            else out.add(k.getAbsolutePath());
        }
    }

    private static boolean deleteRecursive(File f) {
        if (f == null) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        return f.delete();
    }

    private void promptNewFolder() {
        int dp = (int) getResources().getDisplayMetrics().density;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("文件夹名");
        input.setTextColor(Color.parseColor("#FFF2F0EC"));
        input.setHintTextColor(Color.parseColor("#FF6B6B6B"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(16 * dp, 8 * dp, 16 * dp, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("新建文件夹")
                .setMessage("在 " + STORAGE_LABEL + "/" + scanRootDir + "/ 下创建新文件夹，之后可以把音乐放进去。")
                .setView(wrap)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (name.contains("/") || name.contains("\\")
                            || name.contains(":") || name.contains("*")
                            || name.contains("?")) {
                        Toast.makeText(this, "名称不能含 / \\ : * ? 等符号",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createFolder(name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createFolder(String name) {
        if (!hasAllFilesAccess()) {
            Toast.makeText(this, "需要文件访问权限", Toast.LENGTH_SHORT).show();
            return;
        }
        File musicDir = scanRoot();
        if (!musicDir.exists()) musicDir.mkdirs();
        File dir = new File(musicDir, name);
        if (dir.exists()) {
            Toast.makeText(this, "已存在同名文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!dir.mkdirs()) {
            Toast.makeText(this, "创建失败", Toast.LENGTH_SHORT).show();
            return;
        }
        folders.computeIfAbsent(name, k -> new ArrayList<>());
        folderNames.clear();
        folderNames.addAll(folders.keySet());
        refreshCurrentView();
        Toast.makeText(this, "已创建：" + name, Toast.LENGTH_SHORT).show();
    }

    private void writeClassificationToSidecars(Map<String, Set<String>> results) {
        if (!hasAllFilesAccess()) return;
        for (Song s : songByPath.values()) {
            String qk = queryKeyForSong(s);
            if (!results.containsKey(qk)) continue;
            Set<String> scenes = results.get(qk);
            File sf = Sidecar.sidecarFor(s.path);
            if (sf == null) continue;
            Sidecar.Data d = sidecarByPath.get(s.path);
            if (d == null) d = Sidecar.read(sf);
            d.scenes = new LinkedHashSet<>(scenes == null ? Collections.emptySet() : scenes);
            d.classified = true;
            Sidecar.write(sf, d);
            sidecarByPath.put(s.path, d);
        }
    }

    private void refreshCurrentView() {
        if (mode == Mode.FOLDERS) {
            showFolders();
        } else if (mode == Mode.SCENE_SONGS && currentScene != null) {
            openScene(currentScene);
        } else if (mode == Mode.SONGS && currentFolder != null) {
            updateSectionMeta();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        classifierPool = null;

        SharedPreferences sp = Providers.prefs(this);
        boolean rescanAll = Providers.takeRescanAllPending(sp);
        boolean rescanPending = Providers.takeRescanPending(sp);

        String latestDir = Providers.getScanDir(sp);
        boolean dirChanged = !latestDir.equals(scanRootDir);
        if (dirChanged) {
            scanRootDir = latestDir;
        }

        if (rescanAll) {
            if (hasPermission()) {
                if (songByPath.isEmpty() || dirChanged) loadSongs();
                invalidateClassificationsAndRescan();
            }
            return;
        }

        if (dirChanged && hasPermission()) {
            loadAndShow();
            return;
        }

        if (hasAllFilesAccess() && sidecarByPath.isEmpty() && !songByPath.isEmpty()) {
            for (Song s : songByPath.values()) {
                File sf = Sidecar.sidecarFor(s.path);
                if (sf != null && sf.exists()) {
                    sidecarByPath.put(s.path, Sidecar.read(sf));
                }
            }
            refreshCurrentView();
        }

        if (rescanPending && hasPermission()) {
            loadSongs();
            refreshCurrentView();
        }
        maybeStartClassifyScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayer();
    }
}
