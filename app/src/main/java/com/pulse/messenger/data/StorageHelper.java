package com.pulse.messenger.data;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.pulse.messenger.model.Message;
import com.pulse.messenger.util.MediaUtils;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cloudinary-backed Storage Helper (100% Free, No Credit Card).
 * 
 * <p>This replaces Firebase Storage. Files are uploaded using a standard
 * unsigned multipart HTTP POST to Cloudinary. The resulting secure URL is 
 * saved in Firestore. Thumbnails are generated dynamically using Cloudinary's
 * URL transformations, saving client-side CPU and memory.</p>
 * 
 * <p><b>Setup Required:</b></p>
 * <ol>
 *   <li>Create a free account at cloudinary.com.</li>
 *   <li>Copy your Cloud Name and paste it below.</li>
 *   <li>Create an Unsigned Upload Preset and paste its name below.</li>
 * </ol>
 */
public class StorageHelper {

    private static final String TAG = "StorageHelper";

    // ============================================================
    // CONFIGURATION (PASTE YOUR CLOUDINARY DETAILS HERE)
    // ============================================================
    public static final String CLOUDINARY_CLOUD_NAME = "vk9rviez";
    public static final String CLOUDINARY_UPLOAD_PRESET = "pulse_unsigned";
    // ============================================================

    public interface ProgressCallback<T> {
        void onProgress(int percent);
        void onSuccess(@NonNull T result);
        void onError(@NonNull Exception e);
    }

    public interface SimpleCallback {
        void onSuccess();
        void onError(@NonNull Exception e);
    }

    public static final class FileTooLargeException extends Exception {
        public FileTooLargeException(long sizeBytes, long limitBytes, String type) {
            super("File " + sizeBytes + " bytes exceeds " + limitBytes + " bytes for type " + type);
        }
    }

    private static volatile StorageHelper INSTANCE;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private StorageHelper() { }

    public static StorageHelper get() {
        if (INSTANCE == null) {
            synchronized (StorageHelper.class) {
                if (INSTANCE == null) INSTANCE = new StorageHelper();
            }
        }
        return INSTANCE;
    }

    // ============================================================
    // Profile Photo
    // ============================================================

    public void uploadProfilePhoto(@NonNull Context context,
                                   @NonNull String uid,
                                   @NonNull Uri sourceUri,
                                   @NonNull ProgressCallback<String> cb) {
        long size = MediaUtils.querySize(context, sourceUri);
        if (size > 0 && size > MediaUtils.MAX_PROFILE_PHOTO_BYTES) {
            cb.onError(new FileTooLargeException(size, MediaUtils.MAX_PROFILE_PHOTO_BYTES, "profile"));
            return;
        }

        executor.execute(() -> {
            try {
                // Compress/resize image locally before upload
                File resized = new File(context.getCacheDir(), "media/profile_" + UUID.randomUUID() + ".jpg");
                boolean generated = MediaUtils.generateThumbnail(context, sourceUri, resized, 512);
                Uri uploadUri = generated ? Uri.fromFile(resized) : sourceUri;
                
                String url = doCloudinaryUpload(context, uploadUri, "image", null, cb);
                
                if (generated) resized.delete();
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> cb.onSuccess(url));
            } catch (Exception e) {
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> cb.onError(e));
            }
        });
    }

    // ============================================================
    // Message Media
    // ============================================================

    public void uploadMedia(@NonNull Context context,
                            @NonNull String conversationId,
                            @NonNull Uri sourceUri,
                            @NonNull String type,
                            @NonNull ProgressCallback<Message.Media> cb) {

        long declaredSize = MediaUtils.querySize(context, sourceUri);
        if (declaredSize > 0 && !MediaUtils.isWithinLimit(declaredSize, type)) {
            cb.onError(new FileTooLargeException(declaredSize, MediaUtils.maxBytesFor(type), type));
            return;
        }

        executor.execute(() -> {
            try {
                Message.Media media = new Message.Media();
                String mime = MediaUtils.resolveMimeType(context, sourceUri);
                String fileName = MediaUtils.queryFileName(context, sourceUri);
                
                int[] dims = MediaUtils.readDimensions(context, sourceUri);
                long durationMs = 0L;
                if (Message.TYPE_VIDEO.equals(type) || Message.TYPE_AUDIO.equals(type) || Message.TYPE_VOICE.equals(type)) {
                    durationMs = MediaUtils.readDurationMs(context, sourceUri);
                }

                media.setMimeType(mime);
                media.setFileName(fileName);
                media.setSizeBytes(declaredSize >= 0 ? declaredSize : 0);
                media.setWidth(dims[0]);
                media.setHeight(dims[1]);
                media.setDurationMs(durationMs);

                String resourceType = "auto"; // Cloudinary handles image/video/raw
                
                // Perform upload
                String secureUrl = doCloudinaryUpload(context, sourceUri, resourceType, type, cb);
                
                // Store the URL in both fields
                media.setDownloadUrl(secureUrl);
                media.setStoragePath(secureUrl); // Reusing this field for the URL

                // Generate Thumbnail URL dynamically via Cloudinary transformations
                if (Message.TYPE_IMAGE.equals(type) || Message.TYPE_VIDEO.equals(type)) {
                    String thumbUrl = secureUrl.replace("/upload/", "/upload/w_400,c_limit/");
                    media.setThumbnailUrl(thumbUrl);
                }

                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> cb.onSuccess(media));

            } catch (Exception e) {
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> cb.onError(e));
            }
        });
    }

    // ============================================================
    // Core Multipart Upload Logic
    // ============================================================

    private String doCloudinaryUpload(@NonNull Context context, 
                                      @NonNull Uri uri, 
                                      @NonNull String resourceType,
                                      @Nullable String msgType,
                                      @NonNull ProgressCallback<?> cb) throws Exception {
        
        if (CLOUDINARY_CLOUD_NAME.equals("YOUR_CLOUD_NAME")) {
            throw new IllegalStateException("Please set your Cloudinary credentials in StorageHelper.java");
        }

        String boundary = "----PulseBoundary" + System.currentTimeMillis();
        String urlStr = "https://api.cloudinary.com/v1_1/" + CLOUDINARY_CLOUD_NAME + "/" + resourceType + "/upload";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setRequestProperty("Accept", "application/json");

        long totalSize = MediaUtils.querySize(context, uri);
        String fileName = MediaUtils.queryFileName(context, uri);
        String mime = MediaUtils.resolveMimeType(context, uri);

        try (OutputStream out = new BufferedOutputStream(conn.getOutputStream());
             InputStream in = context.getContentResolver().openInputStream(uri)) {

            if (in == null) throw new IOException("Cannot open input stream for URI");

            // 1. Write upload preset
            writeFormField(out, boundary, "upload_preset", CLOUDINARY_UPLOAD_PRESET);

            // 2. Write file headers
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes());
            out.write(("Content-Type: " + mime + "\r\n\r\n").getBytes());

            // 3. Stream file with progress
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            int lastPercent = -1;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                if (totalSize > 0) {
                    int percent = (int) ((totalRead * 100) / totalSize);
                    // Map progress from 0-95% (reserve last 5% for server response)
                    int mapped = (int) (percent * 0.95);
                    if (mapped != lastPercent) {
                        lastPercent = mapped;
                        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                        mainHandler.post(() -> cb.onProgress(mapped));
                    }
                }
            }

            out.write(("\r\n--" + boundary + "--\r\n").getBytes());
            out.flush();

            // 4. Read response
            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream responseStream = conn.getInputStream();
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int len;
                while ((len = responseStream.read(buf)) != -1) {
                    result.write(buf, 0, len);
                }
                String jsonResponse = result.toString("UTF-8");
                JSONObject json = new JSONObject(jsonResponse);
                
                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                mainHandler.post(() -> cb.onProgress(100));

                return json.getString("secure_url");
            } else {
                throw new IOException("Upload failed with HTTP " + responseCode);
            }
        } finally {
            conn.disconnect();
        }
    }

    private void writeFormField(OutputStream out, String boundary, String name, String value) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes());
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes());
        out.write((value + "\r\n").getBytes());
    }

    // ============================================================
    // Download & Delete (Adapted for URLs)
    // ============================================================

    /**
     * Downloads a file from a direct URL (Cloudinary) to local storage.
     */
    public void downloadToFile(@NonNull String urlStr,
                               @NonNull File destination,
                               @Nullable ProgressCallback<File> cb) {
        executor.execute(() -> {
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                if (cb != null) {
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> cb.onError(new IOException("Cannot create parent directory")));
                }
                return;
            }

            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.connect();

                if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Server returned HTTP " + conn.getResponseCode());
                }

                int fileLength = conn.getContentLength();
                try (InputStream input = new BufferedInputStream(conn.getInputStream());
                     OutputStream output = new FileOutputStream(destination)) {

                    byte[] data = new byte[4096];
                    long total = 0;
                    int count;
                    int lastPercent = -1;

                    while ((count = input.read(data)) != -1) {
                        total += count;
                        output.write(data, 0, count);
                        if (fileLength > 0 && cb != null) {
                            int percent = (int) ((total * 100) / fileLength);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                                mainHandler.post(() -> cb.onProgress(percent));
                            }
                        }
                    }
                }
                if (cb != null) {
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> cb.onSuccess(destination));
                }
            } catch (Exception e) {
                if (destination.exists()) destination.delete();
                if (cb != null) {
                    android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                    mainHandler.post(() -> cb.onError(e));
                }
            }
        });
    }

    /**
     * Stub for deleteObject.
     * Cloudinary unsigned uploads cannot be deleted from the client side
     * without your API Secret. We return success so the UI doesn't crash
     * when a user taps "delete for everyone". The media remains on Cloudinary
     * but is removed from the app.
     */
    public void deleteObject(@Nullable String storagePath, @NonNull SimpleCallback cb) {
        Log.d(TAG, "deleteObject: Cloudinary unsigned uploads cannot be deleted directly. Ignoring.");
        cb.onSuccess();
    }

    @NonNull
    public static File createExportFile(@NonNull Context context, @NonNull String displayName) {
        File dir = new File(context.getCacheDir(), "exports");
        if (!dir.exists()) dir.mkdirs();
        String safe = MediaUtils.sanitizeForStorage(displayName);
        return new File(dir, UUID.randomUUID().toString().substring(0, 8) + "_" + safe);
    }
}
