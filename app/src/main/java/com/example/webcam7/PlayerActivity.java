package com.example.webcam7;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频播放界面。
 *
 * <p>两种播放模式：</p>
 * <ul>
 *   <li>视频流（m3u8/mp4/flv 等）：使用 ExoPlayer（Media3），支持 HLS 直播。</li>
 *   <li>图片流（jpg/png/gif 等静态快照，即 MJPEG 网络摄像机）：
 *       ExoPlayer 无法播放，改为定时拉取最新帧并用 ImageView 刷新，模拟直播画面。</li>
 * </ul>
 * 播放失败时弹出 Toast 提示。
 */
public class PlayerActivity extends AppCompatActivity {

    private static final String TAG = "PlayerActivity";

    /** 传入的视频流地址。 */
    public static final String EXTRA_URL = "extra_url";

    /** 快照刷新间隔（毫秒）。 */
    private static final long SNAPSHOT_INTERVAL_MS = 150L;

    private ExoPlayer player;
    private PlayerView playerView;
    private ImageView ivSnapshot;
    private TextView tvLoading;
    private boolean errorShown;

    /** 图片流相关。 */
    private String baseUrl;
    private boolean imageMode;
    private ExecutorService executor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;

    /** 便捷启动方法。 */
    public static void start(Context context, String url) {
        Intent intent = new Intent(context, PlayerActivity.class);
        intent.putExtra(EXTRA_URL, url);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        playerView = findViewById(R.id.playerView);
        ivSnapshot = findViewById(R.id.ivSnapshot);
        tvLoading = findViewById(R.id.tvLoading);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(this, R.string.toast_play_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setTitle(R.string.title_player);
        baseUrl = url;

        if (isImageUrl(url)) {
            startImageStream(url);
        } else {
            startPlayback(url);
        }
    }

    /** 根据扩展名判断是否为图片/快照流。 */
    private boolean isImageUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) {
            lower = lower.substring(0, q);
        }
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".bmp") || lower.endsWith(".webp");
    }

    // ================= 视频模式（ExoPlayer） =================

    /** 创建 ExoPlayer 并开始播放。 */
    private void startPlayback(String url) {
        try {
            playerView.setVisibility(View.VISIBLE);
            ivSnapshot.setVisibility(View.GONE);

            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && tvLoading != null) {
                        tvLoading.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    Log.e(TAG, "播放失败", error);
                    showError();
                }
            });

            MediaItem mediaItem = MediaItem.fromUri(Uri.parse(url));
            player.setMediaItem(mediaItem);
            player.setPlayWhenReady(true);
            player.prepare();
        } catch (Exception e) {
            Log.e(TAG, "初始化播放器失败", e);
            showError();
        }
    }

    // ================= 图片模式（快照逐帧刷新） =================

    /** 启动图片流刷新。 */
    private void startImageStream(String url) {
        imageMode = true;
        stopped = false;
        playerView.setVisibility(View.GONE);
        ivSnapshot.setVisibility(View.VISIBLE);
        tvLoading.setVisibility(View.VISIBLE);

        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
        }
        handler.post(imageLoop);
    }

    /** 定时拉取并刷新一帧。 */
    private final Runnable imageLoop = new Runnable() {
        @Override
        public void run() {
            if (stopped || isFinishing()) {
                return;
            }
            final ExecutorService ex = executor;
            if (ex == null || ex.isShutdown()) {
                return;
            }
            ex.execute(() -> {
                final Bitmap bitmap = fetchBitmap(withCacheBuster(baseUrl));
                runOnUiThread(() -> {
                    if (stopped || isFinishing()) {
                        return;
                    }
                    if (bitmap != null) {
                        ivSnapshot.setImageBitmap(bitmap);
                        if (tvLoading.getVisibility() == View.VISIBLE) {
                            tvLoading.setVisibility(View.GONE);
                        }
                    } else {
                        showError();
                    }
                    handler.postDelayed(imageLoop, SNAPSHOT_INTERVAL_MS);
                });
            });
        }
    };

    /** 追加时间戳参数，绕过缓存，确保每次拿到最新帧。 */
    private String withCacheBuster(String url) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }
        String sep = url.contains("?") ? "&" : "?";
        return url + sep + "uniq=" + System.currentTimeMillis() + Math.random();
    }

    /** 拉取一张图片并解码为 Bitmap。 */
    private Bitmap fetchBitmap(String url) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "快照请求失败: " + conn.getResponseCode());
                return null;
            }
            in = conn.getInputStream();
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Log.e(TAG, "获取快照失败", e);
            return null;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 播放失败处理：Toast 提示并在界面上给出状态。 */
    private void showError() {
        if (errorShown) {
            return;
        }
        errorShown = true;
        Toast.makeText(this, R.string.toast_play_failed, Toast.LENGTH_SHORT).show();
        if (tvLoading != null) {
            tvLoading.setVisibility(View.VISIBLE);
            tvLoading.setText(R.string.toast_play_failed);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (imageMode) {
            stopped = false;
            handler.removeCallbacks(imageLoop);
            handler.post(imageLoop);
        } else if (player != null) {
            player.play();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (imageMode) {
            stopped = true;
            handler.removeCallbacks(imageLoop);
        }
        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        try {
            handler.removeCallbacks(imageLoop);
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            if (ivSnapshot != null) {
                ivSnapshot.setImageBitmap(null);
            }
            if (playerView != null) {
                playerView.setPlayer(null);
            }
            if (player != null) {
                player.release();
                player = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "释放资源失败", e);
        }
        super.onDestroy();
    }
}
