package com.example.helloworld;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERM = 1001;
    private static final String DEFAULT_DIR = "/Music/";
    private static final String ROOT_LABEL = "(根目录)";
    private static final String PREFS = Providers.PREFS;
    private static final String KEY_CUSTOM_SCENES = "custom_scenes";
    private static final int BATCH_SIZE = 10;

    private static final List<String> BUILTIN_ICONS = Arrays.asList(
            "🏃", "🚶", "🎯", "🧘", "💪", "🌙");

    private final List<String> customScenes = new ArrayList<>();

    private enum Mode { FOLDERS, SONGS, SCENE_SONGS }

    private enum PlayMode {
        SEQUENTIAL("顺序"),
        LOOP_ALL("🔁 循环"),
        REPEAT_ONE("🔂 单曲"),
        SHUFFLE("🔀 随机");

        final String label;
        PlayMode(String label) { this.label = label; }
    }

    private TextView dirText;
    private TextView nowPlaying;
    private TextView currentLyric;
    private TextView breadcrumb;
    private ListView listView;
    private Button btnMode, btnPrev, btnPlay, btnNext;

    private View lyricsOverlay;
    private TextView lyricsTitle;
    private ScrollView lyricsScroll;
    private LinearLayout lyricsContainer;

    private final Map<String, List<Song>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<String> folderNames = new ArrayList<>();
    private final Map<String, Song> songByPath = new HashMap<>();
    private final Map<String, Sidecar.Data> sidecarByPath = new HashMap<>();
    private ArrayAdapter<String> adapter;

    private Mode mode = Mode.FOLDERS;
    private String currentFolder = null;
    private String currentScene = null;

    private MediaPlayer player;
    private boolean isPrepared = false;
    private List<Song> playQueue = Collections.emptyList();
    private int playingIndex = -1;
    private String playSourceLabel = null;

    private PlayMode playMode = PlayMode.SEQUENTIAL;
    private final Random random = new Random();

    private List<LrcParser.Line> lyrics = Collections.emptyList();
    private int currentLyricIndex = -1;
    private long lyricsRequestToken = 0;
    private LyricsFetcher fetcher;

    private ClassificationCache classCache;
    private ClassifierPool classifierPool;
    private boolean scanning = false;
    private int scanDone = 0;
    private int scanTotal = 0;

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dirText = findViewById(R.id.dirText);
        nowPlaying = findViewById(R.id.nowPlaying);
        currentLyric = findViewById(R.id.currentLyric);
        breadcrumb = findViewById(R.id.breadcrumb);
        listView = findViewById(R.id.songList);
        btnMode = findViewById(R.id.btnMode);
        btnPrev = findViewById(R.id.btnPrev);
        btnPlay = findViewById(R.id.btnPlay);
        btnNext = findViewById(R.id.btnNext);

        lyricsOverlay = findViewById(R.id.lyricsOverlay);
        lyricsTitle = findViewById(R.id.lyricsTitle);
        lyricsScroll = findViewById(R.id.lyricsScroll);
        lyricsContainer = findViewById(R.id.lyricsContainer);
        findViewById(R.id.btnCloseLyrics).setOnClickListener(v -> hideLyrics());

        fetcher = new LyricsFetcher(this);
        classCache = new ClassificationCache(this);
        Providers.migrateLegacyPrefs(Providers.prefs(this));
        loadCustomScenes();

        dirText.setText("扫描目录：" + DEFAULT_DIR);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (mode == Mode.FOLDERS) {
                if (position == 0) {
                    openSettings();
                    return;
                }
                int rel = position - 1;
                List<String> scenes = allSceneKeys();
                if (rel < scenes.size()) {
                    onSceneClicked(scenes.get(rel));
                } else if (rel == scenes.size()) {
                    promptAddScene();
                } else {
                    int folderIdx = rel - scenes.size() - 1;
                    if (folderIdx < folderNames.size()) openFolder(folderNames.get(folderIdx));
                }
            } else if (mode == Mode.SONGS) {
                List<Song> list = folders.get(currentFolder);
                if (list != null) play(list, position, currentFolder);
            } else if (mode == Mode.SCENE_SONGS) {
                if (position < playQueue.size() || currentScene != null) {
                    List<Song> list = songsForScene(currentScene);
                    play(list, position, sceneDisplayName(currentScene));
                }
            }
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (mode == Mode.FOLDERS && position > 0) {
                int rel = position - 1;
                List<String> scenes = allSceneKeys();
                if (rel < scenes.size()) {
                    String key = scenes.get(rel);
                    if (AiClassifier.BUILTIN_SCENES.contains(key)) {
                        openSettings();
                    } else {
                        promptDeleteScene(key);
                    }
                    return true;
                }
            }
            return false;
        });

        breadcrumb.setOnClickListener(v -> {
            if (mode == Mode.SONGS || mode == Mode.SCENE_SONGS) showFolders();
        });

        findViewById(R.id.header).setOnClickListener(v -> {
            if (playingIndex >= 0) showLyrics();
        });

        btnMode.setOnClickListener(v -> cyclePlayMode());
        btnPrev.setOnClickListener(v -> playPrev());
        btnPlay.setOnClickListener(v -> togglePlayPause());
        btnNext.setOnClickListener(v -> playNext(false));

        updatePlayButton();
        updateModeButton();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
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
                .setTitle("📁 文件访问权限")
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
                    "✓ 已把 " + migrated + " 首歌的场景标签写入 .lrc",
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
        int total = 0;

        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA
        };
        String selection = MediaStore.Audio.Media.DATA + " LIKE ? OR " + MediaStore.Audio.Media.DATA + " LIKE ?";
        String[] args = {"%/Music/%", "%/music/%"};
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
                    total++;
                }
            }
        }

        folderNames.addAll(folders.keySet());

        if (hasAllFilesAccess()) {
            for (Song s : songByPath.values()) {
                File sf = Sidecar.sidecarFor(s.path);
                if (sf != null && sf.exists()) {
                    sidecarByPath.put(s.path, Sidecar.read(sf));
                }
            }
        }

        updateDirText(total);

        if (folderNames.isEmpty()) {
            Toast.makeText(this, "未找到音乐文件，请检查 /Music 目录", Toast.LENGTH_LONG).show();
        }
    }

    private void updateDirText(int totalSongs) {
        StringBuilder sb = new StringBuilder();
        sb.append("扫描目录：").append(DEFAULT_DIR)
                .append("    ").append(folderNames.size())
                .append(" 个分类 / 共 ").append(totalSongs).append(" 首");
        if (scanning) {
            sb.append("    🤖 AI 分类 ").append(scanDone).append("/").append(scanTotal);
        }
        dirText.setText(sb.toString());
    }

    private static String extractFolder(String path) {
        if (path == null) return null;
        int idx = path.indexOf("/Music/");
        int len = "/Music/".length();
        if (idx < 0) {
            idx = path.indexOf("/music/");
            len = "/music/".length();
        }
        if (idx < 0) return null;
        String rest = path.substring(idx + len);
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

    private static String sceneIconFor(String key) {
        int i = AiClassifier.BUILTIN_SCENES.indexOf(key);
        if (i >= 0) return BUILTIN_ICONS.get(i);
        return "🎵";
    }

    private static String sceneDisplayName(String key) {
        if (key == null) return "";
        return sceneIconFor(key) + " " + key;
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
        List<Song> out = new ArrayList<>();
        for (Song s : songByPath.values()) {
            if (scenesForSong(s).contains(sceneKey)) out.add(s);
        }
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
        breadcrumb.setText("全部分类");

        adapter.clear();
        adapter.add("⚙️  设置  (AI 服务商 / API Key)");
        for (String key : allSceneKeys()) {
            int count = countSongsInScene(key);
            adapter.add(sceneIconFor(key) + " " + key + "    (" + count + " 首)");
        }
        adapter.add("➕ 添加场景");
        for (String name : folderNames) {
            adapter.add("📁 " + name + "    (" + folders.get(name).size() + " 首)");
        }
        adapter.notifyDataSetChanged();
        listView.smoothScrollToPosition(0);
    }

    private void openFolder(String folder) {
        mode = Mode.SONGS;
        currentFolder = folder;
        currentScene = null;
        breadcrumb.setText("← 返回   /   📁 " + folder);

        adapter.clear();
        for (Song s : folders.get(folder)) adapter.add(s.name);
        adapter.notifyDataSetChanged();

        listView.smoothScrollToPosition(0);
    }

    private void onSceneClicked(String sceneKey) {
        if (!Providers.buildPool(this, allSceneKeys()).isEmpty()) {
            openScene(sceneKey);
            return;
        }
        Toast.makeText(this,
                "智能场景需要先配置至少一家 AI 服务商，点上方「⚙️ 设置」",
                Toast.LENGTH_LONG).show();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void openScene(String sceneKey) {
        mode = Mode.SCENE_SONGS;
        currentScene = sceneKey;
        currentFolder = null;
        breadcrumb.setText("← 返回   /   " + sceneDisplayName(sceneKey));

        List<Song> list = songsForScene(sceneKey);
        adapter.clear();
        if (list.isEmpty()) {
            if (scanning) {
                adapter.add("（AI 分类中，请稍后回来…）");
            } else if (classCache.size() == 0) {
                adapter.add("（尚未开始 AI 分类，长按场景图标可重设 API Key）");
            } else {
                adapter.add("（暂无歌曲符合此场景）");
            }
        } else {
            for (Song s : list) {
                adapter.add(s.name + "    ·    " + s.folder);
            }
        }
        adapter.notifyDataSetChanged();
        listView.smoothScrollToPosition(0);
    }

    private void play(List<Song> queue, int position, String label) {
        if (queue == null || queue.isEmpty() || position < 0 || position >= queue.size()) {
            stopPlayer();
            nowPlaying.setText("播放完毕");
            playingIndex = -1;
            playSourceLabel = null;
            playQueue = Collections.emptyList();
            clearLyrics();
            updatePlayButton();
            return;
        }

        playQueue = queue;
        playSourceLabel = label;
        playingIndex = position;
        Song song = queue.get(position);
        nowPlaying.setText("▶ " + label + " / " + song.name);

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
                currentLyric.setVisibility(View.VISIBLE);
                tickLyrics();
            } else {
                currentLyric.setText("未找到歌词");
                currentLyric.setVisibility(View.VISIBLE);
            }
            return;
        }

        currentLyric.setText("🔍 正在查找歌词…");
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
                currentLyric.setText(error != null ? "未找到歌词（网络错误）" : "未找到歌词");
                currentLyric.setVisibility(View.VISIBLE);
                return;
            }
            lyrics = lines;
            populateLyricsContainer();
            currentLyric.setText("");
            currentLyric.setVisibility(View.VISIBLE);
            tickLyrics();
        });
    }

    private void populateLyricsContainer() {
        lyricsContainer.removeAllViews();
        for (LrcParser.Line line : lyrics) {
            TextView tv = new TextView(this);
            tv.setText(line.text.isEmpty() ? " " : line.text);
            tv.setTextColor(Color.parseColor("#FF888888"));
            tv.setTextSize(16f);
            tv.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
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
            highlightInContainer(idx);
        } else {
            currentLyric.setText("");
            highlightInContainer(-1);
        }
    }

    private void highlightInContainer(int idx) {
        int n = lyricsContainer.getChildCount();
        for (int i = 0; i < n; i++) {
            TextView tv = (TextView) lyricsContainer.getChildAt(i);
            if (i == idx) {
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(20f);
            } else {
                tv.setTextColor(Color.parseColor("#FF888888"));
                tv.setTextSize(16f);
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

    private void showLyrics() {
        if (lyrics.isEmpty()) {
            Toast.makeText(this, "当前歌曲暂无歌词", Toast.LENGTH_SHORT).show();
            return;
        }
        Song s = currentSongOrNull();
        lyricsTitle.setText(s == null ? "" : s.name);
        lyricsOverlay.setVisibility(View.VISIBLE);
        if (currentLyricIndex >= 0) scrollLyricToCenter(currentLyricIndex);
    }

    private void hideLyrics() {
        lyricsOverlay.setVisibility(View.GONE);
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
            case SEQUENTIAL:
                newIdx = playingIndex + 1;
                if (newIdx >= n) {
                    if (autoFromCompletion) {
                        stopPlayer();
                        nowPlaying.setText("播放完毕");
                        playingIndex = -1;
                        clearLyrics();
                        updatePlayButton();
                        return;
                    }
                    newIdx = 0;
                }
                break;
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
                newIdx = playingIndex + 1;
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
            if (!list.isEmpty()) play(list, 0, sceneDisplayName(currentScene));
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
        Toast.makeText(this, "播放模式：" + playMode.label, Toast.LENGTH_SHORT).show();
    }

    private void updateModeButton() { btnMode.setText(playMode.label); }

    private void updatePlayButton() {
        boolean playing = false;
        try {
            playing = player != null && isPrepared && player.isPlaying();
        } catch (IllegalStateException ignored) {}
        btnPlay.setText(playing ? "⏸" : "▶");
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
        updateDirText(songByPath.size());
        runNextBatch(todo, 0);
    }

    private void runNextBatch(final List<AiClassifier.Item> all, final int from) {
        if (from >= all.size()) {
            scanning = false;
            updateDirText(songByPath.size());
            refreshCurrentView();
            Toast.makeText(this, "AI 分类完成 ✓", Toast.LENGTH_SHORT).show();
            return;
        }
        int to = Math.min(from + BATCH_SIZE, all.size());
        List<AiClassifier.Item> batch = all.subList(from, to);
        classifierPool.classifyBatch(batch, (results, error) -> {
            if (error != null) {
                scanning = false;
                updateDirText(songByPath.size());
                Toast.makeText(this, "所有 AI 服务商都失败：\n" + error, Toast.LENGTH_LONG).show();
                return;
            }
            classCache.putBatch(results);
            writeClassificationToSidecars(results);
            scanDone = to;
            updateDirText(songByPath.size());
            refreshCurrentView();
            runNextBatch(all, to);
        });
    }

    private void promptAddScene() {
        int dp = (int) getResources().getDisplayMetrics().density;
        int pad = 16 * dp;

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("如：开车、午休、做饭…");
        input.setTextColor(Color.parseColor("#FF222222"));
        input.setHintTextColor(Color.parseColor("#FF888888"));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(pad, 8 * dp, pad, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("➕ 添加场景")
                .setMessage("自定义一个场景，AI 会根据歌名/歌手判断每首歌是否适合。\n注意：添加后会重新分类所有歌曲（可能几分钟）。")
                .setView(wrap)
                .setPositiveButton("添加", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
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
                .setMessage("从所有歌曲中移除「" + name + "」标签，不会重新调 AI。")
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
        Toast.makeText(this, "已添加场景，开始重新分类…", Toast.LENGTH_SHORT).show();
        refreshCurrentView();
        maybeStartClassifyScan();
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
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        classifierPool = null;
        if (hasAllFilesAccess() && sidecarByPath.isEmpty() && !songByPath.isEmpty()) {
            for (Song s : songByPath.values()) {
                File sf = Sidecar.sidecarFor(s.path);
                if (sf != null && sf.exists()) {
                    sidecarByPath.put(s.path, Sidecar.read(sf));
                }
            }
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
