package com.example.webcam7;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.List;

/**
 * 收藏列表界面：展示所有已收藏的视频流地址。
 * 支持点击播放、取消收藏、长按删除单条、菜单一键清空。
 */
public class FavoriteActivity extends AppCompatActivity implements StreamAdapter.Listener {

    private static final String TAG = "FavoriteActivity";

    private StreamAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFullscreenTop();
        setContentView(R.layout.activity_favorite);

        setupToolbar();
        setupRecyclerView();
        setupBottomNav();
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
        RecyclerView recyclerView = findViewById(R.id.recyclerFavorites);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StreamAdapter(true, this);
        recyclerView.setAdapter(adapter);
        refreshList();
    }

    /** 从持久化存储重新加载收藏并刷新界面。 */
    private void refreshList() {
        List<Favorite> favorites = FavoriteManager.getFavorites(this);
        adapter.setFavorites(favorites);
        tvEmpty.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);
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

    // ================= 菜单 =================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_favorite, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_clear_all) {
            confirmClearAll();
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
}
