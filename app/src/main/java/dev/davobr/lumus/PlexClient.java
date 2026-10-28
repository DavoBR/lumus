package dev.davobr.lumus;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plex for the Continue watching row: link with a plex.tv/link code (the token stays in this app's private
 * preferences), then read On Deck and Recently Added from every server the account can reach, own or shared
 * by a friend. All calls block: run them off the main thread.
 */
final class PlexClient {
    private PlexClient() {}

    static final String PKG = "com.plexapp.android";
    private static final String PREFS = "plex";
    private static final String PRODUCT = "CleanYourTV Home";

    static final class Pin {
        final long id;
        final String code;
        Pin(long id, String code) { this.id = id; this.code = code; }
    }

    static final class Item {
        final String title, subtitle, machineId, ratingKey;
        final boolean onDeck;
        final double progress;
        Bitmap image;
        final String imageUrl;

        Item(String title, String subtitle, String machineId, String ratingKey, boolean onDeck, double progress,
             String imageUrl) {
            this.title = title; this.subtitle = subtitle; this.machineId = machineId; this.ratingKey = ratingKey;
            this.onDeck = onDeck; this.progress = progress; this.imageUrl = imageUrl;
        }

        /** Opens the title in the Plex app; the caller falls back to the app's start screen. */
        Uri deepLink() {
            return Uri.parse("plex://server://" + machineId + "/com.plexapp.plugins.library/library/metadata/" + ratingKey);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean linked(Context ctx) {
        return !prefs(ctx).getString("token", "").isEmpty();
    }

    static void unlink(Context ctx) {
        prefs(ctx).edit().remove("token").apply();
    }

    private static String clientId(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String id = p.getString("client_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("client_id", id).apply();
        }
        return id;
    }

    static Pin requestPin(Context ctx) throws Exception {
        JSONObject j = new JSONObject(http("POST", "https://plex.tv/api/v2/pins?strong=false", ctx, null));
        return new Pin(j.getLong("id"), j.getString("code"));
    }

    /** True once the user entered the code at plex.tv/link; the token is then saved. */
    static boolean checkPin(Context ctx, Pin pin) throws Exception {
        JSONObject j = new JSONObject(http("GET", "https://plex.tv/api/v2/pins/" + pin.id, ctx, null));
        String token = j.optString("authToken", "");
        if (token.isEmpty() || "null".equals(token)) return false;
        prefs(ctx).edit().putString("token", token).apply();
        return true;
    }

    /** On Deck first (newest servers' order kept), then Recently Added, at most {@code max} items. */
    static List<Item> fetch(Context ctx, int max) throws Exception {
        String token = prefs(ctx).getString("token", "");
        List<Item> deck = new ArrayList<>(), recent = new ArrayList<>();
        if (token.isEmpty()) return deck;
        JSONArray res = new JSONArray(http("GET",
                "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", ctx, token));
        for (int i = 0; i < res.length(); i++) {
            JSONObject r = res.getJSONObject(i);
            if (!r.optString("provides").contains("server")) continue;
            String serverToken = r.optString("accessToken", token);
            String machineId = r.optString("clientIdentifier");
            String base = reachable(ctx, r.optJSONArray("connections"), serverToken);
            if (base == null) continue;
            read(ctx, base, "/library/onDeck?X-Plex-Container-Size=12", serverToken, machineId, true, deck);
            read(ctx, base, "/library/recentlyAdded?X-Plex-Container-Size=12", serverToken, machineId, false, recent);
        }
        List<Item> all = new ArrayList<>(deck);
        for (Item it : recent) {
            if (all.size() >= max) break;
            boolean dup = false;
            for (Item d : all) if (d.ratingKey.equals(it.ratingKey) && d.machineId.equals(it.machineId)) dup = true;
            if (!dup) all.add(it);
        }
        while (all.size() > max) all.remove(all.size() - 1);
        for (Item it : all) {
            if (it.imageUrl == null) continue;
            try {
                byte[] b = bytes(it.imageUrl, null, ctx);
                it.image = BitmapFactory.decodeByteArray(b, 0, b.length);
            } catch (Exception ignored) {
            }
        }
        return all;
    }

    /** Local connections first, then direct remote ones, then the relay; the first that answers wins. */
    private static String reachable(Context ctx, JSONArray conns, String token) {
        if (conns == null) return null;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < conns.length(); i++) {
                JSONObject c = conns.optJSONObject(i);
                if (c == null) continue;
                boolean local = c.optBoolean("local"), relay = c.optBoolean("relay");
                if ((pass == 0 && !local) || (pass == 1 && (local || relay)) || (pass == 2 && !relay)) continue;
                String uri = c.optString("uri");
                try {
                    http("GET", uri + "/identity", ctx, token, 3000);
                    return uri;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static void read(Context ctx, String base, String path, String token, String machineId, boolean onDeck,
                             List<Item> out) {
        try {
            JSONObject mc = new JSONObject(http("GET", base + path, ctx, token)).getJSONObject("MediaContainer");
            JSONArray md = mc.optJSONArray("Metadata");
            if (md == null) return;
            for (int i = 0; i < md.length(); i++) {
                JSONObject m = md.getJSONObject(i);
                String type = m.optString("type");
                String title, subtitle;
                if ("episode".equals(type)) {
                    title = m.optString("grandparentTitle", m.optString("title"));
                    subtitle = "T" + m.optInt("parentIndex") + " · E" + m.optInt("index") + " · " + m.optString("title");
                } else if ("season".equals(type)) {
                    title = m.optString("parentTitle", m.optString("title"));
                    subtitle = m.optString("title");
                } else {
                    title = m.optString("title");
                    subtitle = m.optInt("year") > 0 ? String.valueOf(m.optInt("year")) : "";
                }
                String art = m.optString("art", m.optString("grandparentArt", m.optString("thumb", "")));
                if ("episode".equals(type) && m.has("grandparentArt")) art = m.optString("grandparentArt");
                String img = art.isEmpty() ? null : base + "/photo/:/transcode?width=480&height=270&minSize=1&upscale=1&url="
                        + Uri.encode(art) + "&X-Plex-Token=" + token;
                long duration = m.optLong("duration", 0), offset = m.optLong("viewOffset", 0);
                out.add(new Item(title, subtitle, machineId, m.optString("ratingKey"), onDeck,
                        duration > 0 ? (double) offset / duration : 0, img));
            }
        } catch (Exception ignored) {
        }
    }

    private static String http(String method, String url, Context ctx, String token) throws Exception {
        return http(method, url, ctx, token, 10000);
    }

    private static String http(String method, String url, Context ctx, String token, int timeout) throws Exception {
        HttpURLConnection c = open(method, url, ctx, token, timeout);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] bytes(String url, String token, Context ctx) throws Exception {
        HttpURLConnection c = open("GET", url, ctx, token, 10000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String method, String url, Context ctx, String token, int timeout)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout * 2);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Product", PRODUCT);
        c.setRequestProperty("X-Plex-Client-Identifier", clientId(ctx));
        c.setRequestProperty("X-Plex-Device-Name", "Fire TV (CleanYourTV)");
        c.setRequestProperty("X-Plex-Platform", "Android");
        if (token != null) c.setRequestProperty("X-Plex-Token", token);
        if ("POST".equals(method)) {
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(new byte[0]);
            }
        }
        return c;
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
