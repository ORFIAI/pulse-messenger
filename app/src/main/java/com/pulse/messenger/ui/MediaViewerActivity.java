package com.pulse.messenger.ui;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.snackbar.Snackbar;
import com.pulse.messenger.R;
import com.pulse.messenger.data.StorageHelper;
import com.pulse.messenger.databinding.ActivityMediaViewerBinding;
import com.pulse.messenger.databinding.ItemMediaViewerImageBinding;
import com.pulse.messenger.util.MediaUtils;
import com.pulse.messenger.util.UiUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen media viewer.
 *
 * <p><b>Input (via {@link Intent} extras):</b></p>
 * <ul>
 *   <li>{@link #EXTRA_URLS} — download URLs, in display order
 *       (Glide loads directly from these).</li>
 *   <li>{@link #EXTRA_STORAGE_PATHS} — Storage object paths for each
 *       media item, same order as URLs. Used by share/download to
 *       fetch the file via {@link StorageHelper#downloadToFile}.</li>
 *   <li>{@link #EXTRA_FILENAMES} — display/保存 names, same order.
 *       Used as the destination file name for share and download.</li>
 *   <li>{@link #EXTRA_MIME_TYPES} — MIME type per item, same order.
 *       Used for the share intent's {@code type} and for deriving
 *       the download file extension.</li>
 *   <li>{@link #EXTRA_INITIAL_INDEX} — zero-based index of the
 *       item to show first. Default 0.</li>
 * </ul>
 *
 * <p>All five lists must have the same length. If any is missing
 * or mismatched, the activity finishes immediately — a malformed
 * intent is not something the viewer can recover from, and there
 * is no sensible fallback state.</p>
 *
 * <p><b>Phase-7 scope: images only.</b> The adapter's view type is
 * always {@code ImageVH}. Adding video support means adding a
 * {@code VideoVH} and a {@code getItemViewType} branch on the MIME
 * type — the rest of the activity (pager, counter, toolbar, share,
 * download) is media-type-agnostic and works unchanged.</p>
 *
 * <p><b>Share flow:</b> download to {@code cache/exports/}, build a
 * {@link FileProvider} URI, fire {@code ACTION_SEND} with the file
 * MIME type. This shares the actual file, not the URL — recipients
 * get a real image they can open, view, and re-share.</p>
 *
 * <p><b>Download flow:</b> on API 29+, insert into
 * {@code MediaStore.Downloads} (no permission required). On API
 * 24–28, {@code MediaStore.Downloads} does not exist and
 * {@code WRITE_EXTERNAL_STORAGE} would be required — v1 shows a
 * clear Snackbar instead of implementing the legacy path. This is a
 * documented limitation, not a bug: the download feature works on
 * every Android version with {@code MediaStore.Downloads} support
 * (Android 10+), which covers the vast majority of active devices.</p>
 */
public class MediaViewerActivity extends AppCompatActivity {

    public static final String EXTRA_URLS          = "urls";
    public static final String EXTRA_STORAGE_PATHS = "storage_paths";
    public static final String EXTRA_FILENAMES     = "filenames";
    public static final String EXTRA_MIME_TYPES    = "mime_types";
    public static final String EXTRA_INITIAL_INDEX = "initial_index";

    private ActivityMediaViewerBinding binding;
    private ViewerAdapter adapter;

    private List<String> urls;
    private List<String> storagePaths;
    private List<String> filenames;
    private List<String> mimeTypes;
    private int currentIndex;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        urls          = intent.getStringArrayListExtra(EXTRA_URLS);
        storagePaths  = intent.getStringArrayListExtra(EXTRA_STORAGE_PATHS);
        filenames     = intent.getStringArrayListExtra(EXTRA_FILENAMES);
        mimeTypes     = intent.getStringArrayListExtra(EXTRA_MIME_TYPES);
        int initial   = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0);

        if (!isIntentValid(urls, storagePaths, filenames, mimeTypes)) {
            finish();
            return;
        }

        if (initial < 0 || initial >= urls.size()) initial = 0;
        currentIndex = initial;

        binding = ActivityMediaViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnClose.setOnClickListener(v -> finish());
        binding.btnShare.setOnClickListener(v -> onShareClicked());
        binding.btnDownload.setOnClickListener(v -> onDownloadClicked());

        adapter = new ViewerAdapter();
        binding.viewerPager.setAdapter(adapter);
        binding.viewerPager.setCurrentItem(initial, false);

        binding.viewerPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageSelected(int position) {
                currentIndex = position;
                updateCounter();
            }
        });

        updateCounter();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // ============================================================
    // Validation
    // ============================================================

    private static boolean isIntentValid(@Nullable List<String> urls,
                                          @Nullable List<String> storagePaths,
                                          @Nullable List<String> filenames,
                                          @Nullable List<String> mimeTypes) {
        if (urls == null || storagePaths == null || filenames == null || mimeTypes == null) return false;
        int n = urls.size();
        if (n == 0) return false;
        if (storagePaths.size() != n || filenames.size() != n || mimeTypes.size() != n) return false;
        for (String u : urls) {
            if (u == null || u.isEmpty()) return false;
        }
        return true;
    }

    // ============================================================
    // Counter
    // ============================================================

    private void updateCounter() {
        if (binding == null) return;
        if (urls.size() <= 1) {
            binding.tvViewerCounter.setVisibility(View.GONE);
        } else {
            binding.tvViewerCounter.setVisibility(View.VISIBLE);
            // Localized-friendly formatting is a Phase-8 refinement;
            // the format "N / M" is universally understood and does not
            // need a string resource for v1.
            binding.tvViewerCounter.setText(
                    (currentIndex + 1) + " / " + urls.size());
        }
    }

    // ============================================================
    // Share
    // ============================================================

    private void onShareClicked() {
        if (binding == null) return;
        final String storagePath = storagePaths.get(currentIndex);
        final String fileName    = filenames.get(currentIndex);
        final String mimeType    = mimeTypes.get(currentIndex);

        File cacheFile = StorageHelper.createExportFile(this, fileName);

        Snackbar.make(binding.rootViewer,
                R.string.media_downloading, Snackbar.LENGTH_SHORT).show();

        StorageHelper.get().downloadToFile(storagePath, cacheFile,
                new StorageHelper.ProgressCallback<File>() {
                    @Override public void onProgress(int percent) { }
                    @Override public void onSuccess(@NonNull File result) {
                        if (binding == null) return;
                        launchShareIntent(result, mimeType);
                    }
                    @Override public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        UiUtils.toast(binding.rootViewer,
                                getString(R.string.media_download_failed));
                    }
                });
    }

    private void launchShareIntent(@NonNull File file, @NonNull String mimeType) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mimeType);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(share,
                    getString(R.string.msg_action_forward));
            startActivity(chooser);
        } catch (Exception e) {
            if (binding == null) return;
            UiUtils.toast(binding.rootViewer,
                    getString(R.string.media_open_external_failed));
        }
    }

    // ============================================================
    // Download
    // ============================================================

    private void onDownloadClicked() {
        if (binding == null) return;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // MediaStore.Downloads was added in API 29. On API 24-28
            // saving to Downloads would require WRITE_EXTERNAL_STORAGE
            // and a runtime permission flow. That is deferred to
            // Phase 8; for v1 we surface a clear, honest message.
            UiUtils.toast(binding.rootViewer,
                    "Download requires Android 10 or later");
            return;
        }

        final String storagePath = storagePaths.get(currentIndex);
        final String fileName    = filenames.get(currentIndex);
        final String mimeType    = mimeTypes.get(currentIndex);

        File cacheFile = StorageHelper.createExportFile(this, fileName);

        Snackbar.make(binding.rootViewer,
                R.string.media_downloading, Snackbar.LENGTH_SHORT).show();

        StorageHelper.get().downloadToFile(storagePath, cacheFile,
                new StorageHelper.ProgressCallback<File>() {
                    @Override public void onProgress(int percent) { }
                    @Override public void onSuccess(@NonNull File result) {
                        if (binding == null) return;
                        saveToDownloads(result, fileName, mimeType);
                    }
                    @Override public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        UiUtils.toast(binding.rootViewer,
                                getString(R.string.media_save_failed));
                    }
                });
    }

    private void saveToDownloads(@NonNull File source, @NonNull String displayName,
                                  @NonNull String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS);

            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri item = getContentResolver().insert(collection, values);
            if (item == null) {
                if (binding == null) return;
                UiUtils.toast(binding.rootViewer,
                        getString(R.string.media_save_failed));
                return;
            }

            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(item)) {
                if (out == null) {
                    getContentResolver().delete(item, null, null);
                    if (binding == null) return;
                    UiUtils.toast(binding.rootViewer,
                            getString(R.string.media_save_failed));
                    return;
                }
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            }

            // Best-effort: delete the cache copy now that Downloads has
            // its own copy. Failure to delete is non-fatal (the cache
            // is periodically swept by the system).
            //noinspection ResultOfMethodCallIgnored
            source.delete();

            if (binding == null) return;
            UiUtils.toast(binding.rootViewer,
                    getString(R.string.media_save_success));
        } catch (Exception e) {
            if (binding == null) return;
            UiUtils.toast(binding.rootViewer,
                    getString(R.string.media_save_failed));
        }
    }

    // ============================================================
    // Pager adapter
    // ============================================================

    private final class ViewerAdapter extends RecyclerView.Adapter<ImageVH> {

        @NonNull
        @Override
        public ImageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ItemMediaViewerImageBinding b = ItemMediaViewerImageBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            return new ImageVH(b);
        }

        @Override
        public void onBindViewHolder(@NonNull ImageVH holder, int position) {
            bindImage(holder, position);
        }

        @Override
        public int getItemCount() {
            return urls.size();
        }

        void retry(int position, @NonNull ImageVH holder) {
            bindImage(holder, position);
        }
    }

    private void bindImage(@NonNull ImageVH holder, int position) {
        ItemMediaViewerImageBinding b = holder.binding;
        final String url = urls.get(position);

        // Reset state for rebind / retry.
        b.progressImage.setVisibility(View.VISIBLE);
        b.ivViewerImage.setVisibility(View.INVISIBLE);
        b.llImageError.setVisibility(View.GONE);

        // Tap the error block to retry.
        b.llImageError.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION || adapter == null) return;
            adapter.retry(pos, holder);
        });

        Glide.with(b.ivViewerImage)
                .load(url)
                .listener(new RequestListener<android.graphics.drawable.Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e,
                                                @Nullable Object model,
                                                @NonNull Target<android.graphics.drawable.Drawable> target,
                                                boolean isFirstResource) {
                        b.progressImage.setVisibility(View.GONE);
                        b.ivViewerImage.setVisibility(View.GONE);
                        b.llImageError.setVisibility(View.VISIBLE);
                        return true;    // consume, we handled it
                    }

                    @Override
                    public boolean onResourceReady(@NonNull android.graphics.drawable.Drawable resource,
                                                    @NonNull Object model,
                                                    @NonNull Target<android.graphics.drawable.Drawable> target,
                                                    @NonNull DataSource dataSource,
                                                    boolean isFirstResource) {
                        b.progressImage.setVisibility(View.GONE);
                        b.ivViewerImage.setVisibility(View.VISIBLE);
                        b.llImageError.setVisibility(View.GONE);
                        return false;   // let Glide set the image
                    }
                })
                .into(b.ivViewerImage);
    }

    static final class ImageVH extends RecyclerView.ViewHolder {
        final ItemMediaViewerImageBinding binding;

        ImageVH(@NonNull ItemMediaViewerImageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    // ============================================================
    // Intent-builder convenience (called by ChatActivity / MessageAdapter)
    // ============================================================

    /**
     * Builds an intent that opens the viewer at a single media item.
     * Used by {@code MessageAdapter} when the user taps an image
     * bubble. Wraps a single-item list so the viewer code path is
     * identical for both single- and multi-item launches.
     */
    @NonNull
    public static Intent singleItemIntent(@NonNull android.content.Context context,
                                           @NonNull String downloadUrl,
                                           @NonNull String storagePath,
                                           @NonNull String fileName,
                                           @NonNull String mimeType) {
        ArrayList<String> urls = new ArrayList<>(1);
        urls.add(downloadUrl);
        ArrayList<String> spaths = new ArrayList<>(1);
        spaths.add(storagePath);
        ArrayList<String> fnames = new ArrayList<>(1);
        fnames.add(fileName);
        ArrayList<String> mimes = new ArrayList<>(1);
        mimes.add(mimeType);

        Intent i = new Intent(context, MediaViewerActivity.class);
        i.putStringArrayListExtra(EXTRA_URLS, urls);
        i.putStringArrayListExtra(EXTRA_STORAGE_PATHS, spaths);
        i.putStringArrayListExtra(EXTRA_FILENAMES, fnames);
        i.putStringArrayListExtra(EXTRA_MIME_TYPES, mimes);
        i.putExtra(EXTRA_INITIAL_INDEX, 0);
        return i;
    }
}
