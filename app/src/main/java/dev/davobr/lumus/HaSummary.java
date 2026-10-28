package dev.davobr.lumus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

/**
 * Beside the weather: how many lights are on in the house and the bedroom air conditioner, when it runs
 * ("\uD83D\uDCA1 3 · \u2744\uFE0F 22°"). One template call every 30 seconds while Home is in front.
 */
final class HaSummary {
    private static final long REFRESH_MS = 30 * 1000;
    private static final String TEMPLATE = "{% set l = states.light | selectattr('state','eq','on') | list | count %}"
            + "{% set ac = states.climate | rejectattr('state','in',['off','unavailable']) | list %}"
            + "{{ l }}|{% for c in ac %}{{ c.attributes.temperature | int(0) }}{% if not loop.last %},{% endif %}{% endfor %}";
    private static String last;

    private final Context ctx;
    private final TextView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean running;
    private final Runnable tick = this::fetch;

    HaSummary(Context ctx, TextView view) {
        this.ctx = ctx.getApplicationContext();
        this.view = view;
        if (last != null) show(last);
    }

    void resume() {
        running = true;
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void pause() {
        running = false;
        main.removeCallbacks(tick);
    }

    private void fetch() {
        if (!running) return;
        final HaClient ha = HaClient.load(ctx);
        if (ha == null) {
            view.setVisibility(View.GONE);
            return;
        }
        new Thread(() -> {
            String text = null;
            try {
                String[] p = ha.template(TEMPLATE).trim().split("\\|", -1);
                int lights = Integer.parseInt(p[0].trim());
                StringBuilder sb = new StringBuilder();
                sb.append("\uD83D\uDCA1 ").append(lights);
                if (p.length > 1 && !p[1].trim().isEmpty()) {
                    for (String t : p[1].split(",")) sb.append("   \u2744\uFE0F ").append(t.trim()).append("°");
                }
                text = sb.toString();
            } catch (Exception ignored) {
            }
            final String result = text;
            main.post(() -> {
                if (result != null) {
                    last = result;
                    show(result);
                }
                if (running) main.postDelayed(tick, REFRESH_MS);
            });
        }, "ha-summary").start();
    }

    private void show(String text) {
        view.setText(text);
        view.setVisibility(View.VISIBLE);
    }
}
