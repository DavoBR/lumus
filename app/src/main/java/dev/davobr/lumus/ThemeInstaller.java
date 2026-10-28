package dev.davobr.lumus;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Themes bundled in assets/themes/<name>/ and applied on the TV itself, the same files tools/theme.sh pushes:
 * config.json, wallpaper.jpg and wallpapers/ in the external files dir, plus a fresh theme_revision so the
 * on-TV column and motion choices follow the new theme. dock_apps and hidden_apps are stripped from a
 * bundled theme so this TV keeps its own dock. "default" removes the theme files.
 */
final class ThemeInstaller {
    private ThemeInstaller() {}

    static final String DEFAULT = "default";
    private static final String PREFS = "theme_installer";
    private static final String KEY = "current";

    static String[] bundled(Context ctx) {
        try {
            String[] names = ctx.getAssets().list("themes");
            return names == null ? new String[0] : names;
        } catch (Exception e) {
            return new String[0];
        }
    }

    static String label(Context ctx, String name) {
        switch (name) {
            case DEFAULT: return ctx.getString(R.string.theme_default);
            case "six-columns": return ctx.getString(R.string.theme_six_columns);
            case "scenes": return ctx.getString(R.string.theme_scenes);
            case "sweden": return ctx.getString(R.string.theme_sweden);
            default: return name.substring(0, 1).toUpperCase() + name.substring(1);
        }
    }

    static String current(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT);
    }

    static boolean apply(Context ctx, String name) {
        File dir = Config.dir(ctx);
        if (dir == null) return false;
        try {
            dir.mkdirs();
            new File(dir, Config.FILE_NAME).delete();
            new File(dir, Config.WALLPAPER_NAME).delete();
            File walls = new File(dir, ThemeFiles.WALLPAPERS);
            File[] old = walls.listFiles();
            if (old != null) for (File f : old) f.delete();
            if (!DEFAULT.equals(name)) {
                AssetManager am = ctx.getAssets();
                String base = "themes/" + name;
                JSONObject cfg = new JSONObject(new String(read(am.open(base + "/config.json")), StandardCharsets.UTF_8));
                cfg.remove("dock_apps");
                cfg.remove("hidden_apps");
                write(new File(dir, Config.FILE_NAME), cfg.toString(2).getBytes(StandardCharsets.UTF_8));
                String[] top = am.list(base);
                if (top != null) for (String f : top) {
                    if (f.equals(Config.WALLPAPER_NAME)) write(new File(dir, f), read(am.open(base + "/" + f)));
                }
                String[] images = am.list(base + "/" + ThemeFiles.WALLPAPERS);
                if (images != null && images.length > 0) {
                    walls.mkdirs();
                    for (String f : images) write(new File(walls, f), read(am.open(base + "/wallpapers/" + f)));
                }
            }
            write(new File(dir, "theme_revision"), UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, name).commit();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
    }
}
