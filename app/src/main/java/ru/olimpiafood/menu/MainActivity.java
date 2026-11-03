package ru.olimpiafood.menu;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;

public class MainActivity extends Activity {

    /** Адрес сайта, который превращён в приложение. */
    static final String START_URL = "https://falling-dust-d961.olimpiafood.workers.dev/";
    private static final String BG = "#f7f2e9";
    private static final long RELOAD_AFTER_MS = 10 * 60 * 1000;

    private WebView web;
    private CacheStore cache;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long stoppedAt = 0;

    private final Runnable preload = new Runnable() {
        @Override public void run() { forceLoadImages(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.parseColor(BG));
        getWindow().setNavigationBarColor(Color.parseColor(BG));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        cache = new CacheStore(new File(getFilesDir(), "webcache"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(BG));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE); // кеш ведёт CacheStore, а не WebView
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        final String host = Uri.parse(START_URL).getHost();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return cache.handle(request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                boolean web = "http".equals(scheme) || "https".equals(scheme);
                if (web && host != null && host.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                handler.removeCallbacks(preload);
                handler.postDelayed(preload, 300);
                handler.postDelayed(preload, 2500);
                handler.postDelayed(preload, 6000);
            }
        });

        // Запросы из service worker (если сайт его использует) тоже идут через кеш
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return cache.handle(request);
                }
            });
        }

        // Каждый запуск: сначала сеть (свежая версия сайта), при её отсутствии — кеш
        web.loadUrl(START_URL);
    }

    /** Заставляет подгрузиться все «ленивые» картинки, чтобы они тоже попали в офлайн-кеш. */
    private void forceLoadImages() {
        if (web == null) return;
        web.evaluateJavascript(
            "(function(){"
          + "document.querySelectorAll('img[loading=\"lazy\"]').forEach(function(i){i.loading='eager';});"
          + "document.querySelectorAll('img[data-src]').forEach(function(i){"
          + "if(!i.getAttribute('src')){i.src=i.getAttribute('data-src');}});"
          + "})();", null);
    }

    @Override
    protected void onStop() {
        super.onStop();
        stoppedAt = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Если приложение долго пролежало в фоне — снова проверяем обновления
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > RELOAD_AFTER_MS && web != null) {
            web.reload();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
