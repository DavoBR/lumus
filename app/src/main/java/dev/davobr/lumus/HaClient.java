package dev.davobr.lumus;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Home Assistant over its REST API. The address and a long-lived token come from {@code ha.json}
 * ({"url": "http://...:8123", "token": "..."}) pushed into the external files dir; nothing is in the APK.
 * All calls block: run them off the main thread.
 */
final class HaClient {
    static final String FILE = "ha.json";

    final String url, token;

    private HaClient(String url, String token) {
        this.url = url.replaceAll("/+$", "");
        this.token = token;
    }

    /** Null when ha.json is missing or incomplete. */
    static HaClient load(Context ctx) {
        File dir = Config.dir(ctx);
        if (dir == null) return null;
        File f = new File(dir, FILE);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            JSONObject j = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            String u = j.optString("url", ""), t = j.optString("token", "");
            return u.isEmpty() || t.isEmpty() ? null : new HaClient(u, t);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean configured(Context ctx) {
        File dir = Config.dir(ctx);
        return dir != null && new File(dir, FILE).isFile();
    }

    /** Renders a Jinja template on the server (areas, area_entities, ...). */
    String template(String template) throws Exception {
        JSONObject body = new JSONObject().put("template", template);
        return request("POST", "/api/template", body.toString());
    }

    JSONArray states() throws Exception {
        return new JSONArray(request("GET", "/api/states", null));
    }

    void call(String domain, String service, JSONObject data) throws Exception {
        request("POST", "/api/services/" + domain + "/" + service, data == null ? "{}" : data.toString());
    }

    private String request(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(path.startsWith("/api/services/") ? 30000 : 10000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/json");
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
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
