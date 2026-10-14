package com.example.webcam7;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面：使用 WebView 加载 Bing 搜索页面，注入 JS 抓取视频流地址，
 * 结果展示在列表中，支持播放与收藏。
 */
public class MainActivity extends AppCompatActivity implements StreamAdapter.Listener {

    private static final String TAG = "MainActivity";

    /** 启动时加载的 Bing 搜索地址。 */
    private static final String START_URL = "https://www.bing.com/search?q=webcam+7";

    /** JS 桥接对象名，注入脚本通过 AndroidBridge.addStream(...) 回调 Java。 */
    private static final String JS_BRIDGE = "AndroidBridge";

    private WebView webView;
    private StreamAdapter adapter;

    /**
     * 注入到网页中的脚本：
     * 1. 扫描 video/source/a 标签、页面 HTML、资源性能条目；
     * 2. Hook XMLHttpRequest / fetch，捕获动态请求的流地址；
     * 3. 通过 AndroidBridge.addStream 回传 Java 层。
     * 注意：脚本内只向 Java 传递字符串，不执行任何危险操作。
     */
    private static final String INJECT_JS =
            "(function(){" +
            "  if (window.__wc7_installed) { return; }" +
            "  window.__wc7_installed = true;" +
            "  var seen = {};" +
            // 视频类流：m3u8/mp4/flv 等
            "  var VIDEO_EXT = /\\.(m3u8|mp4|flv|webm|mov|mkv|ts|m4v|avi|mpg|mpeg|ogv|3gp|mjpg|mjpeg)(\\?|#|$)/i;" +
            // 网络摄像机快照：仅匹配 cam_<数字>.jpg，如 /cam_1.jpg?uniq=0.609...
            "  var CAM_EXT = /(^|\\/)cam_\\d+\\.jpe?g$/i;" +
            // 去除 uniq 等缓存参数，避免快照每帧都产生新链接
            "  function stripCacheBuster(p){" +
            "    var keys = ['uniq','_','t','ts','timestamp','rand','random','nocache','cb','cachebuster','_t'];" +
            "    for(var i=0;i<keys.length;i++){ try{ p.searchParams.delete(keys[i]); }catch(e){} }" +
            "    return p;" +
            "  }" +
            "  function report(raw){" +
            "    try{" +
            "      if(!raw) return;" +
            "      raw = ('' + raw).trim();" +
            "      if(raw.indexOf('blob:') === 0 || raw.indexOf('data:') === 0 || raw.indexOf('about:') === 0) return;" +
            "      var p = new URL(raw, location.href);" +
            "      if(p.protocol !== 'http:' && p.protocol !== 'https:') return;" +
            "      var isVideo = VIDEO_EXT.test(p.pathname) || VIDEO_EXT.test(p.href);" +
            "      var isCam = CAM_EXT.test(p.pathname);" +
            "      if(!isVideo && !isCam) return;" +
            "      if(isCam){ stripCacheBuster(p); p.hash = ''; }" +
            "      var key = p.href;" +
            "      if(seen[key]) return;" +
            "      seen[key] = true;" +
            "      " + JS_BRIDGE + ".addStream(key);" +
            "    }catch(e){}" +
            "  }" +
            "  function scan(){" +
            "    try{" +
            "      var media = document.querySelectorAll('video, video source, audio, audio source');" +
            "      for(var i=0;i<media.length;i++){" +
            "        report(media[i].src || media[i].currentSrc || media[i].getAttribute('src'));" +
            "      }" +
            // 扫描 img，抓取 MJPEG/快照网络摄像机（webcamXP 等）
            "      var imgs = document.querySelectorAll('img[src]');" +
            "      for(var n=0;n<imgs.length;n++){ report(imgs[n].src || imgs[n].getAttribute('src')); }" +
            "      var links = document.querySelectorAll('a[href]');" +
            "      for(var j=0;j<links.length;j++){ report(links[j].href); }" +
            "      var html = document.documentElement ? document.documentElement.outerHTML : '';" +
            "      var re = /(https?:\\/\\/[^\\s\"'<>)]+?\\.(?:m3u8|mp4|flv|webm|mov|mkv|ts|m4v|mjpg|mjpeg)(?:\\?[^\\s\"'<>)]*)?)/ig;" +
            "      var m; while((m = re.exec(html)) !== null){ report(m[1]); }" +
            "      if(window.performance && performance.getEntriesByType){" +
            "        var entries = performance.getEntriesByType('resource') || [];" +
            "        for(var k=0;k<entries.length;k++){ report(entries[k].name); }" +
            "      }" +
            "    }catch(e){}" +
            "  }" +
            "  try{" +
            "    var origOpen = XMLHttpRequest.prototype.open;" +
            "    XMLHttpRequest.prototype.open = function(method, url){" +
            "      try{ report(url); }catch(e){}" +
            "      return origOpen.apply(this, arguments);" +
            "    };" +
            "  }catch(e){}" +
            "  try{" +
            "    if(window.fetch){" +
            "      var origFetch = window.fetch;" +
            "      window.fetch = function(input){" +
            "        try{ report(typeof input === 'string' ? input : (input && input.url)); }catch(e){}" +
            "        return origFetch.apply(this, arguments);" +
            "      };" +
            "    }" +
            "  }catch(e){}" +
            // 监听 img.src 动态变化（webcamXP 每 40ms 刷新一次快照）
            "  try{" +
            "    var imgDesc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');" +
            "    if(imgDesc && imgDesc.set){" +
            "      Object.defineProperty(HTMLImageElement.prototype, 'src', {" +
            "        configurable: true, enumerable: imgDesc.enumerable," +
            "        get: function(){ return imgDesc.get.call(this); }," +
            "        set: function(v){ try{ report(v); }catch(e){} return imgDesc.set.call(this, v); }" +
            "      });" +
            "    }" +
            "  }catch(e){}" +
            "  scan();" +
            "  if(window.__wc7_timer) clearInterval(window.__wc7_timer);" +
            "  window.__wc7_timer = setInterval(scan, 3000);" +
            "})();";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyFullscreenTop();
        setContentView(R.layout.activity_main);

        setupToolbar();
        setupRecyclerView();
        setupWebView();
        setupBottomNav();
        setupBackPressed();

        // 启动后自动加载 Bing 搜索
        loadStartUrl();
    }

    /**
     * 沉浸式顶部：内容延伸至状态栏区域并隐藏系统状态栏（时间、电量等），
     * 由应用顶部工具栏覆盖该区域。
     */
    private void applyFullscreenTop() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.statusBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    /** 初始化工具栏与返回箭头。 */
    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.title_main);
        }
    }

    /** 初始化抓取结果列表。 */
    private void setupRecyclerView() {
        RecyclerView recyclerView = findViewById(R.id.recyclerStreams);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StreamAdapter(false, this);
        recyclerView.setAdapter(adapter);
        updateCount();
    }

    /** 底部菜单：第一页“搜索”，第二页“收藏”。 */
    private void setupBottomNav() {
        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setSelectedItemId(R.id.nav_search);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_favorite) {
                // 切换到第二页：收藏页
                startActivity(new Intent(this, FavoriteActivity.class));
                // 返回 false 保持“搜索”项选中，不改变本页状态
                return false;
            }
            return id == R.id.nav_search;
        });
    }

    /** 配置 WebView：开启 JS、允许媒体自动播放、拦截外部跳转。 */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        webView = findViewById(R.id.webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);                       // 开启 JS
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);       // 允许媒体自动播放
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        // 允许 https 页面加载 http 资源
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // 桥接对象：JS 通过 AndroidBridge.addStream 回传地址
        webView.addJavascriptInterface(new JsBridge(), JS_BRIDGE);

        // WebViewClient：所有跳转都在内部处理，不唤起外部浏览器
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成后注入抓取脚本
                injectJs();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "页面加载错误: " + (error == null ? "" : error.getDescription()));
            }
        });

        // WebChromeClient：授权摄像头/麦克风，支持弹窗
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception e) {
                        Log.e(TAG, "授权失败", e);
                    }
                });
            }
        });
    }

    /**
     * 处理网页跳转：仅 http/https 在 WebView 内加载，其余协议（如 intent://、market://）
     * 一律拦截，避免唤起外部应用或浏览器。
     *
     * @return true 表示由 App 自行处理（拦截），false 表示交给 WebView 加载
     */
    private boolean handleUrl(Uri uri) {
        if (uri == null) {
            return true;
        }
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            // 在 WebView 内部继续加载，不跳转外部浏览器
            return false;
        }
        // 非 http(s) 协议直接拦截
        Log.d(TAG, "拦截外部协议: " + uri);
        return true;
    }

    /** 注入抓取脚本。 */
    private void injectJs() {
        if (webView == null) {
            return;
        }
        try {
            webView.evaluateJavascript(INJECT_JS, null);
        } catch (Exception e) {
            Log.e(TAG, "注入 JS 失败", e);
        }
    }

    /** 加载起始地址。 */
    private void loadStartUrl() {
        try {
            webView.loadUrl(START_URL);
        } catch (Exception e) {
            Log.e(TAG, "加载起始地址失败", e);
        }
    }

    /** 返回键：若 WebView 可后退则回退页面，否则退出。 */
    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    /** 更新抓取数量显示。 */
    private void updateCount() {
        android.widget.TextView tvCount = findViewById(R.id.tvCount);
        if (tvCount != null && adapter != null) {
            tvCount.setText(getString(R.string.count_format, adapter.getItemCount()));
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
        if (FavoriteManager.contains(this, url)) {
            Toast.makeText(this, R.string.toast_favorite_exist, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean ok = FavoriteManager.addFavorite(this, url);
        Toast.makeText(this, ok ? R.string.toast_favorite_added : R.string.toast_play_failed,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDelete(String url) {
        // 主页的抓取结果移除（仅本次内存显示，不影响收藏）
        adapter.removeItem(url);
        updateCount();
    }

    @Override
    public void onTag(String url) {
        // 主页不提供标签功能，收藏页才可编辑标签
    }

    // ================= JS 桥接 =================

    /**
     * 提供给网页 JS 调用的桥接对象。
     * 注意：{@link JavascriptInterface} 方法运行在 WebView 的 JavaBridge 子线程，
     * 因此更新 UI 需要切回主线程。
     */
    private class JsBridge {
        @JavascriptInterface
        public void addStream(final String url) {
            if (TextUtils.isEmpty(url)) {
                return;
            }
            runOnUiThread(() -> {
                // addItem 内部已做去重；仅新增时查询归属国家
                if (adapter.addItem(url)) {
                    updateCount();
                    GeoIpManager.lookupCountry(MainActivity.this, url,
                            country -> adapter.setCountry(url, country));
                }
            });
        }
    }

    // ================= 菜单 =================

    /** 弹窗确认后一键清空抓取结果（仅内存列表，不影响收藏）。 */
    private void confirmClearCaptured() {
        if (adapter == null || adapter.getItemCount() == 0) {
            Toast.makeText(this, R.string.toast_no_stream, Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.action_clear_captured)
                .setMessage(R.string.action_clear_captured)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    adapter.clear();
                    updateCount();
                    Toast.makeText(MainActivity.this,
                            R.string.toast_captured_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_prev) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
            return true;
        } else if (id == R.id.action_next) {
            if (webView != null && webView.canGoForward()) {
                webView.goForward();
            }
            return true;
        } else if (id == R.id.action_refresh) {
            webView.reload();
            return true;
        } else if (id == R.id.action_clear) {
            confirmClearCaptured();
            return true;
        } else if (id == R.id.action_favorite) {
            startActivity(new Intent(this, FavoriteActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            if (webView != null) {
                webView.loadUrl("about:blank");
                webView.removeJavascriptInterface(JS_BRIDGE);
                webView.destroy();
                webView = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "销毁 WebView 失败", e);
        }
        super.onDestroy();
    }
}
