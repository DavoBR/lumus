package dev.davobr.lumus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The "cartelera" theme: this week's trending films and series from TMDB as the rotating wallpaper, with the title
 * under the buttons (wallpapers/NNN.jpg plus captions.txt, which WallpaperPlayer already rotates and names).
 * The read access token is never in the APK: it is pushed to the external files dir as {@code tmdb_token}.
 * Refreshed at most every REFRESH_MS, on a background thread; the callback runs on the main thread only when the
 * pictures changed.
 */
final class TmdbBackdrops {
    private TmdbBackdrops() {}

    static final String THEME = "cartelera";
    private static final String TOKEN_FILE = "tmdb_token";
    private static final String PREFS = "tmdb_backdrops";
    private static final long REFRESH_MS = 6L * 60 * 60 * 1000;
    private static final int MAX = 15;
    private static volatile boolean running;

    static boolean active(Context ctx) {
        return THEME.equals(ThemeInstaller.current(ctx));
    }

    /** Starts a refresh when the cartelera theme is on and the pictures are missing or older than six hours. */
    static void maybeRefresh(Context ctx, boolean force, Runnable onChanged) {
        if (!active(ctx) || running) return;
        final Context app = ctx.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File dir = Config.dir(app);
        if (dir == null) return;
        File walls = new File(dir, ThemeFiles.WALLPAPERS);
        boolean empty = !new File(walls, WallpaperCaptions.FILE_NAME).isFile();
        if (!force && !empty && System.currentTimeMillis() - p.getLong("last", 0) < REFRESH_MS) return;
        running = true;
        new Thread(() -> {
            boolean changed = false;
            try {
                changed = fetch(app, dir, walls);
                if (changed) p.edit().putLong("last", System.currentTimeMillis()).apply();
            } catch (Throwable ignored) {
                // No network or no token: keep the pictures we have.
            } finally {
                running = false;
            }
            if (changed && onChanged != null) new Handler(Looper.getMainLooper()).post(onChanged);
        }, "tmdb-backdrops").start();
    }

    private static boolean fetch(Context ctx, File dir, File walls) throws Exception {
        File tokenFile = new File(dir, TOKEN_FILE);
        if (!tokenFile.isFile()) return false;
        String token = new String(readAll(new java.io.FileInputStream(tokenFile)), StandardCharsets.UTF_8).trim();
        if (token.isEmpty()) return false;
        Locale l = Locale.getDefault();
        String lang = l.getLanguage() + (l.getCountry().isEmpty() ? "" : "-" + l.getCountry());
        JSONObject json = new JSONObject(new String(get(
                "https://api.themoviedb.org/3/trending/all/week?language=" + lang, token), StandardCharsets.UTF_8));
        JSONArray results = json.getJSONArray("results");
        walls.mkdirs();
        StringBuilder captions = new StringBuilder();
        int n = 0;
        for (int i = 0; i < results.length() && n < MAX; i++) {
            JSONObject r = results.getJSONObject(i);
            String backdrop = r.optString("backdrop_path", "");
            if (backdrop.isEmpty() || "null".equals(backdrop)) continue;
            String title = r.optString("title", r.optString("name", ""));
            if (title.isEmpty()) continue;
            String name = String.format(Locale.ROOT, "%03d.jpg", n + 1);
            File tmp = new File(walls, name + ".part");
            try {
                byte[] img = get("https://image.tmdb.org/t/p/w1280" + backdrop, null);
                try (OutputStream o = new FileOutputStream(tmp)) {
                    o.write(img);
                }
                File target = new File(walls, name);
                target.delete();
                if (!tmp.renameTo(target)) continue;
            } catch (Exception e) {
                tmp.delete();
                continue;
            }
            boolean tv = "tv".equals(r.optString("media_type"));
            String kind = ctx.getString(tv ? R.string.tmdb_series : R.string.tmdb_movie);
            double vote = r.optDouble("vote_average", 0);
            String line = title.replace('\t', ' ').replace('\n', ' ') + " · " + kind
                    + (vote > 0 ? String.format(Locale.ROOT, " · ★ %.1f", vote) : "");
            captions.append(name).append('\t').append(line).append('\n');
            n++;
        }
        if (n == 0) return false;
        // Drop pictures from an earlier, longer list.
        File[] old = walls.listFiles();
        if (old != null) for (File f : old) {
            String fn = f.getName();
            if (fn.endsWith(".jpg") && fn.length() == 7) {
                try {
                    if (Integer.parseInt(fn.substring(0, 3)) > n) f.delete();
                } catch (NumberFormatException ignored) {
                    f.delete();
                }
            }
        }
        try (OutputStream o = new FileOutputStream(new File(walls, WallpaperCaptions.FILE_NAME))) {
            o.write(captions.toString().getBytes(StandardCharsets.UTF_8));
        }
        return true;
    }

    private static byte[] get(String url, String bearer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        c.setRequestProperty("Accept", bearer != null ? "application/json" : "image/jpeg");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int k;
            while ((k = i.read(buf)) > 0) out.write(buf, 0, k);
            return out.toByteArray();
        }
    }
}
