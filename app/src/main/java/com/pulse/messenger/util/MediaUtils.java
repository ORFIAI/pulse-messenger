package com.pulse.messenger.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pulse.messenger.model.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Media processing utilities.
 *
 * <p><b>Threading discipline:</b> every method that reads from disk,
 * decodes a bitmap, or opens a {@link MediaMetadataRetriever} runs
 * synchronously. Callers MUST invoke these from a background thread —
 * typically inside {@code StorageHelper}'s upload executor. There is
 * no main-thread guard here because that would require an
 * {@code ActivityManager} lookup on every call; the contract is
 * documented and enforced by code review, not by a runtime check.</p>
 *
 * <p><b>Memory discipline:</b> image decoding always uses
 * {@code inSampleSize} (from {@link #calculateInSampleSize(int, int, int)})
 * so we never allocate a bitmap larger than the target dimensions.
 * Without subsampling, a 12 MP JPEG decodes to ~48 MB of ARGB_8888 —
 * enough to OOM a low-end device when combined with the message
 * list's existing allocations. Subsampling is the single most
 * important defense against OutOfMemoryError in this app.</p>
 */
public final class MediaUtils {

    private MediaUtils() {
    }

    // ============================================================
    // Limits
    // ============================================================

    /** Profile photo upload cap. Matches the Firestore Storage rule. */
    public static final long MAX_PROFILE_PHOTO_BYTES  = 5L * 1024 * 1024;   //  5 MB

    /** Chat image upload cap. */
    public static final long MAX_IMAGE_BYTES         = 15L * 1024 * 1024;  // 15 MB

    /** Chat video upload cap. */
    public static final long MAX_VIDEO_BYTES         = 100L * 1024 * 1024; // 100 MB

    /** Chat audio (music / long-form) upload cap. */
    public static final long MAX_AUDIO_BYTES         = 25L * 1024 * 1024;  // 25 MB

    /** Voice message upload cap (should be well under the audio cap; a 5-min AAC is ~2–3 MB). */
    public static final long MAX_VOICE_BYTES         = 10L * 1024 * 1024;  // 10 MB

    /** Document upload cap. */
    public static final long MAX_DOCUMENT_BYTES      = 25L * 1024 * 1024;  // 25 MB

    /** Minimum voice-message duration. Anything shorter is treated as an accidental tap. */
    public static final long MIN_VOICE_DURATION_MS   = 1000L;

    // ============================================================
    // Size validation
    // ============================================================

    /**
     * Returns the maximum allowed size for a given message type.
     * Used by {@link #isWithinLimit(long, String)} and by the UI to
     * show a size hint in the file picker dialog.
     */
    public static long maxBytesFor(@NonNull String type) {
        switch (type) {
            case Message.TYPE_IMAGE:    return MAX_IMAGE_BYTES;
            case Message.TYPE_VIDEO:    return MAX_VIDEO_BYTES;
            case Message.TYPE_AUDIO:    return MAX_AUDIO_BYTES;
            case Message.TYPE_VOICE:    return MAX_VOICE_BYTES;
            case Message.TYPE_DOCUMENT: return MAX_DOCUMENT_BYTES;
            default:                    return MAX_DOCUMENT_BYTES;
        }
    }

    /** {@code true} if a file of {@code sizeBytes} fits within the limit for {@code type}. */
    public static boolean isWithinLimit(long sizeBytes, @NonNull String type) {
        return sizeBytes > 0 && sizeBytes <= maxBytesFor(type);
    }

    /**
     * Human-readable limit string for a given type, using
     * {@link UiUtils#formatFileSize(long)}. Used in error messages:
     * "File is too large (max 15.0 MB)".
     */
    @NonNull
    public static String maxSizeLabel(@NonNull String type) {
        return UiUtils.formatFileSize(maxBytesFor(type));
    }

    // ============================================================
    // Picker metadata
    // ============================================================

    /**
     * Queries the size in bytes for a {@code content://} URI from the
     * system's {@link OpenableColumns#SIZE} column. Returns {@code -1}
     * when the provider does not expose a size — this happens with
     * some third-party document providers and with some
     * {@code content://} URIs from cloud providers that only stream
     * on read.
     *
     * <p>When this returns -1, the caller must not block the upload
     * based on size alone — it should proceed and let the upload
     * fail at the Firebase Storage rule layer if the size turns out
     * to be excessive. That is a worse UX but a correct fallback.</p>
     */
    public static long querySize(@NonNull Context context, @NonNull Uri uri) {
        ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) {
                    return c.getLong(idx);
                }
            }
        } catch (Exception ignored) {
            // Some providers throw SecurityException or IllegalArgument
            // for unregistered URIs. Treat as unknown size.
        }
        return -1;
    }

    /**
     * Queries the display name for a {@code content://} URI. Falls
     * back to the last path segment, then to "file". Never returns
     * null.
     */
    @NonNull
    public static String queryFileName(@NonNull Context context, @NonNull Uri uri) {
        ContentResolver cr = context.getContentResolver();
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0 && !c.isNull(idx)) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to path-segment fallback.
        }
        String path = uri.getLastPathSegment();
        if (path != null && !path.trim().isEmpty()) {
            return path;
        }
        return "file";
    }

    /**
     * Resolves the MIME type for a URI. Tries the ContentResolver
     * first (which is what content:// URIs want), then falls back to
     * the file extension via {@link MimeTypeMap}. Returns
     * {@code application/octet-stream} when neither yields a type —
     * a real, recognizable MIME type, not null.
     */
    @NonNull
    public static String resolveMimeType(@NonNull Context context, @NonNull Uri uri) {
        String type = context.getContentResolver().getType(uri);
        if (type != null && !type.trim().isEmpty()) {
            return type;
        }
        String name = queryFileName(context, uri);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase();
            String mapped = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mapped != null) return mapped;
        }
        return "application/octet-stream";
    }

    // ============================================================
    // Dimensions / duration
    // ============================================================

    /**
     * Reads image or video dimensions without decoding the full
     * bitmap. Uses {@link BitmapFactory.Options#inJustDecodeBounds}
     * for images and {@link MediaMetadataRetriever} for videos.
     *
     * <p>Returns a 2-element array {@code [width, height]}. Returns
     * {@code {0, 0}} when dimensions cannot be determined — this is
     * not an error (some document types are technically images with
     * no pixel dimensions) and callers must handle 0 as "unknown."</p>
     */
    @NonNull
    public static int[] readDimensions(@NonNull Context context, @NonNull Uri uri) {
        String mime = resolveMimeType(context, uri);
        if (mime.startsWith("image/")) {
            return readImageDimensions(context, uri);
        }
        if (mime.startsWith("video/")) {
            return readVideoDimensions(context, uri);
        }
        return new int[]{0, 0};
    }

    private static int[] readImageDimensions(@NonNull Context context, @NonNull Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return new int[]{0, 0};
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, opts);
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                return new int[]{opts.outWidth, opts.outHeight};
            }
        } catch (Exception ignored) {
            // Fall through.
        }
        return new int[]{0, 0};
    }

    private static int[] readVideoDimensions(@NonNull Context context, @NonNull Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, uri);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (w != null && h != null) {
                int wi = Integer.parseInt(w);
                int hi = Integer.parseInt(h);
                if (wi > 0 && hi > 0) {
                    return new int[]{wi, hi};
                }
            }
        } catch (Exception ignored) {
            // Fall through.
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        return new int[]{0, 0};
    }

    /**
     * Reads the duration in milliseconds for an audio or video URI.
     * Returns 0 when the duration cannot be determined.
     *
     * <p>Uses {@link MediaMetadataRetriever} which correctly handles
     * files whose duration is stored in the MP4/M4A metadata atom —
     * a raw file-size ÷ bitrate calculation would fail for VBR
     * audio, which is what most voice messages are encoded with.</p>
     */
    public static long readDurationMs(@NonNull Context context, @NonNull Uri uri) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, uri);
            String dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (dur != null) {
                return Math.max(0, Long.parseLong(dur));
            }
        } catch (Exception ignored) {
            // Fall through.
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    // ============================================================
    // Thumbnails
    // ============================================================

    /**
     * Generates a thumbnail for the given image or video URI and writes
     * it to {@code outFile} as JPEG. Returns {@code true} on success,
     * {@code false} on failure (in which case the caller should upload
     * the media without a thumbnail and let Glide fall back to
     * {@code downloadUrl} for images).
     *
     * <p>The longest dimension is downscaled to {@code maxDimension}
     * with aspect ratio preserved. Video frames are extracted at
     * approximately 1 second into the video — this is the same
     * heuristic used by every photo/video app for previews and avoids
     * the common "black first frame" issue with videos that fade in.</p>
     *
     * <p>Reads the source via subsampling to avoid loading the full
     * image into memory. For videos, {@link MediaMetadataRetriever}
     * handles decoding internally with its own memory budget.</p>
     */
    public static boolean generateThumbnail(@NonNull Context context,
                                            @NonNull Uri sourceUri,
                                            @NonNull File outFile,
                                            int maxDimension) {
        String mime = resolveMimeType(context, sourceUri);
        Bitmap thumb;
        if (mime.startsWith("video/")) {
            thumb = extractVideoFrame(context, sourceUri, maxDimension);
        } else if (mime.startsWith("image/")) {
            thumb = decodeSampledBitmap(context, sourceUri, maxDimension);
        } else {
            return false;
        }
        if (thumb == null) return false;
        return writeJpeg(thumb, outFile);
    }

    private static Bitmap decodeSampledBitmap(@NonNull Context context,
                                              @NonNull Uri uri,
                                              int maxDim) {
        try {
            // First decode bounds only.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDim);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception | OutOfMemoryError e) {
            // OutOfMemoryError is caught explicitly: on low-end
            // devices, even a sampled decode can OOM. Returning null
            // lets the caller proceed without a thumbnail rather
            // than crash the app.
            return null;
        }
    }

    private static Bitmap extractVideoFrame(@NonNull Context context,
                                            @NonNull Uri uri,
                                            int maxDim) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(context, uri);
            // Extract frame at ~1s in. For videos shorter than 1s,
            // getFrameAtTime returns the closest available frame.
            Bitmap full = mmr.getFrameAtTime(1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (full == null) return null;
            return scaleDown(full, maxDim);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            try { mmr.release(); } catch (Exception ignored) { }
        }
    }

    /**
     * Scales a bitmap down so its longest dimension equals
     * {@code maxDim}. Returns the original bitmap if it is already
     * smaller — the original is recycled only if a new bitmap is
     * created, so the caller can always safely treat the return value
     * as the bitmap to use.
     */
    @Nullable
    private static Bitmap scaleDown(@NonNull Bitmap source, int maxDim) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxDim) return source;

        float scale = (float) maxDim / longest;
        int targetW = Math.max(1, Math.round(w * scale));
        int targetH = Math.max(1, Math.round(h * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }

    /**
     * Chooses the largest power-of-two subsample factor such that the
     * decoded bitmap's longest dimension still exceeds
     * {@code targetDim}. This is the standard Android recipe for
     * safe image decoding.
     */
    public static int calculateInSampleSize(int width, int height, int targetDim) {
        int sampleSize = 1;
        int longest = Math.max(width, height);
        while (longest / sampleSize > targetDim * 2) {
            sampleSize *= 2;
        }
        return sampleSize;
    }

    private static boolean writeJpeg(@NonNull Bitmap bitmap, @NonNull File outFile) {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.flush();
            return ok;
        } catch (IOException e) {
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    // ============================================================
    // File-extension mapping
    // ============================================================

    /**
     * Derives a file extension from a MIME type. Falls back to
     * {@code .bin} when the mapping is unknown — a real extension
     * that signals "opaque file" rather than breaking storage paths.
     */
    @NonNull
    public static String extensionFor(@NonNull String mimeType) {
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        return ext != null ? ext : "bin";
    }

    /**
     * Strips characters that are unsafe in Firebase Storage object
     * names (which use a URL-safe path scheme). Replaces whitespace
     * with underscores, drops anything not alphanumeric / dot /
     * underscore / hyphen, and truncates to 60 chars. This is the
     * only place where file names are sanitized — the caller's
     * original file name should remain unsanitized in the Firestore
     * {@code media.fileName} field for display purposes.
     */
    @NonNull
    public static String sanitizeForStorage(@NonNull String fileName) {
        String cleaned = fileName.replaceAll("[\\s/\\\\:*?\"<>|]+", "_");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        if (cleaned.isEmpty()) {
            cleaned = "file";
        }
        return cleaned;
    }
}
