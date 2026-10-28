package dev.davobr.lumus;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.provider.Settings;

/**
 * The launcher's own navigation and select sounds (res/raw, made by launcher/sfx/make_sfx.py), played instead of
 * the system's: every focusable view sets soundEffectsEnabled="false". Follows the TV's "sound effects" setting
 * like the system sounds do.
 *
 * Optional (config.xml keep_audio_awake): on TVs whose single output (for example HDMI ARC to a soundbar) goes to
 * standby after a few seconds of silence, waking it delays each sound noticeably, and some soundbars switch their
 * amplifier off on all-zero samples and swallow the first sounds. While the home screen is in front, a looping
 * stream of noise at about -75 dBFS, far below hearing, keeps both awake. It stops in onPause, so it never keeps
 * the TV from sleeping.
 */
final class UiSounds {

    private static final float VOLUME = 0.75f;
    private static final int RATE = 48000;   // the output's own rate, like res/raw: no resampling, fast track
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final Context context;
    private final SoundPool pool;
    private final int nav, select;
    private final boolean keepOutputAwake;
    private volatile AudioTrack keepAwake;
    private Thread keepAwakeWriter;

    UiSounds(Context context) {
        this.context = context;
        keepOutputAwake = context.getResources().getBoolean(R.bool.keep_audio_awake);
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(ATTRS).build();
        nav = pool.load(context, R.raw.nav, 1);
        select = pool.load(context, R.raw.select, 1);
    }

    static final String PREF_KEY = "nav_sounds";

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_KEY, true);
    }

    void nav() {
        play(nav);
    }

    void select() {
        play(select);
    }

    private void play(int id) {
        // Launcher settings > Navigation sounds (on by default); the views' system clicks are switched off.
        if (!enabled(context)) return;
        pool.play(id, VOLUME, VOLUME, 1, 0, 1f);
    }

    /**
     * Home screen in front: start the near-silent stream, if enabled. Streamed by a thread that blocks in write()
     * between half-second chunks; a static track looping one chunk kept its AudioTrack thread busy at about 75% of
     * a core on a MediaTek Google TV.
     */
    void resume() {
        if (!keepOutputAwake || keepAwake != null) return;
        int frames = RATE / 2;   // half a second per write
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(ATTRS)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(frames * 2 * 2)
                    .build();
            track.play();
        } catch (RuntimeException e) {
            // Called from onResume: a failure here must never take the home screen down.
            if (track != null) track.release();
            return;
        }
        final AudioTrack playing = track;
        short[] hiss = new short[frames];
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < frames; i++) hiss[i] = (short) (random.nextInt(13) - 6);
        keepAwake = playing;
        keepAwakeWriter = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            try {
                // Blocks until the track has room; ends when pause() stops the track (write returns an error).
                while (keepAwake == playing && playing.write(hiss, 0, frames) > 0) {
                }
            } catch (RuntimeException ignored) {
                // The track was stopped underneath the write: just end.
            }
        }, "KeepAudioAwake");
        keepAwakeWriter.start();
    }

    /** Home screen left (an app opened or the TV sleeping): let the output go to standby again. */
    void pause() {
        AudioTrack track = keepAwake;
        if (track == null) return;
        keepAwake = null;
        try {
            track.pause();
            track.flush();
            track.stop();   // unblocks a write() in progress
        } catch (IllegalStateException ignored) {
        }
        // Release only once the writer is out of write(): releasing a track another thread is writing to is a
        // use-after-free in native code.
        Thread writer = keepAwakeWriter;
        keepAwakeWriter = null;
        if (writer != null) {
            try {
                writer.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        track.release();
    }

    void release() {
        pause();
        pool.release();
    }
}
