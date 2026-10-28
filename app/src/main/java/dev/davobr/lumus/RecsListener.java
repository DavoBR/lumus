package dev.davobr.lumus;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the recommendation notifications apps post for the TV home (Netflix posts "continueWatching" and
 * "trendingNow" this way on Fire OS 6). Enabled over adb:
 * {@code settings put secure enabled_notification_listeners dev.davobr.lumus/dev.davobr.lumus.RecsListener}.
 * The items are kept in memory and the Continue watching row reads them through {@link #items()}.
 */
public class RecsListener extends NotificationListenerService {

    static final class Rec {
        final String pkg, title, text, group;
        final Bitmap image;
        final PendingIntent intent;
        final long when;

        Rec(String pkg, String title, String text, String group, Bitmap image, PendingIntent intent, long when) {
            this.pkg = pkg; this.title = title; this.text = text; this.group = group;
            this.image = image; this.intent = intent; this.when = when;
        }

        /** Continue watching rows before trending/recommended ones. */
        boolean isContinue() {
            String g = group == null ? "" : group.toLowerCase();
            return g.contains("continue") || g.contains("resume") || g.contains("watchnext") || g.contains("recent");
        }
    }

    private static final List<Rec> recs = new ArrayList<>();
    private static RecsListener instance;
    private static Runnable onChange;

    static synchronized List<Rec> items() {
        return new ArrayList<>(recs);
    }

    static void setOnChange(Runnable r) {
        onChange = r;
    }

    static boolean connected() {
        return instance != null;
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        rebuildSoon();
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (isRec(sbn)) rebuildSoon();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (isRec(sbn)) rebuildSoon();
    }

    // Apps post their rows a dozen at a time: rebuild once, off the main thread (pictures are decoded there).
    private static final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable rebuildTask = () -> worker.execute(this::rebuild);

    private void rebuildSoon() {
        main.removeCallbacks(rebuildTask);
        main.postDelayed(rebuildTask, 800);
    }

    /** Pictures by URI, kept across rebuilds (Netflix re-posts the same rows often). */
    private static final java.util.Map<String, Bitmap> pictures = new java.util.HashMap<>();

    private static boolean isRec(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return n != null && Notification.CATEGORY_RECOMMENDATION.equals(n.category);
    }

    private void rebuild() {
        StatusBarNotification[] all;
        try {
            all = getActiveNotifications();
        } catch (Exception e) {
            return;
        }
        List<Rec> list = new ArrayList<>();
        if (all != null) for (StatusBarNotification sbn : all) {
            if (!isRec(sbn)) continue;
            Notification n = sbn.getNotification();
            Bundle x = n.extras;
            CharSequence title = x == null ? null : x.getCharSequence(Notification.EXTRA_TITLE);
            if (title == null || title.length() == 0) continue;
            CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
            list.add(new Rec(sbn.getPackageName(), title.toString(), text == null ? "" : text.toString(),
                    n.getGroup(), image(n), n.contentIntent, n.when != 0 ? n.when : sbn.getPostTime()));
        }
        // Continue rows first, newest first within each kind; ties keep the app's own order (sortKey/priority).
        Collections.sort(list, (a, b) -> {
            if (a.isContinue() != b.isContinue()) return a.isContinue() ? -1 : 1;
            return Long.compare(b.when, a.when);
        });
        synchronized (RecsListener.class) {
            recs.clear();
            recs.addAll(list);
        }
        Runnable r = onChange;
        if (r != null) new Handler(Looper.getMainLooper()).post(r);
    }

    private Bitmap image(Notification n) {
        Bundle x = n.extras;
        // Android TV recommendations carry their artwork as a content URI (Netflix: its preapp provider).
        String uri = x == null ? null : x.getString("android.backgroundImageUri");
        if (uri != null && !uri.isEmpty()) {
            Bitmap cached = pictures.get(uri);
            if (cached != null) return cached;
            try (java.io.InputStream in = getContentResolver().openInputStream(android.net.Uri.parse(uri))) {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inSampleSize = 2;
                Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, o);
                if (b != null) {
                    if (pictures.size() > 60) pictures.clear();
                    pictures.put(uri, b);
                    return b;
                }
            } catch (Exception ignored) {
            }
        }
        if (x != null) {
            Object pic = x.get(Notification.EXTRA_PICTURE);
            if (pic instanceof Bitmap) return (Bitmap) pic;
            Object large = x.get(Notification.EXTRA_LARGE_ICON_BIG);
            if (large instanceof Bitmap) return (Bitmap) large;
        }
        if (n.largeIcon != null) return n.largeIcon;
        try {
            Icon icon = n.getLargeIcon();
            if (icon != null) {
                Drawable d = icon.loadDrawable(this);
                if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
