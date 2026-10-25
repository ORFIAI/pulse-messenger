package com.pulse.messenger.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Voice-message recorder.
 *
 * <p><b>Usage pattern (hold-to-record):</b></p>
 * <pre>
 *   VoiceRecorder recorder = new VoiceRecorder(context);
 *
 *   // On mic-button DOWN:
 *   try {
 *       recorder.start();
 *       showRecordingUi();
 *   } catch (SecurityException e) {
 *       requestRecordAudioPermission();
 *   } catch (Exception e) {
 *       showRecordingFailed();
 *   }
 *
 *   // On mic-button UP (or finger slides into "cancel" zone):
 *   if (cancelRequested) {
 *       recorder.cancel();
 *   } else {
 *       recorder.stop(new VoiceRecorder.Callback() {
 *           public void onStopped(Recording r) {
 *               StorageHelper.get().uploadMedia(context, cid,
 *                       Uri.fromFile(r.file), Message.TYPE_VOICE, uploadCb);
 *           }
 *           public void onError(Exception e) {
 *               showRecordingFailed();
 *           }
 *       });
 *   }
 *
 *   // From Activity.onDestroy():
 *   recorder.release();
 * </pre>
 *
 * <p><b>Threading:</b> {@link #start()} runs synchronously on the calling
 * thread (typically the main thread). {@code MediaRecorder}'s setup is
 * ~50–200 ms, which is fast enough to run on the main thread without an
 * ANR — and doing it synchronously means the caller knows immediately
 * whether recording actually started, before the user has begun speaking.
 * {@link #stop(Callback)} runs {@code MediaRecorder.stop()} and duration
 * extraction on a dedicated single-thread executor, because both operations
 * block for hundreds of milliseconds and would freeze the UI if run on the
 * main thread. Callbacks are always delivered on the main thread.</p>
 *
 * <p><b>RECORD_AUDIO permission:</b> the caller MUST have granted this
 * permission before calling {@link #start()}. {@code start()} throws
 * {@link SecurityException} with a clear message if the permission is
 * missing — this is a loud, actionable failure for a developer error, not
 * a silent "records nothing" bug. {@code ChatActivity} will handle the
 * runtime permission request flow (a Phase-8 UI concern) before invoking
 * this class.</p>
 *
 * <p><b>Lifecycle:</b> callers must call {@link #release()} from
 * {@code Activity.onDestroy()} (or {@code Fragment.onDestroyView()}).
 * {@link #release()} stops any in-flight recording, deletes the partial
 * file, and shuts down the executor. It is idempotent — safe to call
 * more than once.</p>
 */
public class VoiceRecorder {

    // ============================================================
    // Output shape
    // ============================================================

    /** The result of a successful stop: the recorded file and its duration. */
    public static final class Recording {
        public final File file;
        public final long durationMs;

        Recording(@NonNull File file, long durationMs) {
            this.file = file;
            this.durationMs = durationMs;
        }
    }

    /**
     * Stop callback. Either {@link #onStopped} or {@link #onError} fires,
     * never both. Both are delivered on the main thread.
     */
    public interface Callback {
        void onStopped(@NonNull Recording recording);
        void onError(@NonNull Exception e);
    }

    // ============================================================
    // State
    // ============================================================

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService stopExecutor = Executors.newSingleThreadExecutor();

    @Nullable private MediaRecorder recorder;
    @Nullable private File pendingFile;
    private long startTimeMs;
    private boolean recording;
    private volatile boolean released;

    public VoiceRecorder(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ============================================================
    // Public API
    // ============================================================

    /** {@code true} while a recording is in progress. */
    public boolean isRecording() {
        return recording;
    }

    /**
     * Starts recording. Runs on the calling thread; returns once recording
     * has actually begun (i.e., {@code MediaRecorder.start()} returned).
     *
     * @throws SecurityException    if {@code RECORD_AUDIO} permission is
     *                              not granted.
     * @throws IOException          if the cache directory cannot be created
     *                              or the output file cannot be opened.
     * @throws IllegalStateException if {@link #release()} was already called,
     *                              or a recording is already in progress.
     * @throws RuntimeException     if the underlying {@link MediaRecorder}
     *                              fails to configure / prepare / start
     *                              for any other reason (device-specific).
     */
    public void start() throws Exception {
        if (released) {
            throw new IllegalStateException("VoiceRecorder has been released");
        }
        if (recording) {
            throw new IllegalStateException("Already recording");
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("RECORD_AUDIO permission has not been granted");
        }

        File dir = new File(appContext.getCacheDir(), "voice");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create voice cache directory: " + dir.getAbsolutePath());
        }
        File file = new File(dir, System.currentTimeMillis() + ".m4a");

        MediaRecorder r = new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioSamplingRate(44100);
            r.setAudioEncodingBitRate(64000);
            r.setAudioChannels(1);
            r.setOutputFile(file.getAbsolutePath());
            r.prepare();
            r.start();
        } catch (Exception e) {
            try { r.release(); } catch (Exception ignored) {}
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            throw e;
        }

        this.recorder = r;
        this.pendingFile = file;
        this.startTimeMs = System.currentTimeMillis();
        this.recording = true;
    }

    /**
     * Stops recording and delivers a {@link Recording} (or an error) to
     * the callback. Safe to call when not recording — the callback simply
     * receives an error explaining that. Do NOT call both {@link #stop}
     * and {@link #cancel} for the same recording — {@code stop} means
     * "keep the file", {@code cancel} means "discard".
     */
    public void stop(@NonNull final Callback cb) {
        if (released) {
            cb.onError(new IllegalStateException("VoiceRecorder has been released"));
            return;
        }
        if (!recording) {
            cb.onError(new IllegalStateException("Not recording"));
            return;
        }

        final MediaRecorder r = this.recorder;
        final File file = this.pendingFile;
        final long elapsed = System.currentTimeMillis() - startTimeMs;

        this.recorder = null;
        this.pendingFile = null;
        this.recording = false;

        if (r == null || file == null) {
            cb.onError(new IllegalStateException("Recorder state was corrupted"));
            return;
        }

        stopExecutor.execute(() -> {
            Exception error = null;
            long actualDuration = elapsed;

            try {
                r.stop();
                // MediaRecorder.stop() returns only after the file is
                // finalized. Reading the duration now is safe. If it
                // somehow returns 0 (corrupt file), fall back to the
                // client-side elapsed time.
                long read = MediaUtils.readDurationMs(appContext, Uri.fromFile(file));
                if (read > 0) actualDuration = read;
            } catch (Exception e) {
                // The most common cause is a recording shorter than the
                // encoder's minimum (~1s). The file exists but is
                // unusable — we delete it below.
                error = e;
            } finally {
                try { r.reset(); } catch (Exception ignored) {}
                try { r.release(); } catch (Exception ignored) {}
            }

            final long finalDuration = actualDuration;
            if (error != null || finalDuration < MediaUtils.MIN_VOICE_DURATION_MS) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                final Exception finalError = error != null
                        ? error
                        : new IllegalStateException(
                                "Recording too short (" + finalDuration + "ms)");
                mainHandler.post(() -> {
                    if (released) return;
                    cb.onError(finalError);
                });
                return;
            }

            final Recording result = new Recording(file, finalDuration);
            mainHandler.post(() -> {
                if (released) return;
                cb.onStopped(result);
            });
        });
    }

    /**
     * Discards the current recording: stops the {@link MediaRecorder},
     * deletes the partial file, and returns to idle. No callback fires.
     * Idempotent — safe to call when not recording.
     */
    public void cancel() {
        if (released) return;

        final MediaRecorder r = this.recorder;
        final File file = this.pendingFile;
        this.recorder = null;
        this.pendingFile = null;
        this.recording = false;

        if (r == null && file == null) {
            return;
        }

        stopExecutor.execute(() -> {
            if (r != null) {
                try { r.stop(); } catch (Exception ignored) {}
                try { r.reset(); } catch (Exception ignored) {}
                try { r.release(); } catch (Exception ignored) {}
            }
            if (file != null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        });
    }

    /**
     * Releases all native resources and shuts down the executor. Must be
     * called from {@code Activity.onDestroy()} (or
     * {@code Fragment.onDestroyView()}). Idempotent — safe to call more
     * than once. Any in-flight recording is cancelled and its partial file
     * deleted. Any pending stop callback will be suppressed (the executor
     * is shut down gracefully so the cancel task still completes).
     */
    public void release() {
        if (released) return;
        released = true;
        cancel();               // enqueue a cancel task for any active recorder
        stopExecutor.shutdown();// allow the queued cancel task to complete
    }
}
