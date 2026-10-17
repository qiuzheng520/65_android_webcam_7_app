package com.example.webcam7;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 收藏列表界面：展示所有已收藏的视频流地址。
 * 支持点击播放、取消收藏、长按删除单条、菜单一键清空。
 */
public class FavoriteActivity extends AppCompatActivity implements StreamAdapter.Listener {

    private static final String TAG = "FavoriteActivity";

    /** 预览模式状态保存键。 */
    private static final String STATE_PREVIEW_MODE = "state_preview_mode";

    /** GitHub 加速镜像前缀：原始地址下载失败时依次拼接重试。 */
    private static final String[] GITHUB_MIRRORS = {
            "https://ghfast.top/",
    };

    private StreamAdapter adapter;
    private TextView tvEmpty;
    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;

    /** 是否处于预览（网格）模式。 */
    private boolean previewMode;

    /** 导入 / 导出的系统文件选择器。 */
    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            previewMode = savedInstanceState.getBoolean(STATE_PREVIEW_MODE, false);
        }
        applyFullscreenTop();
        setContentView(R.layout.activity_favorite);

        registerFileLaunchers();
        setupToolbar();
        setupRecyclerView();
        setupBottomNav();
    }

    /** 注册“导出/导入”所需的系统文件选择器回调。 */
    private void registerFileLaunchers() {
        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) {
                        exportTo(uri);
                    }
                });
        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        importFrom(uri);
                    }
                });
    }

    /**
     * 沉浸式顶部：内容延伸至状态栏区域并隐藏系统状态栏（时间、电量等），
     * 由应用顶部工具栏覆盖该区域。
     */
    private void applyFullscreenTop() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars());
            controller.setSystemBarsBehavior(
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** 底部菜单：第一页“搜索”，第二页“收藏”。 */
    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setSelectedItemId(R.id.nav_favorite);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_search) {
                // 切换到第一页：搜索页
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                return true;
            }
            return id == R.id.nav_favorite;
        });
    }

    /** 初始化工具栏并显示返回箭头。 */
    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_favorite);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    /** 初始化收藏列表。 */
    private void setupRecyclerView() {
        tvEmpty = findViewById(R.id.tvEmpty);
        recyclerView = findViewById(R.id.recyclerFavorites);
        adapter = new StreamAdapter(true, this);
        adapter.setPreviewMode(previewMode);
        recyclerView.setAdapter(adapter);
        applyLayoutManager();
        setupSwipeRefresh();
        refreshList();
    }

    /** 顶部下拉刷新：重新加载收藏并停止刷新动画。 */
    private void setupSwipeRefresh() {
        swipeRefresh = findViewById(R.id.swipeRefresh);
        swipeRefresh.setColorSchemeColors(
                getResources().getColor(R.color.colorPrimary, getTheme()));
        swipeRefresh.setOnRefreshListener(() -> {
            refreshList();
            swipeRefresh.postDelayed(() -> swipeRefresh.setRefreshing(false), 300);
        });
    }

    /** 根据当前模式设置布局管理器：列表 / 网格（每行 2 个）。 */
    private void applyLayoutManager() {
        if (recyclerView == null) {
            return;
        }
        if (previewMode) {
            recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
        }
    }

    /** 切换列表模式 / 预览模式。 */
    private void toggleViewMode() {
        previewMode = !previewMode;
        adapter.setPreviewMode(previewMode);
        applyLayoutManager();
        invalidateOptionsMenu();
    }

    /** 从持久化存储重新加载收藏并刷新界面。 */
    private void refreshList() {
        List<Favorite> favorites = FavoriteManager.getFavorites(this);
        adapter.setFavorites(favorites);
        tvEmpty.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(getString(R.string.count_format, favorites.size()));
        }
        // 展示归属国家：优先用缓存，未缓存的异步查询
        for (Favorite f : favorites) {
            final String urlKey = f.url;
            String cached = GeoIpManager.getCached(this, urlKey);
            if (cached != null) {
                adapter.setCountry(urlKey, cached);
            } else {
                GeoIpManager.lookupCountry(this, urlKey,
                        country -> adapter.setCountry(urlKey, country));
            }
        }
    }

    // ================= StreamAdapter.Listener 回调 =================

    @Override
    public void onPlay(String url) {
        try {
            PlayerActivity.start(this, url);
        } catch (Exception e) {
            Log.e(TAG, "启动播放器失败", e);
            Toast.makeText(this, R.string.toast_play_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onFavorite(String url) {
        // 收藏页中“取消收藏”按钮
        removeOne(url);
    }

    @Override
    public void onDelete(String url) {
        // 长按删除单条
        removeOne(url);
    }

    @Override
    public void onTag(String url) {
        // 添加 / 修改标签
        showTagDialog(url);
    }

    /** 弹出输入框，添加或修改收藏项的标签。 */
    private void showTagDialog(final String url) {
        // 取当前标签作为初始内容
        String current = "";
        for (Favorite f : FavoriteManager.getFavorites(this)) {
            if (url.equals(f.url)) {
                current = f.tag;
                break;
            }
        }

        final EditText input = new EditText(this);
        input.setHint(R.string.tag_hint);
        input.setText(current);
        input.setSelection(input.getText().length());

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.tag_title)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String tag = input.getText().toString().trim();
                    FavoriteManager.updateTag(FavoriteActivity.this, url, tag);
                    adapter.setTag(url, tag);
                    Toast.makeText(FavoriteActivity.this,
                            R.string.tag_saved, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** 取消 / 删除单条收藏。 */
    private void removeOne(String url) {
        boolean removed = FavoriteManager.removeFavorite(this, url);
        if (removed) {
            refreshList();
            Toast.makeText(this, R.string.toast_favorite_removed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 弹窗确认后一键清空收藏。 */
    private void confirmClearAll() {
        if (FavoriteManager.getFavorites(this).isEmpty()) {
            Toast.makeText(this, R.string.empty_favorite, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_clear_all)
                .setMessage(R.string.action_clear_all)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        FavoriteManager.clearFavorites(FavoriteActivity.this);
                        refreshList();
                        Toast.makeText(FavoriteActivity.this,
                                R.string.toast_favorite_cleared, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ================= 导入 / 导出 =================

    /** 导出收藏：弹出系统“保存文件”选择器。 */
    private void exportFavorites() {
        if (FavoriteManager.getFavorites(this).isEmpty()) {
            Toast.makeText(this, R.string.empty_favorite, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "webcam7_favorites_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + ".json";
        exportLauncher.launch(name);
    }

    /** 导入收藏：弹出系统“打开文件”选择器。 */
    private void importFavorites() {
        importLauncher.launch(new String[]{
                "application/json", "text/plain", "application/octet-stream", "*/*"});
    }

    /** 后台线程把收藏写入用户选定的文件。 */
    private void exportTo(final Uri uri) {
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            boolean ok = false;
            try {
                String json = FavoriteManager.exportToJson(appContext);
                try (OutputStream os = appContext.getContentResolver().openOutputStream(uri, "wt")) {
                    if (os != null) {
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        ok = true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "导出收藏失败", e);
            }
            final boolean success = ok;
            mainHandler.post(() -> Toast.makeText(this,
                    success ? R.string.toast_export_success : R.string.toast_export_failed,
                    Toast.LENGTH_SHORT).show());
        });
    }

    /** 后台线程读取用户选定的文件并合并到收藏。 */
    private void importFrom(final Uri uri) {
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            String content = null;
            try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    content = readAll(in);
                }
            } catch (Exception e) {
                Log.e(TAG, "读取导入文件失败", e);
            }
            final String json = content;
            mainHandler.post(() -> {
                if (TextUtils.isEmpty(json)) {
                    Toast.makeText(this, R.string.toast_import_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                FavoriteManager.ImportResult result =
                        FavoriteManager.importFromContent(this, json);
                if (!result.valid) {
                    Toast.makeText(this, R.string.toast_import_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                refreshList();
                Toast.makeText(this, getString(R.string.toast_import_result,
                        result.added, result.updated, result.skipped),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 读取输入流全部文本（UTF-8）。 */
    private String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        }
        return sb.toString();
    }

    // ================= 从网址导入 / 手动添加 =================

    /** 弹窗输入网页/接口地址，下载后合并到收藏。 */
    private void showImportUrlDialog() {
        final EditText input = new EditText(this);
        input.setHint(R.string.hint_import_url);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(R.string.default_import_url);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_import_url)
                .setView(wrapInPaddedContainer(input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = input.getText().toString().trim();
                    if (TextUtils.isEmpty(url)) {
                        return;
                    }
                    importFromUrl(url);
                })
                .show();
    }

    /** 后台下载地址内容并导入。 */
    private void importFromUrl(final String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null) {
            Toast.makeText(this, R.string.toast_address_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.toast_importing, Toast.LENGTH_SHORT).show();
        final Context appContext = getApplicationContext();
        ioExecutor.execute(() -> {
            String content = null;
            try {
                content = downloadTextWithFallback(url);
            } catch (Exception e) {
                Log.e(TAG, "下载导入数据失败", e);
            }
            final String data = content;
            mainHandler.post(() -> {
                if (data == null) {
                    Toast.makeText(this, R.string.toast_import_network, Toast.LENGTH_LONG).show();
                    return;
                }
                FavoriteManager.ImportResult result =
                        FavoriteManager.importFromContent(this, data);
                if (!result.valid) {
                    Toast.makeText(this, R.string.toast_import_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (result.added == 0 && result.updated == 0 && result.skipped == 0) {
                    Toast.makeText(this, R.string.toast_import_nothing, Toast.LENGTH_SHORT).show();
                    return;
                }
                refreshList();
                Toast.makeText(this, getString(R.string.toast_import_result,
                        result.added, result.updated, result.skipped),
                        Toast.LENGTH_LONG).show();
            });
        });
    }

    /** 弹窗手工输入视频流地址与可选标签。 */
    private void showAddManualDialog() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        final EditText etUrl = new EditText(this);
        etUrl.setHint(R.string.hint_address);
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        final EditText etTag = new EditText(this);
        etTag.setHint(R.string.hint_tag_optional);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(etUrl);
        container.addView(etTag);

        new AlertDialog.Builder(this)
                .setTitle(R.string.action_add_manual)
                .setView(container)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String url = normalizeUrl(etUrl.getText().toString().trim());
                    if (url == null) {
                        Toast.makeText(this, R.string.toast_address_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (FavoriteManager.contains(this, url)) {
                        Toast.makeText(this, R.string.toast_add_exist, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String tag = etTag.getText().toString().trim();
                    boolean ok = FavoriteManager.addFavorite(this, url, tag);
                    if (ok) {
                        refreshList();
                        Toast.makeText(this, R.string.toast_favorite_added,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.toast_add_exist,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /** 把控件包进带内边距的容器，供对话框使用。 */
    private FrameLayout wrapInPaddedContainer(View content) {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(content);
        return container;
    }

    /** 规范化地址：缺省协议补 http://，非法返回 null。 */
    private String normalizeUrl(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }
        String url = raw.trim();
        if (!url.matches("(?i)^https?://.*")) {
            url = "http://" + url;
        }
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (uri.getHost() == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return null;
        }
        return url;
    }

    /** 下载文本内容：先请求原始地址，失败后依次尝试镜像加速地址。 */
    private String downloadTextWithFallback(String rawUrl) throws Exception {
        Exception lastError = null;
        try {
            String content = downloadText(rawUrl);
            if (!TextUtils.isEmpty(content)) {
                return content;
            }
            Log.w(TAG, "原始地址下载内容为空");
        } catch (Exception e) {
            lastError = e;
            Log.w(TAG, "原始地址下载失败，尝试镜像", e);
        }
        // GitHub 地址在部分网络下无法直连，追加镜像加速重试
        if (!rawUrl.contains("github.com") && !rawUrl.contains("githubusercontent.com")) {
            return null;
        }
        for (String mirror : GITHUB_MIRRORS) {
            try {
                String content = downloadText(mirror + rawUrl);
                if (!TextUtils.isEmpty(content)) {
                    return content;
                }
                Log.w(TAG, "镜像下载内容为空: " + mirror);
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "镜像下载失败: " + mirror, e);
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    /** 下载文本内容（UTF-8）。 */
    private String downloadText(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Webcam7");
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "下载导入数据失败: " + conn.getResponseCode());
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return readAll(in);
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ================= 菜单 =================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_favorite, menu);
        // 依据当前模式更新切换按钮的图标与文案
        MenuItem toggle = menu.findItem(R.id.action_toggle_view);
        if (toggle != null) {
            if (previewMode) {
                toggle.setIcon(R.drawable.ic_view_list);
                toggle.setTitle(R.string.action_view_list);
            } else {
                toggle.setIcon(R.drawable.ic_view_grid);
                toggle.setTitle(R.string.action_view_preview);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_clear_all) {
            confirmClearAll();
            return true;
        }
        if (item.getItemId() == R.id.action_export) {
            exportFavorites();
            return true;
        }
        if (item.getItemId() == R.id.action_import) {
            importFavorites();
            return true;
        }
        if (item.getItemId() == R.id.action_import_url) {
            showImportUrlDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_add_manual) {
            showAddManualDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_toggle_view) {
            toggleViewMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放器返回时同步最新收藏
        if (adapter != null) {
            refreshList();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PREVIEW_MODE, previewMode);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ioExecutor.shutdownNow();
    }
}
