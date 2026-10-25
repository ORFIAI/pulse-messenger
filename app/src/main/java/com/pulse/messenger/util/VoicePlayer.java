package com.pulse.messenger.util;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Voice/audio message playback utility.
 *
 * <p><b>Design: single-instance, one-at-a-time.</b> Only one voice
 * message can play at any moment across the entire app. Calling
 * {@link #play(Context, Uri, String, Callback)} while another message
 * is playing stops the previous playback and starts the new one — the
 * exact behavior users expect from every messaging app. This is why
 * the class is a singleton: multiple instances would allow parallel
 * playback, which is both confusing and undesirable (users do not want
 * two voice messages overlapping).</p>
 *
 * <p><b>Threading:</b> all public methods must be called on the main
 * thread. All callbacks are delivered on the main thread (via a
 * {@link Handler} bound to {@link Looper#getMainLooper()}). The class
 * never blocks: {@link MediaPlayer#prepareAsync()} is used instead of
 * the synchronous {@code prepare()}, so a slow network-backed source
 * (e.g., a future streamed playback) does not stall the UI.</p>
 *
 * <p><b>Lifecycle:</b> the singleton persists across activity
 * transitions — deliberately. If the user starts a voice message
 * playing and rotates the device (activity destroyed and recreated),
 * playback continues. Callers must pause playback in their
 * {@code onStop()} if they want it to stop when the screen is no longer
 * visible — Phase-9 will add a {@code ProcessLifecycleOwner} observer
 * inside this class to do that automatically. For v1, the current
 * {@code MessageAdapter} binding does not observe lifecycle; the
 * {@code ChatActivity} wiring will pause on {@code onStop} explicitly
 * until the process-lifecycle observer is added.</p>
 *
 * <p><b>Playback position tracking:</b> the class holds a {@link Handler}
 * that fires every {@link #PROGRESS_INTERVAL_MS} to deliver
 * {@link Callback#onProgress(long, long)} events. The handler is
 * started on {@code onPrepared} and cancelled in every terminal state
 * (pause, stop, completion, error). This produces smooth progress bar
 * updates without polling {@code MediaPlayer.getCurrentPosition()}
 * from a background thread.</p>
 *
 * <p><b>Error handling:</b> the underlying {@link MediaPlayer} can
 * fail on:</p>
 * <ul>
 *   <li>Missing file (deleted cache, network error during download).</li>
 *   <li>Corrupt or unsupported audio format.</li>
 *   <li>Device audio focus being taken by another app (phone call,
 *       another media player).</li>
 * </ul>
 * All three surface as {@link Callback#onError(Exception)}. The class
 * does not retry — retry is a UI decision (the caller may show a
 * "download" button or a "tap to retry" affordance).</p>
 */
public final class VoicePlayer {

    private static final String TAG = "VoicePlayer";

    /** How often {@link Callback#onProgress} fires while playing. */
    private static final long PROGRESS_INTERVAL_MS = 100L;

    // ============================================================
    // Callback
    // ============================================================

    /**
     * Playback callback. Callers pass one instance per {@link #play}
     * invocation. The same instance receives every event for that
     * playback session — a new {@code play()} call should use a new
     * callback instance (or a callback whose {@code messageId} field
     * identifies the current session).
     *
     * <p>{@code messageId} on the callback (see {@link #play}) lets
     * the caller correlate the callback to the specific message being
     * played. The caller is expected to guard against stale callbacks
     * — e.g., "a progress event arrived for a message the adapter has
     * already rebound to a different row" — by comparing
     * {@code messageId} to the current row's message ID. The class
     * does not do this filtering itself; callbacks for the current
     * playback session are delivered as-is.</p>
     */
    public interface Callback {
        /** Called when playback has started (first frame of audio). */
        void onStarted();

        /** Called periodically while playing. Positions are in ms. */
        void onProgress(long positionMs, long durationMs);

        /** Called when the user paused via {@link #pause()}. */
        void onPaused();

        /** Called when the user stopped via {@link #stop()}. */
        void onStopped();

        /** Called when playback completed normally (reached the end). */
        void onCompleted();

        /** Called on any playback error. The player is returned to idle. */
        void onError(@NonNull Exception e);
    }

    // ============================================================
    // Singleton
    // ============================================================

    private static volatile VoicePlayer INSTANCE;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** The active MediaPlayer, or null when idle. */
    @Nullable private MediaPlayer player;

    /** Identifier of the currently playing message (for callback correlation). */
    @Nullable private String currentMessageId;
    @Nullable private Uri currentUri;
    @Nullable private Callback currentCallback;

    /** Duration of the currently loaded media (available after onPrepared). */
    private long durationMs = 0L;

    /** True between {@link #pause()} and {@link #resume()} (or {@link #play} with a different URI). */
    private boolean paused = false;

    /** Runnable posted on {@link #mainHandler} while playback is active. */
    @Nullable private Runnable progressRunnable;

    private VoicePlayer() { }

    @NonNull
    public static VoicePlayer get() {
        if (INSTANCE == null) {
            synchronized (VoicePlayer.class) {
                if (INSTANCE == null) INSTANCE = new VoicePlayer();
            }
        }
        return INSTANCE;
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Starts playback of {@code uri}. If another message is already
     * playing, it is stopped (without firing its callback's
     * {@code onStopped} — the stop is implicit). If the same URI is
     * currently paused, playback resumes from the paused position.
     *
     * @param context      used to open the file; the application
     *                     context is safe (the class does not retain it).
     * @param uri          the audio source. Typically a local file
     *                     URI ({@code file://...}) pointing at a
     *                     downloaded cache file, or a remote HTTPS
     *                     URL for future streamed playback.
     * @param messageId    an identifier that the caller's callback can
     *                     use to correlate events to its own state.
     *                     Pass any non-null string; the class does not
     *                     interpret it.
     * @param cb           event listener. Must not be null.
     */
    public void play(@NonNull Context context,
                     @NonNull Uri uri,
                     @NonNull String messageId,
                     @NonNull Callback cb) {
        // If the same URI is paused, resume it.
        if (paused && currentUri != null && currentUri.equals(uri) && player != null) {
            resumeInternal(cb);
            return;
        }

        // Otherwise, stop whatever is currently playing and start fresh.
        stopInternal(false /* no callback */);

        this.currentCallback = cb;
        this.currentMessageId = messageId;
        this.currentUri = uri;
        this.paused = false;
        this.durationMs = 0L;

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(context, uri);
            mp.setOnPreparedListener(prepared -> {
                // Race guard: if the player was replaced or stopped
                // between prepareAsync() and this callback, discard.
                if (this.player != prepared) {
                    try { prepared.release(); } catch (Exception ignored) {}
                    return;
                }
                try { prepared.start(); } catch (Exception e) {
                    onError(e);
                    return;
                }
                this.durationMs = prepared.getDuration();
                startProgressLoop();
                if (cb != null) cb.onStarted();
                // Emit an initial progress event so the UI gets the
                // duration immediately (without waiting 100ms).
                if (cb != null) cb.onProgress(0L, this.durationMs);
            });
            mp.setOnCompletionListener(completed -> {
                if (this.player != completed) return;
                stopProgressLoop();
                Callback active = this.currentCallback;
                stopInternal(false);
                if (active != null) active.onCompleted();
            });
            mp.setOnErrorListener((errored, what, extra) -> {
                if (this.player != errored) return false; // let MP handle its own release
                onError(new IllegalStateException(
                        "MediaPlayer error (what=" + what + ", extra=" + extra + ")"));
                return true;    // we handled the error
            });

            this.player = mp;
            mp.prepareAsync();
        } catch (Exception e) {
            onError(e);
        }
    }

    /**
     * Pauses playback. Fires {@link Callback#onPaused} on the current
     * callback. Safe to call when idle (no-op).
     */
    public void pause() {
        if (player == null || paused) return;
        try {
            player.pause();
        } catch (Exception ignored) {
            // MediaPlayer in an inconsistent state — fall through to
            // the state-flag flip, so the caller's UI at least gets
            // the onPaused event.
        }
        paused = true;
        stopProgressLoop();
        if (currentCallback != null) currentCallback.onPaused();
    }

    /**
     * Resumes from a paused state. Safe to call when idle (no-op) or
     * when actively playing (no-op).
     */
    public void resume() {
        if (player == null || !paused) return;
        resumeInternal(currentCallback);
    }

    /**
     * Stops playback and releases the {@link MediaPlayer}. Fires
     * {@link Callback#onStopped} on the current callback (unless
     * silent). Idempotent — safe to call when idle.
     */
    public void stop() {
        stopInternal(true /* fire callback */);
    }

    /**
     * Seeks to {@code positionMs} within the currently loaded media.
     * Safe to call when idle (no-op). The next progress event reflects
     * the new position — the caller does not need to poll.
     */
    public void seekTo(long positionMs) {
        if (player == null) return;
        try {
            player.seekTo((int) Math.max(0, Math.min(positionMs, durationMs)));
        } catch (Exception ignored) {
            // Some MediaPlayer implementations throw on seek when the
            // media is not prepared yet. Swallow — the next progress
            // tick will reflect whatever position the player is at.
        }
    }

    /** {@code true} if a player is loaded and actively playing (not paused). */
    public boolean isPlaying() {
        return player != null && !paused;
    }

    /**
     * The messageId of the currently playing (or paused) item, or
     * null when idle. Useful for the adapter to highlight the active
     * row.
     */
    @Nullable
    public String getCurrentMessageId() {
        return currentMessageId;
    }

    // ============================================================
    // Internal
    // ============================================================

    private void resumeInternal(@Nullable Callback cb) {
        if (player == null) return;
        try {
            player.start();
        } catch (Exception e) {
            onError(e);
            return;
        }
        paused = false;
        startProgressLoop();
        if (cb != null) cb.onProgress(player.getCurrentPosition(), durationMs);
    }

    private void stopInternal(boolean fireCallback) {
        stopProgressLoop();

        Callback cb = currentCallback;
        MediaPlayer mp = player;

        // Clear state before releasing to prevent re-entrant callbacks
        // (completion listener firing during release, etc.).
        this.player = null;
        this.currentCallback = null;
        this.currentUri = null;
        this.currentMessageId = null;
        this.durationMs = 0L;
        this.paused = false;

        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) {}
            try { mp.reset(); } catch (Exception ignored) {}
            try { mp.release(); } catch (Exception ignored) {}
        }

        if (fireCallback && cb != null) cb.onStopped();
    }

    private void onError(@NonNull Exception e) {
        Log.w(TAG, "Playback error: " + e.getClass().getSimpleName());
        Callback cb = currentCallback;
        stopInternal(false);
        if (cb != null) cb.onError(e);
    }

    private void startProgressLoop() {
        stopProgressLoop();
        progressRunnable = new Runnable() {
            @Override public void run() {
                if (player == null || paused) return;
                long pos;
                try { pos = player.getCurrentPosition(); }
                catch (Exception e) { return; }
                Callback cb = currentCallback;
                if (cb != null) cb.onProgress(pos, durationMs);
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        };
        mainHandler.post(progressRunnable);
    }

    private void stopProgressLoop() {
        if (progressRunnable != null) {
            mainHandler.removeCallbacks(progressRunnable);
            progressRunnable = null;
        }
    }
}
