package dev.davobr.lumus;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * "Casa": Home Assistant on the TV. The bedroom first (its lights and air conditioner), then every other area with
 * its lights, switches, climate, fans, covers and media players. OK switches, ◀ ▶ dims a light or moves the air
 * conditioner's set temperature, holding OK (or Menu) on the air conditioner picks its mode. Locks and cameras are
 * never offered. States are read every 3 seconds while the screen is open.
 */
public class HomeActivity extends Activity {

    static final String FAVORITE_AREA = "bedroom";
    private static final long POLL_MS = 3000;
    /** Pseudo tile in the bedroom: switches off its lights and air conditioner. */
    private static final String ALL_OFF = "_all_off";
    /** Configuration switches HA exposes next to the real device: not worth a tile on a TV. */
    private static final Pattern NOISE = Pattern.compile(
            "motion|lock|disturb|fresh_air|health|panel_light|quiet|xtra_fan|effect|protection|filtering|query_log"
                    + "|safe_|parental|child");
    private static final String AREAS_TEMPLATE = "{% set ns = namespace(o=[]) %}{% for a in areas() %}"
            + "{% set ns.o = ns.o + [{\"id\": a, \"name\": area_name(a), \"entities\": area_entities(a)"
            + " | reject('is_hidden_entity') | select('match', '^(light|switch|climate|fan|cover|media_player)\\\\.')"
            + " | list}] %}{% endfor %}{{ ns.o | tojson }}";

    private static final class Area {
        final String id, name;
        final List<String> entities = new ArrayList<>();
        Area(String id, String name) { this.id = id; this.name = name; }
    }

    private HaClient ha;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Area> areas = new ArrayList<>();
    private final Map<String, JSONObject> states = new HashMap<>();
    /** Entities just switched from the TV: keep the optimistic state until the device has had time to report. */
    private final Map<String, Long> holdUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> shown = new ArrayList<>();
    private Area current;
    private LinearLayout areaList;
    private TextView title, status;
    private RecyclerView grid;
    private final TileAdapter adapter = new TileAdapter();
    private UiSounds sounds;
    private float density;
    private boolean resumed;
    private boolean heldOpen;
    private boolean controlsArmed;
    private AlertDialog controlsDialog;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshStates();
            if (resumed) main.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        ha = HaClient.load(this);
        sounds = new UiSounds(this);
        buildUi();
        if (ha == null) {
            status.setText(R.string.ha_not_configured);
            return;
        }
        status.setText(R.string.ha_loading);
        io.execute(this::loadAreas);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        sounds.resume();
        if (ha != null) main.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        sounds.pause();
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        calls.shutdown();
        sounds.release();
    }

    private int dp(float v) { return Math.round(v * density); }

    // ---- layout --------------------------------------------------------------------------------------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07090E);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(56), dp(32), dp(56), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = new TextView(this);
        h.setText(R.string.ha_title);
        h.setTextColor(Color.WHITE);
        h.setTextSize(30);
        head.addView(h);
        status = new TextView(this);
        status.setTextColor(0x99FFFFFF);
        status.setTextSize(14);
        status.setPadding(dp(20), 0, 0, 0);
        head.addView(status);
        page.addView(head);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1);
        blp.topMargin = dp(24);
        page.addView(body, blp);

        ScrollView side = new ScrollView(this);
        side.setVerticalScrollBarEnabled(false);
        side.setClipToPadding(false);
        areaList = new LinearLayout(this);
        areaList.setOrientation(LinearLayout.VERTICAL);
        side.addView(areaList);
        body.addView(side, new LinearLayout.LayoutParams(dp(220), -1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(32), 0, 0, 0);
        title = new TextView(this);
        title.setTextColor(0xE6FFFFFF);
        title.setTextSize(20);
        right.addView(title);
        TextView hint = new TextView(this);
        hint.setText(R.string.ha_hint);
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(12);
        hint.setPadding(0, dp(4), 0, dp(12));
        right.addView(hint);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 3));
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(dp(8), dp(8), dp(8), dp(40));
        right.addView(grid, new LinearLayout.LayoutParams(-1, -1));
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        setContentView(root);
    }

    private GradientDrawable rounded(int color, int strokeColor, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        if (stroke > 0) g.setStroke(stroke, strokeColor);
        return g;
    }

    private void showAreas() {
        areaList.removeAllViews();
        for (final Area a : areas) {
            final TextView t = new TextView(this);
            t.setText(FAVORITE_AREA.equals(a.id) ? "★  " + a.name : a.name);
            t.setTextSize(17);
            t.setTextColor(0xCCFFFFFF);
            t.setPadding(dp(18), dp(12), dp(18), dp(12));
            t.setFocusable(true);
            t.setClickable(true);
            t.setSoundEffectsEnabled(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(6);
            t.setLayoutParams(lp);
            t.setOnFocusChangeListener((v, has) -> {
                styleArea(t, a, has);
                if (has) {
                    if (areaList.getTag() != null) sounds.nav();
                    areaList.setTag(Boolean.TRUE);
                    select(a);
                }
            });
            t.setOnClickListener(v -> focusGrid());
            t.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusGrid();
                    return true;
                }
                return false;
            });
            styleArea(t, a, false);
            areaList.addView(t);
        }
        if (areaList.getChildCount() > 0) areaList.getChildAt(0).requestFocus();
    }

    private void styleArea(TextView t, Area a, boolean focused) {
        boolean selected = a == current;
        t.setBackground(focused ? rounded(0x33FFFFFF, Color.WHITE, dp(2))
                : selected ? rounded(0x1FFFFFFF, 0, 0) : null);
        t.setTextColor(focused || selected ? Color.WHITE : 0x99FFFFFF);
    }

    private void select(Area a) {
        if (current == a) return;
        current = a;
        for (int i = 0; i < areaList.getChildCount(); i++) {
            View v = areaList.getChildAt(i);
            styleArea((TextView) v, areas.get(i), v.hasFocus());
        }
        title.setText(a.name);
        shown.clear();
        shown.addAll(a.entities);
        adapter.notifyDataSetChanged();
    }

    private void focusGrid() {
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(0);
        if (vh != null) vh.itemView.requestFocus();
    }

    private void focusArea() {
        int i = areas.indexOf(current);
        if (i >= 0 && i < areaList.getChildCount()) areaList.getChildAt(i).requestFocus();
    }

    // ---- data ----------------------------------------------------------------------------------------

    private void loadAreas() {
        try {
            JSONArray arr = new JSONArray(ha.template(AREAS_TEMPLATE));
            List<Area> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Area a = new Area(o.getString("id"), o.getString("name"));
                JSONArray es = o.getJSONArray("entities");
                // Lights and climate first, then the rest; noise switches left out.
                List<String> later = new ArrayList<>();
                for (int k = 0; k < es.length(); k++) {
                    String e = es.getString(k);
                    if (e.startsWith("switch.") && NOISE.matcher(e).find()) continue;
                    if (e.startsWith("light.") || e.startsWith("climate.")) a.entities.add(e);
                    else later.add(e);
                }
                a.entities.addAll(later);
                // A relay switch that a light entity already wraps (switch.x next to light.x) is the same device.
                List<String> dupes = new ArrayList<>();
                for (String e : a.entities) {
                    if (e.startsWith("switch.") && a.entities.contains("light." + e.substring(7))) dupes.add(e);
                }
                a.entities.removeAll(dupes);
                if (a.entities.isEmpty()) continue;
                if (FAVORITE_AREA.equals(a.id)) a.entities.add(0, ALL_OFF);
                if (FAVORITE_AREA.equals(a.id)) list.add(0, a);
                else list.add(a);
            }
            refreshStatesNow();
            // Two media players for one TV (an integration and a cast device) show the same name: keep the first.
            for (Area a : list) {
                List<String> names = new ArrayList<>(), drop = new ArrayList<>();
                for (String e : a.entities) {
                    if (!e.startsWith("media_player.")) continue;
                    JSONObject st = state(e);
                    String n = st == null ? e : st.optJSONObject("attributes") == null ? e
                            : st.optJSONObject("attributes").optString("friendly_name", e);
                    if (names.contains(n)) drop.add(e); else names.add(n);
                }
                a.entities.removeAll(drop);
            }
            main.post(() -> {
                areas.clear();
                areas.addAll(list);
                status.setText("");
                showAreas();
                if (!areas.isEmpty()) select(areas.get(0));
            });
        } catch (Exception e) {
            main.post(() -> status.setText(R.string.ha_error));
        }
    }

    private void refreshStates() {
        io.execute(() -> {
            if (!refreshStatesNow()) return;
            main.post(this::updateVisibleTiles);
        });
    }

    private boolean refreshStatesNow() {
        try {
            JSONArray all = ha.states();
            Map<String, JSONObject> m = new HashMap<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject s = all.getJSONObject(i);
                m.put(s.getString("entity_id"), s);
            }
            synchronized (states) {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Long> h : new ArrayList<>(holdUntil.entrySet())) {
                    JSONObject mine = states.get(h.getKey());
                    if (h.getValue() > now && mine != null) m.put(h.getKey(), mine);
                    else holdUntil.remove(h.getKey());
                }
                states.clear();
                states.putAll(m);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateVisibleTiles() {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View v = grid.getChildAt(i);
            int pos = grid.getChildAdapterPosition(v);
            if (pos >= 0) adapter.bind((Tile) grid.getChildViewHolder(v), pos);
        }
    }

    private JSONObject state(String id) {
        synchronized (states) {
            return states.get(id);
        }
    }

    private void call(String domain, String service, JSONObject data) {
        io.execute(() -> {
            try {
                ha.call(domain, service, data);
                Thread.sleep(1500);
                if (refreshStatesNow()) main.post(this::updateVisibleTiles);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, R.string.ha_error, Toast.LENGTH_SHORT).show());
            }
        });
    }

    /**
     * On/off with the latest wish winning. Some devices take seconds to answer a service call (a template light here
     * blocks for 8 s), and a second press during that time must not be dropped or applied out of order: one call per
     * entity at a time, and when it returns the newest wanted state is sent if it differs.
     */
    private final Map<String, Boolean> wanted = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> busy = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final ExecutorService calls = Executors.newCachedThreadPool();

    private void switchTo(String id, boolean on) {
        wanted.put(id, on);
        if (!busy.add(id)) return;
        calls.execute(() -> {
            String domain = id.substring(0, id.indexOf('.'));
            Boolean sent = null;
            try {
                while (true) {
                    Boolean want = wanted.get(id);
                    if (want == null || want.equals(sent)) break;
                    sent = want;
                    try {
                        ha.call(domain, want ? "turn_on" : "turn_off", target(id));
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this, R.string.ha_error, Toast.LENGTH_SHORT).show());
                        break;
                    }
                }
            } finally {
                busy.remove(id);
                holdUntil.remove(id);
                if (refreshStatesNow()) main.post(this::updateVisibleTiles);
            }
        });
    }

    private static JSONObject target(String id) {
        try {
            return new JSONObject().put("entity_id", id);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---- actions -------------------------------------------------------------------------------------

    private void toggle(String id) {
        if (ALL_OFF.equals(id)) {
            for (String e : current.entities) {
                JSONObject st = state(e);
                if (st == null) continue;
                String v = st.optString("state");
                if (e.startsWith("light.") && "on".equals(v)) {
                    switchTo(e, false);
                    optimistic(e, v);
                    holdUntil.put(e, System.currentTimeMillis() + 15000);
                } else if (e.startsWith("climate.") && !"off".equals(v)) switchTo(e, false);
            }
            return;
        }
        String domain = id.substring(0, id.indexOf('.'));
        JSONObject s = state(id);
        String st = s == null ? "" : s.optString("state");
        if ("climate".equals(domain)) {
            call("climate", "off".equals(st) ? "turn_on" : "turn_off", target(id));
        } else if ("media_player".equals(domain)) {
            call("media_player", "off".equals(st) || "standby".equals(st) ? "turn_on" : "turn_off", target(id));
        } else if ("cover".equals(domain)) {
            call("cover", "toggle", target(id));
        } else {
            // Explicit on/off from what the tile shows, not toggle: slow devices (a relay that reports a second
            // later) would otherwise undo a quick second press.
            switchTo(id, !"on".equals(st));
        }
        optimistic(id, st);
        holdUntil.put(id, System.currentTimeMillis() + 15000);
    }

    /** Flip the tile at once; the next poll corrects it if the device did not follow. */
    private void optimistic(String id, String st) {
        JSONObject s = state(id);
        if (s == null) return;
        try {
            if (id.startsWith("light.") || id.startsWith("switch.") || id.startsWith("fan."))
                s.put("state", "on".equals(st) ? "off" : "on");
        } catch (Exception ignored) {
        }
        updateVisibleTiles();
    }

    /** ◀ ▶: brightness by 10 % for a light, set temperature by one degree for climate. */
    private boolean adjust(String id, int dir) {
        JSONObject s = state(id);
        if (s == null) return false;
        JSONObject attr = s.optJSONObject("attributes");
        if (attr == null) attr = new JSONObject();
        try {
            if (id.startsWith("light.")) {
                JSONArray modes = attr.optJSONArray("supported_color_modes");
                boolean dimmable = modes != null && !(modes.length() == 1 && "onoff".equals(modes.optString(0)));
                if (!dimmable) return false;
                int b = "on".equals(s.optString("state")) ? attr.optInt("brightness", 0) : 0;
                int nb = Math.max(3, Math.min(255, b + Math.round(25.5f * dir)));
                attr.put("brightness", nb);
                s.put("state", "on");
                updateVisibleTiles();
                call("light", "turn_on", target(id).put("brightness", nb));
                return true;
            }
            if (id.startsWith("climate.")) {
                double t = attr.optDouble("temperature", Double.NaN);
                if (Double.isNaN(t)) return false;
                double step = attr.optDouble("target_temp_step", 1);
                double nt = Math.max(attr.optDouble("min_temp", 16), Math.min(attr.optDouble("max_temp", 30), t + step * dir));
                attr.put("temperature", nt);
                updateVisibleTiles();
                call("climate", "set_temperature", target(id).put("temperature", nt));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean dimmable(String id) {
        JSONObject s = state(id);
        JSONObject attr = s == null ? null : s.optJSONObject("attributes");
        JSONArray modes = attr == null ? null : attr.optJSONArray("supported_color_modes");
        return id.startsWith("light.") && modes != null && !(modes.length() == 1 && "onoff".equals(modes.optString(0)));
    }

    private boolean controllable(String id) {
        return dimmable(id) || id.startsWith("climate.");
    }

    private interface Step { void step(int dir); }

    /**
     * Hold OK (or Menu) on a dimmable light or the air conditioner: a small panel where ◀ ▶ change the value of the
     * focused row (brightness; or temperature and mode), ▲ ▼ move between rows and OK or Back close it. The tiles
     * themselves keep ◀ ▶ for moving between devices.
     */
    private void showControls(final String id) {
        // A held OK reaches here twice (the key's repeat and the view's own long click): one panel only.
        if (controlsDialog != null && controlsDialog.isShowing()) return;
        JSONObject s0 = state(id);
        if (s0 == null) return;
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        final List<TextView> rows = new ArrayList<>();
        final List<Runnable> refreshers = new ArrayList<>();
        final AlertDialog[] dlg = new AlertDialog[1];
        String name = s0.optJSONObject("attributes") == null ? id : s0.optJSONObject("attributes").optString("friendly_name", id);
        if (id.startsWith("light.")) {
            final TextView r = controlRow(box, rows, refreshers, dlg, dir -> adjust(id, dir));
            refreshers.add(() -> {
                JSONObject s = state(id);
                JSONObject at = s == null ? null : s.optJSONObject("attributes");
                int b = at == null ? -1 : at.optInt("brightness", -1);
                boolean on = s != null && "on".equals(s.optString("state"));
                r.setText(getString(R.string.ha_brightness) + ":   \u25C0  " + (on && b >= 0 ? Math.round(b * 100 / 255f) + "%" : getString(R.string.ha_off)) + "  \u25B6");
            });
        } else {
            final TextView temp = controlRow(box, rows, refreshers, dlg, dir -> adjust(id, dir));
            final TextView mode = controlRow(box, rows, refreshers, dlg, dir -> cycleMode(id, dir));
            refreshers.add(() -> {
                JSONObject s = state(id);
                JSONObject at = s == null ? null : s.optJSONObject("attributes");
                double t = at == null ? Double.NaN : at.optDouble("temperature", Double.NaN);
                temp.setText(getString(R.string.ha_temperature) + ":   \u25C0  " + (Double.isNaN(t) ? "\u2013" : String.format(Locale.ROOT, "%.0f\u00B0", t)) + "  \u25B6");
                mode.setText(getString(R.string.ha_mode) + ":   \u25C0  " + hvacLabel(s == null ? "" : s.optString("state")) + "  \u25B6");
            });
        }
        for (Runnable rf : refreshers) rf.run();
        // Opened from a held OK: wait for its release. Opened from Menu: OK works at once.
        controlsArmed = !heldOpen;
        dlg[0] = new AlertDialog.Builder(this).setTitle(name).setView(box).create();
        controlsDialog = dlg[0];
        final Runnable live = new Runnable() {
            @Override public void run() {
                if (!dlg[0].isShowing()) return;
                for (Runnable rf : refreshers) rf.run();
                main.postDelayed(this, 1000);
            }
        };
        dlg[0].setOnDismissListener(d -> {
            main.removeCallbacks(live);
            heldOpen = false;
        });
        dlg[0].show();
        main.postDelayed(live, 1000);
        rows.get(0).requestFocus();
    }

    private TextView controlRow(LinearLayout box, List<TextView> rows, final List<Runnable> refreshers,
                                final AlertDialog[] dlg, final Step onStep) {
        final TextView r = new TextView(this);
        r.setTextSize(20);
        r.setTextColor(Color.WHITE);
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        r.setFocusable(true);
        r.setSoundEffectsEnabled(false);
        r.setOnFocusChangeListener((v, has) -> r.setBackground(has ? rounded(0x33FFFFFF, Color.WHITE, dp(2)) : null));
        r.setOnKeyListener((v, key, ev) -> {
            boolean ok = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
            // The panel opens while OK is still held: the remote keeps sending that press (repeats, then its
            // release) into the panel. Ignore OK until it has been let go once, or the panel closes at once.
            if (ok && !controlsArmed) {
                if (ev.getAction() == KeyEvent.ACTION_UP) controlsArmed = true;
                return true;
            }
            if (ok && ev.getAction() == KeyEvent.ACTION_UP) return true;
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (ok && ev.getRepeatCount() > 0) return true;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                onStep.step(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                sounds.nav();
                for (Runnable rf : refreshers) rf.run();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                sounds.select();
                if (dlg[0] != null) dlg[0].dismiss();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        box.addView(r, lp);
        rows.add(r);
        return r;
    }

    /** ◀ ▶ on the mode row: the next or previous of the device's own hvac modes, sent at once. */
    private void cycleMode(String id, int dir) {
        JSONObject s = state(id);
        JSONObject a = s == null ? null : s.optJSONObject("attributes");
        JSONArray modes = a == null ? null : a.optJSONArray("hvac_modes");
        if (modes == null || modes.length() == 0) return;
        int i = 0;
        for (int k = 0; k < modes.length(); k++) if (modes.optString(k).equals(s.optString("state"))) i = k;
        String next = modes.optString(((i + dir) % modes.length() + modes.length()) % modes.length());
        try {
            s.put("state", next);
            updateVisibleTiles();
            call("climate", "set_hvac_mode", target(id).put("hvac_mode", next));
        } catch (Exception ignored) {
        }
    }

    private void chooseMode(String id) {
        JSONObject s = state(id);
        JSONArray modes = s == null ? null : s.optJSONObject("attributes").optJSONArray("hvac_modes");
        if (modes == null || modes.length() == 0) return;
        String[] keys = new String[modes.length()], labels = new String[modes.length()];
        int checked = -1;
        for (int i = 0; i < modes.length(); i++) {
            keys[i] = modes.optString(i);
            labels[i] = hvacLabel(keys[i]);
            if (keys[i].equals(s.optString("state"))) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.ha_mode)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    try {
                        call("climate", "set_hvac_mode", target(id).put("hvac_mode", keys[which]));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String hvacLabel(String m) {
        switch (m) {
            case "off": return getString(R.string.ha_mode_off);
            case "cool": return getString(R.string.ha_mode_cool);
            case "heat": return getString(R.string.ha_mode_heat);
            case "dry": return getString(R.string.ha_mode_dry);
            case "fan_only": return getString(R.string.ha_mode_fan);
            case "auto": case "heat_cool": return getString(R.string.ha_mode_auto);
            default: return m;
        }
    }

    // ---- tiles ---------------------------------------------------------------------------------------

    private final class Tile extends RecyclerView.ViewHolder {
        final TextView icon, name, detail;
        final LinearLayout box;
        Tile(LinearLayout box) {
            super(box);
            this.box = box;
            icon = (TextView) box.getChildAt(0);
            name = (TextView) box.getChildAt(1);
            detail = (TextView) box.getChildAt(2);
        }
    }

    private final class TileAdapter extends RecyclerView.Adapter<Tile> {
        @NonNull
        @Override
        public Tile onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(HomeActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(16), dp(18), dp(16));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, dp(132));
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            box.setLayoutParams(lp);
            box.setFocusable(true);
            box.setClickable(true);
            box.setLongClickable(true);
            box.setSoundEffectsEnabled(false);
            TextView icon = new TextView(HomeActivity.this);
            icon.setTextSize(26);
            TextView name = new TextView(HomeActivity.this);
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(0, dp(8), 0, 0);
            TextView detail = new TextView(HomeActivity.this);
            detail.setTextColor(0xB3FFFFFF);
            detail.setTextSize(13);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(icon);
            box.addView(name);
            box.addView(detail);
            Tile t = new Tile(box);
            box.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
                int pos = t.getAdapterPosition();
                if (pos >= 0) bind(t, pos);
                if (has) sounds.nav();
            });
            box.setOnClickListener(v -> {
                int pos = t.getAdapterPosition();
                if (pos < 0) return;
                sounds.select();
                toggle(shown.get(pos));
            });
            box.setOnLongClickListener(v -> {
                int pos = t.getAdapterPosition();
                if (pos >= 0 && controllable(shown.get(pos))) showControls(shown.get(pos));
                return true;
            });
            box.setOnKeyListener((v, key, ev) -> {
                if (ev.getAction() == KeyEvent.ACTION_UP && heldOpen
                        && (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) {
                    heldOpen = false;
                    return true;
                }
                if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = t.getAdapterPosition();
                if (pos < 0) return false;
                String id = shown.get(pos);
                // Held OK: the second auto-repeat of the key opens the controls (works for remotes and injected presses).
                if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) && ev.getRepeatCount() == 1
                        && controllable(id)) {
                    heldOpen = true;
                    showControls(id);
                    return true;
                }
                if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) && ev.getRepeatCount() > 1) return true;
                if (key == KeyEvent.KEYCODE_MENU && controllable(id)) {
                    showControls(id);
                    return true;
                }
                // Left from the first column goes back to the areas; otherwise D-pad moves between tiles.
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && pos % 3 == 0) {
                    focusArea();
                    return true;
                }
                return false;
            });
            return t;
        }

        void bind(Tile t, int pos) {
            String id = shown.get(pos);
            if (ALL_OFF.equals(id)) {
                t.icon.setText("\uD83C\uDF19");
                t.name.setText(R.string.ha_all_off);
                int lit = 0;
                for (String e : shown) {
                    JSONObject st = state(e);
                    if (st == null) continue;
                    if ((e.startsWith("light.") && "on".equals(st.optString("state")))
                            || (e.startsWith("climate.") && !"off".equals(st.optString("state")))) lit++;
                }
                t.detail.setText(lit == 0 ? getString(R.string.ha_all_off_done) : getString(R.string.ha_all_off_count, lit));
                t.detail.setTextColor(0xB3FFFFFF);
                boolean f = t.box.hasFocus();
                t.box.setBackground(rounded(0x14FFFFFF, Color.WHITE, f ? dp(3) : 0));
                t.box.setAlpha(1f);
                return;
            }
            JSONObject s = state(id);
            String st = s == null ? "unavailable" : s.optString("state");
            JSONObject attr = s == null ? new JSONObject() : s.optJSONObject("attributes");
            if (attr == null) attr = new JSONObject();
            String name = attr.optString("friendly_name", id);
            if (current != null && name.startsWith(current.name + " ")) name = name.substring(current.name.length() + 1);
            t.name.setText(name);
            boolean on;
            String icon, detail;
            int accent;
            String domain = id.substring(0, id.indexOf('.'));
            switch (domain) {
                case "light": {
                    on = "on".equals(st);
                    icon = "\uD83D\uDCA1";
                    int b = attr.optInt("brightness", -1);
                    detail = on ? getString(R.string.ha_on) + (b >= 0 ? String.format(Locale.ROOT, " · %d%%", Math.round(b * 100 / 255f)) : "")
                            : getString(R.string.ha_off);
                    accent = 0xFFFFC94D;
                    break;
                }
                case "climate": {
                    on = !"off".equals(st) && !"unavailable".equals(st);
                    icon = "\u2744\uFE0F";
                    double target = attr.optDouble("temperature", Double.NaN), now = attr.optDouble("current_temperature", Double.NaN);
                    String nowText = Double.isNaN(now) ? "" : String.format(Locale.ROOT, " · %s %.0f°", getString(R.string.ha_room), now);
                    detail = on ? hvacLabel(st) + (Double.isNaN(target) ? "" : String.format(Locale.ROOT, " · %.0f°", target)) + nowText
                            : getString(R.string.ha_off) + nowText;
                    accent = 0xFF5AB8FF;
                    break;
                }
                case "media_player": {
                    on = !"off".equals(st) && !"standby".equals(st) && !"unavailable".equals(st);
                    icon = "\uD83D\uDCFA";
                    detail = on ? getString(R.string.ha_on) : getString(R.string.ha_off);
                    accent = 0xFFB18CFF;
                    break;
                }
                case "cover": {
                    on = "open".equals(st);
                    icon = "\uD83E\uDE9F";
                    detail = st;
                    accent = 0xFF7FD68A;
                    break;
                }
                case "fan":
                    on = "on".equals(st);
                    icon = "\uD83C\uDF00";
                    detail = on ? getString(R.string.ha_on) : getString(R.string.ha_off);
                    accent = 0xFF7FD6D0;
                    break;
                default:
                    on = "on".equals(st);
                    icon = "\uD83D\uDD0C";
                    detail = on ? getString(R.string.ha_on) : getString(R.string.ha_off);
                    accent = 0xFF7FD68A;
            }
            if ("unavailable".equals(st)) detail = getString(R.string.ha_unavailable);
            t.icon.setText(icon);
            t.detail.setText(detail);
            boolean focused = t.box.hasFocus();
            int bg = on ? (accent & 0x00FFFFFF) | 0x33000000 : 0x14FFFFFF;
            t.box.setBackground(rounded(bg, focused ? Color.WHITE : (on ? accent : 0), focused ? dp(3) : (on ? dp(1) : 0)));
            t.box.setAlpha("unavailable".equals(st) ? 0.45f : 1f);
            t.detail.setTextColor(on ? accent : 0xB3FFFFFF);
        }

        @Override
        public void onBindViewHolder(@NonNull Tile t, int position) {
            bind(t, position);
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }
    }
}
