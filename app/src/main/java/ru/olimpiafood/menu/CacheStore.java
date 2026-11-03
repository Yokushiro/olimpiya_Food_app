package ru.olimpiafood.menu;

import android.content.res.AssetManager;
import android.net.Uri;
import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Кеш для WebView.
 *
 * Картинки: берутся мгновенно из локального кеша. Если в кеше пусто — из вшитых в APK файлов
 * (assets/preload). Раз за запуск приложения каждая картинка тихо проверяется на сайте в фоне
 * (условный запрос If-None-Match / If-Modified-Since), и если на сайте она изменилась — обновляется.
 *
 * Всё остальное (страница, скрипты, данные меню): сначала сеть, при её отсутствии — кеш.
 */
class CacheStore {

    private static final List<String> SKIP_RESPONSE_HEADERS = Arrays.asList(
            "content-type", "content-encoding", "content-length", "transfer-encoding",
            "connection", "keep-alive", "set-cookie", "cache-control", "expires",
            "etag", "last-modified", "pragma", "vary", "alt-svc");

    private static final List<String> IMAGE_EXT = Arrays.asList(
            ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".avif", ".ico");

    private static class Bundled {
        String file, mime, etag, lastMod;
    }

    private static class Meta {
        String mime = "application/octet-stream";
        String enc = "";
        String etag = "";
        String lastMod = "";
        Map<String, String> headers = new HashMap<>();
    }

    private final File dir;
    private final AssetManager assets;
    private final Object lock = new Object();
    private final Map<String, Bundled> bundled = new HashMap<>();
    private final Set<String> checkedThisRun = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final ExecutorService bg = Executors.newFixedThreadPool(3);

    CacheStore(File dir, AssetManager assets) {
        this.dir = dir;
        this.assets = assets;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        loadManifest();
    }

    private void loadManifest() {
        try (InputStream in = assets.open("preload/manifest.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            pump(in, bo);
            JSONArray arr = new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Bundled b = new Bundled();
                b.file = o.getString("file");
                b.mime = o.optString("mime", "application/octet-stream");
                b.etag = o.optString("etag", "");
                b.lastMod = o.optString("lastModified", "");
                bundled.put(o.getString("url"), b);
            }
        } catch (Exception ignored) {
            // вшитых картинок нет — работаем только с кешем
        }
    }

    WebResourceResponse handle(WebResourceRequest req) {
        String scheme = req.getUrl().getScheme();
        if (!"GET".equalsIgnoreCase(req.getMethod()) || scheme == null
                || !(scheme.equals("http") || scheme.equals("https"))) {
            return null;
        }
        Map<String, String> rh = req.getRequestHeaders();
        if (rh != null) {
            for (String k : rh.keySet()) {
                if (k.equalsIgnoreCase("Range")) return null; // видео/частичные загрузки — напрямую
            }
        }

        String url = req.getUrl().buildUpon().fragment(null).build().toString();
        String key = sha256(url);
        File body = new File(dir, key + ".body");
        File meta = new File(dir, key + ".meta");

        try {
            if (isImage(url, rh, req.isForMainFrame())) {
                WebResourceResponse r = imageFromCacheOrBundle(url, rh, body, meta);
                if (r != null) return r;
            }
            return fetchAndStore(url, rh, body, meta, "", "");
        } catch (Exception e) {
            try {
                WebResourceResponse cached = readCache(body, meta);
                if (cached != null) return cached;
            } catch (Exception ignored) { }
            return offline(req.isForMainFrame());
        }
    }

    // ---------------------------------------------------------------- картинки

    private WebResourceResponse imageFromCacheOrBundle(String url, Map<String, String> rh, File body, File meta)
            throws IOException {
        if (!(body.exists() && meta.exists())) {
            if (!seedFromAsset(url, body, meta)) return null; // нет ни в кеше, ни в APK -> качаем
        }
        WebResourceResponse r = readCache(body, meta);
        if (r != null && checkedThisRun.add(url)) scheduleRevalidate(url, rh, body, meta);
        return r;
    }

    private void scheduleRevalidate(final String url, Map<String, String> rh, final File body, final File meta) {
        final Map<String, String> headers = rh == null ? null : new HashMap<String, String>(rh);
        bg.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Meta m = readMeta(meta);
                    WebResourceResponse r = fetchAndStore(url, headers, body, meta, m.etag, m.lastMod);
                    if (r != null && r.getData() != null) r.getData().close();
                } catch (Exception ignored) {
                    // нет сети — остаётся то, что в кеше
                }
            }
        });
    }

    private boolean seedFromAsset(String url, File body, File meta) {
        Bundled b = bundled.get(url);
        if (b == null) return false;
        File tmp = new File(body.getPath() + "." + System.nanoTime() + ".tmp");
        File metaTmp = new File(meta.getPath() + "." + System.nanoTime() + ".tmp");
        try {
            try (InputStream in = assets.open("preload/" + b.file);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                pump(in, out);
            }
            Meta m = new Meta();
            m.mime = b.mime;
            m.etag = b.etag;
            m.lastMod = b.lastMod;
            writeMeta(metaTmp, m);
            synchronized (lock) {
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(body);
                //noinspection ResultOfMethodCallIgnored
                metaTmp.renameTo(meta);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            tmp.delete();
            metaTmp.delete();
        }
    }

    private static boolean isImage(String url, Map<String, String> rh, boolean mainFrame) {
        if (mainFrame) return false;
        String path = Uri.parse(url).getPath();
        if (path != null) {
            String p = path.toLowerCase(Locale.ROOT);
            for (String ext : IMAGE_EXT) {
                if (p.endsWith(ext)) return true;
            }
        }
        if (rh != null) {
            for (Map.Entry<String, String> e : rh.entrySet()) {
                if (e.getKey().equalsIgnoreCase("Accept") && e.getValue() != null
                        && e.getValue().trim().toLowerCase(Locale.ROOT).startsWith("image/")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- сеть

    private WebResourceResponse fetchAndStore(String url, Map<String, String> rh, File body, File meta,
                                              String etag, String lastMod) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        if (rh != null) {
            for (Map.Entry<String, String> e : rh.entrySet()) {
                String n = e.getKey().toLowerCase(Locale.ROOT);
                if (n.equals("accept-encoding") || n.equals("if-none-match") || n.equals("if-modified-since")
                        || n.equals("host") || n.equals("connection") || n.equals("cache-control")
                        || n.equals("pragma")) continue;
                c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        c.setRequestProperty("Cache-Control", "no-cache");
        if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-None-Match", etag);
        if (lastMod != null && !lastMod.isEmpty()) c.setRequestProperty("If-Modified-Since", lastMod);

        int code = c.getResponseCode();
        if (code >= 500) {
            c.disconnect();
            throw new IOException("HTTP " + code); // сервер недоступен -> уйдём в кеш
        }
        if (code == 304) { // на сайте без изменений
            long now = System.currentTimeMillis();
            //noinspection ResultOfMethodCallIgnored
            body.setLastModified(now);
            //noinspection ResultOfMethodCallIgnored
            meta.setLastModified(now);
            c.disconnect();
            return readCache(body, meta);
        }
        if (code >= 300 && code < 400) return null; // пусть WebView сам обработает
        if (code < 200 || code >= 300) {
            if (code == 404 || code == 410) { // файл удалён на сайте — удаляем и из кеша
                body.delete();
                meta.delete();
            }
            InputStream es = c.getErrorStream();
            if (es == null) es = new ByteArrayInputStream(new byte[0]);
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = "Error";
            return new WebResourceResponse(mimeOf(c, url), encOf(c), code, reason, headersOf(c), es);
        }

        File tmp = new File(body.getPath() + "." + System.nanoTime() + ".tmp");
        File metaTmp = new File(meta.getPath() + "." + System.nanoTime() + ".tmp");
        try {
            try (InputStream in = c.getInputStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
                pump(in, out);
            }
            Meta m = new Meta();
            m.mime = mimeOf(c, url);
            String enc = encOf(c);
            m.enc = enc == null ? "" : enc;
            String et = c.getHeaderField("ETag");
            String lm = c.getHeaderField("Last-Modified");
            m.etag = et == null ? "" : et;
            m.lastMod = lm == null ? "" : lm;
            m.headers = headersOf(c);
            writeMeta(metaTmp, m);
            synchronized (lock) {
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(body);
                //noinspection ResultOfMethodCallIgnored
                metaTmp.renameTo(meta);
            }
        } finally {
            tmp.delete();
            metaTmp.delete();
        }

        WebResourceResponse r = readCache(body, meta);
        if (r == null) throw new IOException("cache write failed");
        return r;
    }

    // ---------------------------------------------------------------- чтение/запись кеша

    private WebResourceResponse readCache(File body, File meta) throws IOException {
        if (!body.exists() || !meta.exists()) return null;
        Meta m = readMeta(meta);
        Map<String, String> h = new HashMap<>(m.headers);
        boolean hasCors = false;
        for (String k : h.keySet()) {
            if (k.equalsIgnoreCase("Access-Control-Allow-Origin")) hasCors = true;
        }
        if (!hasCors) h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(m.mime, m.enc.isEmpty() ? null : m.enc, 200, "OK", h,
                new FileInputStream(body));
    }

    private static Meta readMeta(File f) throws IOException {
        Meta m = new Meta();
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            m.mime = d.readUTF();
            m.enc = d.readUTF();
            m.etag = d.readUTF();
            m.lastMod = d.readUTF();
            int n = d.readInt();
            for (int i = 0; i < n; i++) {
                String k = d.readUTF();
                String v = d.readUTF();
                m.headers.put(k, v);
            }
        }
        return m;
    }

    private static void writeMeta(File f, Meta m) throws IOException {
        try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
            d.writeUTF(m.mime);
            d.writeUTF(m.enc);
            d.writeUTF(m.etag);
            d.writeUTF(m.lastMod);
            d.writeInt(m.headers.size());
            for (Map.Entry<String, String> e : m.headers.entrySet()) {
                d.writeUTF(e.getKey());
                d.writeUTF(e.getValue());
            }
        }
    }

    // ---------------------------------------------------------------- утилиты

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static WebResourceResponse offline(boolean mainFrame) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        if (mainFrame) {
            String html = "<!doctype html><html><head><meta charset='utf-8'>"
                    + "<meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                    + "<body style='font-family:sans-serif;background:#f7f2e9;color:#333;text-align:center;padding:48px 24px'>"
                    + "<h2>Нет подключения</h2>"
                    + "<p>Меню ещё не сохранено на устройстве. Подключитесь к интернету и запустите приложение — "
                    + "после первой загрузки оно будет работать офлайн.</p>"
                    + "<button onclick='location.reload()' style='font-size:18px;padding:12px 24px;border:0;"
                    + "border-radius:12px;background:#c8553d;color:#fff'>Повторить</button></body></html>";
            return new WebResourceResponse("text/html", "utf-8", 503, "Offline", h,
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        }
        return new WebResourceResponse("text/plain", "utf-8", 504, "Offline", h,
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(HttpURLConnection c, String url) {
        String ct = c.getContentType();
        if (ct != null && !ct.isEmpty()) return ct.split(";")[0].trim();
        String g = URLConnection.guessContentTypeFromName(url);
        return g != null ? g : "application/octet-stream";
    }

    private static String encOf(HttpURLConnection c) {
        String ct = c.getContentType();
        if (ct == null) return null;
        int i = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i < 0) return null;
        String e = ct.substring(i + 8);
        int semi = e.indexOf(';');
        if (semi >= 0) e = e.substring(0, semi);
        e = e.replace("\"", "").trim();
        return e.isEmpty() ? null : e;
    }

    private static Map<String, String> headersOf(HttpURLConnection c) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            if (SKIP_RESPONSE_HEADERS.contains(k.toLowerCase(Locale.ROOT))) continue;
            out.put(k, TextUtils.join(", ", e.getValue()));
        }
        return out;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
