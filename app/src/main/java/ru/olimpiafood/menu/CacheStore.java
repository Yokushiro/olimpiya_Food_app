package ru.olimpiafood.menu;

import android.text.TextUtils;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Офлайн-кеш: "сначала сеть, потом кеш".
 *  - есть интернет  -> берём свежую версию с сайта и перезаписываем кеш (так подтягиваются обновления);
 *  - интернета нет  -> отдаём последнюю сохранённую копию.
 * Кешируются все GET-запросы (страница, скрипты, стили, картинки, API-запросы данных).
 */
class CacheStore {

    private static final List<String> SKIP_RESPONSE_HEADERS = Arrays.asList(
            "content-type", "content-encoding", "content-length", "transfer-encoding",
            "connection", "keep-alive", "set-cookie", "cache-control", "expires",
            "etag", "last-modified", "pragma", "vary", "alt-svc");

    private final File dir;
    private final Object lock = new Object();

    CacheStore(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
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
            return fetchAndStore(url, rh, body, meta);
        } catch (Exception e) {
            try {
                WebResourceResponse cached = readCache(body, meta);
                if (cached != null) return cached;
            } catch (Exception ignored) { }
            return offline(req.isForMainFrame());
        }
    }

    private WebResourceResponse fetchAndStore(String url, Map<String, String> rh, File body, File meta)
            throws IOException {
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

        int code = c.getResponseCode();
        if (code >= 500) {
            c.disconnect();
            throw new IOException("HTTP " + code); // сервер недоступен -> уйдём в кеш
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
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            String mime = mimeOf(c, url);
            String enc = encOf(c);
            Map<String, String> h = headersOf(c);
            try (DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(metaTmp)))) {
                d.writeUTF(mime);
                d.writeUTF(enc == null ? "" : enc);
                d.writeInt(h.size());
                for (Map.Entry<String, String> e : h.entrySet()) {
                    d.writeUTF(e.getKey());
                    d.writeUTF(e.getValue());
                }
            }
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

    private WebResourceResponse readCache(File body, File meta) throws IOException {
        if (!body.exists() || !meta.exists()) return null;
        String mime = "application/octet-stream";
        String enc = "";
        Map<String, String> h = new HashMap<>();
        try (DataInputStream d = new DataInputStream(new BufferedInputStream(new FileInputStream(meta)))) {
            mime = d.readUTF();
            enc = d.readUTF();
            int n = d.readInt();
            for (int i = 0; i < n; i++) {
                String k = d.readUTF();
                String v = d.readUTF();
                h.put(k, v);
            }
        }
        boolean hasCors = false;
        for (String k : h.keySet()) {
            if (k.equalsIgnoreCase("Access-Control-Allow-Origin")) hasCors = true;
        }
        if (!hasCors) h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse(mime, enc.isEmpty() ? null : enc, 200, "OK", h, new FileInputStream(body));
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
