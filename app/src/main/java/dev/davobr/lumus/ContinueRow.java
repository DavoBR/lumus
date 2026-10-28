package dev.davobr.lumus;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * A Samsung-style "Continue watching" row above the dock: what apps publish as TV recommendations (Netflix's
 * continue watching and trending rows, through {@link RecsListener}) and the user's Plex On Deck / Recently Added
 * (own or shared servers, through {@link PlexClient}). Hidden while it has nothing to show or is switched off in
 * Launcher settings.
 */
final class ContinueRow {

    static final String PREF_KEY = "continue_row";
    private static final long PLEX_REFRESH_MS = 10 * 60 * 1000;
    private static final int MAX_PER_SOURCE = 12;

    interface Host {
        void focusDock();
        View topButton();
        /** The row appeared or went: the grid has less or more room and must park its dock again. */
        void rowVisibilityChanged();
    }

    static final class Entry {
        final String title, subtitle;
        final Bitmap image;
        final Drawable badge;
        final double progress;
        final Runnable open;

        Entry(String title, String subtitle, Bitmap image, Drawable badge, double progress, Runnable open) {
            this.title = title; this.subtitle = subtitle; this.image = image; this.badge = badge;
            this.progress = progress; this.open = open;
        }
    }

    private static List<PlexClient.Item> plexCache = new ArrayList<>();
    private static long plexFetched;
    private static boolean plexRunning;

    private final Activity activity;
    private final Host host;
    private final View block;
    private final RecyclerView row;
    private final float density;
    private final List<Entry> entries = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private int lastFocused;

    ContinueRow(Activity activity, Host host, View block, RecyclerView row) {
        this.activity = activity;
        this.host = host;
        this.block = block;
        this.row = row;
        this.density = activity.getResources().getDisplayMetrics().density;
        row.setLayoutManager(new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false));
        row.setAdapter(adapter);
        row.setItemAnimator(null);
        RecsListener.setOnChange(this::rebuild);
    }

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_KEY, true);
    }

    boolean visible() {
        return block.getVisibility() == View.VISIBLE;
    }

    void focus() {
        if (entries.isEmpty()) return;
        int pos = Math.max(0, Math.min(lastFocused, entries.size() - 1));
        RecyclerView.ViewHolder vh = row.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
        else {
            row.scrollToPosition(pos);
            row.post(() -> {
                RecyclerView.ViewHolder v = row.findViewHolderForAdapterPosition(pos);
                if (v != null) v.itemView.requestFocus();
            });
        }
    }

    /** Rebuilds from the in-memory sources now and refreshes Plex in the background when it is stale. */
    void refresh() {
        rebuild();
        if (!enabled(activity) || !PlexClient.linked(activity) || plexRunning) return;
        if (System.currentTimeMillis() - plexFetched < PLEX_REFRESH_MS) return;
        plexRunning = true;
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            try {
                List<PlexClient.Item> items = PlexClient.fetch(app, MAX_PER_SOURCE);
                plexCache = items;
                plexFetched = System.currentTimeMillis();
            } catch (Throwable ignored) {
            } finally {
                plexRunning = false;
            }
            activity.runOnUiThread(this::rebuild);
        }, "plex-continue").start();
    }

    /** Forget Plex results, e.g. after linking or unlinking. */
    static void invalidatePlex() {
        plexFetched = 0;
        plexCache = new ArrayList<>();
    }

    void rebuild() {
        if (activity.isFinishing()) return;
        entries.clear();
        if (enabled(activity)) {
            PackageManager pm = activity.getPackageManager();
            List<RecsListener.Rec> recs = RecsListener.items();
            List<Entry> later = new ArrayList<>();
            // Continue items from every source first, then trending / recently added.
            for (RecsListener.Rec r : recs) {
                String title = r.title, sub = r.text;
                // Netflix titles its continue row "Continue watching for <profile>" and puts the show in the text.
                String lt = title.toLowerCase();
                if ((lt.startsWith("continuar viendo") || lt.startsWith("continue watching")) && sub != null && !sub.isEmpty()) {
                    int cut = -1;
                    for (char ch : new char[]{':', ','}) {
                        int k = sub.indexOf(ch);
                        if (k > 0 && (cut < 0 || k < cut)) cut = k;
                    }
                    title = cut > 0 ? sub.substring(0, cut).trim() : sub;
                    sub = cut > 0 ? sub.substring(cut + 1).trim() : "";
                }
                final String fTitle = title, fSub = sub;
                Entry e = new Entry(fTitle, fSub, r.image, icon(pm, r.pkg), 0, () -> send(r.intent, r.pkg));
                (r.isContinue() ? entries : later).add(e);
            }
            Drawable plexIcon = icon(pm, PlexClient.PKG);
            for (PlexClient.Item it : plexCache) {
                Entry e = new Entry(it.title, it.subtitle, it.image, plexIcon, it.onDeck ? it.progress : 0,
                        () -> openPlex(it));
                (it.onDeck ? entries : later).add(e);
            }
            entries.addAll(later);
            while (entries.size() > 24) entries.remove(entries.size() - 1);
        }
        adapter.notifyDataSetChanged();
        boolean show = !entries.isEmpty();
        boolean hadFocus = block.hasFocus();
        boolean was = block.getVisibility() == View.VISIBLE;
        block.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show && hadFocus) host.focusDock();
        if (was != show) host.rowVisibilityChanged();
        // Down from the top bar lands on this row while it shows, on the grid otherwise.
        ViewGroup bar = activity.findViewById(R.id.topButtons);
        if (bar != null) for (int i = 0; i < bar.getChildCount(); i++) {
            bar.getChildAt(i).setNextFocusDownId(show ? R.id.continueRow : R.id.grid);
        }
    }

    private static Drawable icon(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private void send(PendingIntent intent, String pkg) {
        try {
            if (intent != null) {
                intent.send();
                return;
            }
        } catch (Exception ignored) {
        }
        launch(pkg);
    }

    private void openPlex(PlexClient.Item it) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, it.deepLink()).setPackage(PlexClient.PKG)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            launch(PlexClient.PKG);
        }
    }

    private void launch(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    // ---- views -------------------------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * density);
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image, badge;
        final TextView title, subtitle;
        final View progressTrack, progress;
        final GradientDrawable frame;

        Holder(FrameLayout card) {
            super(card);
            image = (ImageView) card.getChildAt(0);
            LinearLayout text = (LinearLayout) card.getChildAt(2);
            title = (TextView) text.getChildAt(0);
            subtitle = (TextView) text.getChildAt(1);
            badge = (ImageView) card.getChildAt(3);
            progressTrack = card.getChildAt(4);
            progress = card.getChildAt(5);
            frame = (GradientDrawable) card.getForeground();
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout card = new FrameLayout(activity);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(200), dp(112));
            lp.setMarginEnd(dp(18));
            card.setLayoutParams(lp);
            card.setFocusable(true);
            card.setClickable(true);
            card.setSoundEffectsEnabled(false);
            card.setClipToOutline(true);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1A1D24);
            bg.setCornerRadius(dp(12));
            card.setBackground(bg);
            card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(12));
            frame.setColor(Color.TRANSPARENT);
            frame.setStroke(0, Color.WHITE);
            card.setForeground(frame);

            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(image, new FrameLayout.LayoutParams(-1, -1));

            View shade = new View(activity);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x00000000, 0xE6000000}));
            card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(12), 0, dp(12), dp(12));
            TextView title = new TextView(activity);
            title.setTextColor(Color.WHITE);
            title.setTextSize(15);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView sub = new TextView(activity);
            sub.setTextColor(0xCCFFFFFF);
            sub.setTextSize(11);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(title);
            text.addView(sub);
            card.addView(text, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            ImageView badge = new ImageView(activity);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
            blp.setMargins(0, dp(8), dp(8), 0);
            card.addView(badge, blp);

            View track = new View(activity);
            track.setBackgroundColor(0x55FFFFFF);
            card.addView(track, new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM));
            View bar = new View(activity);
            bar.setBackgroundColor(0xFFE5A00D);
            card.addView(bar, new FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM));

            Holder h = new Holder(card);
            card.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
                h.frame.setStroke(has ? dp(3) : 0, Color.WHITE);
                if (has) lastFocused = h.getAdapterPosition();
            });
            card.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = h.getAdapterPosition();
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    host.focusDock();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    View top = host.topButton();
                    if (top != null) top.requestFocus();
                    return true;
                }
                // Stop at the row's ends instead of letting focus escape sideways.
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && pos == 0) return true;
                return key == KeyEvent.KEYCODE_DPAD_RIGHT && pos == entries.size() - 1;
            });
            card.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos >= 0 && pos < entries.size()) entries.get(pos).open.run();
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Entry e = entries.get(position);
            if (e.image != null) {
                h.image.setImageBitmap(e.image);
                h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                h.image.setImageDrawable(e.badge);
                h.image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            }
            h.title.setText(e.title);
            h.subtitle.setText(e.subtitle);
            h.subtitle.setVisibility(e.subtitle == null || e.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            h.badge.setImageDrawable(e.badge);
            boolean showProgress = e.progress > 0.01;
            h.progressTrack.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            h.progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams lp = h.progress.getLayoutParams();
            lp.width = (int) (dp(200) * Math.min(1, e.progress));
            h.progress.setLayoutParams(lp);
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
