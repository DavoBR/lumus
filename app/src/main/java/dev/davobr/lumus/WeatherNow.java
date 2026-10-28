package dev.davobr.lumus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The weather now next to the greeting: an icon and the temperature, from Open-Meteo (no key, no account).
 * The place is config.xml weather_latitude / weather_longitude. Refreshed every 20 minutes while Home is in
 * front (5 minutes after a failure); the last reading stays on screen in between.
 */
final class WeatherNow {
    private static final long REFRESH_MS = 20 * 60 * 1000, RETRY_MS = 5 * 60 * 1000;
    private static String lastText;
    private static long lastFetch;

    private final Context ctx;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String lat, lon;
    private boolean running;
    private final Runnable tick = this::fetchNow;

    WeatherNow(Context ctx, TextView view) {
        this.ctx = ctx.getApplicationContext();
        this.view = view;
        lat = ctx.getString(R.string.weather_latitude).trim();
        lon = ctx.getString(R.string.weather_longitude).trim();
        if (lastText != null) show(lastText);
    }

    void resume() {
        running = true;
        long wait = lastText == null ? 0 : Math.max(0, REFRESH_MS - (System.currentTimeMillis() - lastFetch));
        main.removeCallbacks(tick);
        main.postDelayed(tick, wait);
    }

    void pause() {
        running = false;
        main.removeCallbacks(tick);
    }

    private void fetchNow() {
        if (!running || lat.isEmpty() || lon.isEmpty()) return;
        new Thread(() -> {
            String text = null;
            try {
                JSONObject cur = new JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=" + lat
                        + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day&timezone=auto"))
                        .getJSONObject("current");
                long t = Math.round(cur.getDouble("temperature_2m"));
                text = icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1)
                        + "  " + String.format(Locale.ROOT, "%d°", t);
            } catch (Exception ignored) {
            }
            final String result = text;
            main.post(() -> {
                if (result != null) {
                    lastText = result;
                    lastFetch = System.currentTimeMillis();
                    show(result);
                }
                if (running) main.postDelayed(tick, result != null ? REFRESH_MS : RETRY_MS);
            });
        }, "weather-now").start();
    }

    private void show(String text) {
        view.setText(text);
        view.setVisibility(android.view.View.VISIBLE);
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";              // clear: sun / moon
        if (code == 1) return day ? "\uD83C\uDF24\uFE0F" : "\uD83C\uDF19";        // mainly clear
        if (code == 2) return day ? "\u26C5" : "\u2601\uFE0F";                     // partly cloudy
        if (code == 3) return "\u2601\uFE0F";                                      // overcast
        if (code == 45 || code == 48) return "\uD83C\uDF2B\uFE0F";                 // fog
        if (code >= 51 && code <= 57) return day ? "\uD83C\uDF26\uFE0F" : "\uD83C\uDF27\uFE0F"; // drizzle
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "\uD83C\uDF27\uFE0F"; // rain
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\u2744\uFE0F";      // snow
        if (code >= 95) return "\u26C8\uFE0F";                                     // thunderstorm
        return "\u2601\uFE0F";
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
