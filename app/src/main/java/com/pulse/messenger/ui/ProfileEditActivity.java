package com.pulse.messenger.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.R;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.data.StorageHelper;
import com.pulse.messenger.databinding.ActivityProfileEditBinding;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.MediaUtils;
import com.pulse.messenger.util.UiUtils;

import java.util.regex.Pattern;

/**
 * Profile edit / first-time setup activity.
 *
 * <p><b>Two modes:</b> first-time setup (username empty) shows
 * "Set up your profile" and hides the back arrow; normal edit shows
 * "Edit profile" with the back arrow. First-time setup requires a
 * valid username before Save is enabled.</p>
 *
 * <p><b>Save semantics:</b> display name + bio are saved via
 * {@link FirestoreHelper#updateProfile}. Username is saved separately
 * via {@link FirestoreHelper#reserveUsername} — and only when it has
 * actually changed from the loaded value, to avoid an unnecessary
 * transaction (and its potential "username taken" error) for edits
 * that do not touch the username.</p>
 *
 * <p><b>Photo upload</b> is independent of the Save button: picking a
 * new photo triggers an immediate upload + Firestore write, then
 * renders the new photo. This matches every modern profile-editing
 * UX.</p>
 */
public class ProfileEditActivity extends AppCompatActivity {

    /**
     * Username format: 3–20 chars, lowercase letters/digits/underscores,
     * must start with a letter. Enforced locally for immediate feedback;
     * the unique constraint is enforced server-side by
     * {@link FirestoreHelper#reserveUsername}.
     */
    private static final Pattern USERNAME_PATTERN =
            Pattern.compile("^[a-z][a-z0-9_]{2,19}$");

    private static final int MAX_DISPLAY_NAME_LENGTH = 40;
    private static final int MAX_BIO_LENGTH = 200;

    private ActivityProfileEditBinding binding;
    private String myUid;

    @Nullable private User loadedUser;
    private boolean firstTimeSetup;

    /**
     * TextWatcher for the username field — re-runs
     * {@link #updateSaveButtonState()} on every keystroke so the Save
     * button reflects the current validity of the typed username.
     * Without this, the button would only update from the initial
     * {@code bindUser} render, which never sees the user's typing in
     * first-time-setup mode.
     */
    private final TextWatcher usernameWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
        @Override public void afterTextChanged(Editable s) {
            if (firstTimeSetup) updateSaveButtonState();
        }
    };

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null) onPhotoPicked(uri);
                    });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser == null) {
            finish();
            return;
        }
        myUid = fbUser.getUid();

        binding = ActivityProfileEditBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarProfileEdit.setNavigationOnClickListener(v -> finish());
        binding.btnChangePhoto.setOnClickListener(v -> photoPicker.launch(
                new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build()));
        binding.btnSave.setOnClickListener(v -> onSaveClicked());

        // Wire the username TextWatcher once — it lives for the activity's
        // lifetime and reads/writes the current binding safely (guards on
        // the `firstTimeSetup` flag which is set during load).
        binding.etUsername.addTextChangedListener(usernameWatcher);

        loadProfile();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (binding != null) {
            binding.etUsername.removeTextChangedListener(usernameWatcher);
        }
        binding = null;
    }

    // ============================================================
    // Load
    // ============================================================

    private void loadProfile() {
        showLoading(true);
        FirestoreHelper.get().getUser(myUid, new FirestoreHelper.Callback<User>() {
            @Override
            public void onSuccess(@Nullable User result) {
                if (binding == null) return;
                showLoading(false);
                if (result == null) {
                    UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
                    finish();
                    return;
                }
                loadedUser = result;
                firstTimeSetup = result.getUsername() == null
                        || result.getUsername().trim().isEmpty();
                bindUser(result);
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (binding == null) return;
                showLoading(false);
                UiUtils.toast(binding.getRoot(), getString(R.string.error_load_failed));
                finish();
            }
        });
    }

    private void bindUser(@NonNull User user) {
        binding.toolbarProfileEdit.setTitle(firstTimeSetup
                ? R.string.profile_setup_title
                : R.string.profile_edit_title);
        setToolbarBackArrowVisible(!firstTimeSetup);

        binding.etDisplayName.setText(user.getDisplayName());
        binding.etUsername.setText(user.getUsername());
        binding.etBio.setText(user.getBio());

        if (firstTimeSetup) {
            updateSaveButtonState();
        } else {
            binding.btnSave.setEnabled(true);
        }

        renderAvatar(user);
    }

    /**
     * Sets or hides the toolbar's back arrow. Passing a valid drawable
     * resource sets it; passing {@code 0} removes it. This replaces the
     * non-existent {@code setNavigationIconVisible(...)} shortcut.
     */
    private void setToolbarBackArrowVisible(boolean visible) {
        if (visible) {
            binding.toolbarProfileEdit.setNavigationIcon(R.drawable.ic_arrow_back);
        } else {
            binding.toolbarProfileEdit.setNavigationIcon((android.graphics.drawable.Drawable) null);
        }
    }

    private void renderAvatar(@NonNull User user) {
        String label = user.getDisplayLabel();
        String initialSeed = user.getUid() != null ? user.getUid() : "?";
        String initialText = UiUtils.avatarInitial(label);
        int initialColor = UiUtils.resolveAvatarColor(this, initialSeed);

        binding.tvInitials.setText(initialText);
        ViewCompat.setBackgroundTintList(
                binding.tvInitials,
                ColorStateList.valueOf(initialColor));

        String photoUrl = user.getPhotoUrl();
        if (photoUrl != null && !photoUrl.isEmpty()) {
            binding.ivAvatar.setVisibility(View.VISIBLE);
            Glide.with(binding.ivAvatar)
                    .load(photoUrl)
                    .transform(new CircleCrop())
                    .into(binding.ivAvatar);
        } else {
            binding.ivAvatar.setVisibility(View.GONE);
        }
    }

    // ============================================================
    // Photo upload
    // ============================================================

    private void onPhotoPicked(@NonNull Uri uri) {
        if (binding == null) return;
        long size = MediaUtils.querySize(this, uri);
        if (size > 0 && size > MediaUtils.MAX_PROFILE_PHOTO_BYTES) {
            UiUtils.toast(binding.getRoot(),
                    getString(R.string.error_file_too_large,
                            UiUtils.formatFileSize(MediaUtils.MAX_PROFILE_PHOTO_BYTES)));
            return;
        }

        showPhotoUploading(true);

        StorageHelper.get().uploadProfilePhoto(this, myUid, uri,
                new StorageHelper.ProgressCallback<String>() {
                    @Override
                    public void onProgress(int percent) {
                        if (binding == null) return;
                        binding.progressPhoto.setProgressCompat(percent, true);
                    }

                    @Override
                    public void onSuccess(@NonNull String downloadUrl) {
                        if (binding == null) return;
                        persistPhotoUrl(downloadUrl);
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        showPhotoUploading(false);
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.profile_photo_upload_failed));
                    }
                });
    }

    private void persistPhotoUrl(@NonNull String downloadUrl) {
        FirestoreHelper.get().updateProfile(myUid, null, null, downloadUrl,
                new FirestoreHelper.Callback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        if (binding == null) return;
                        showPhotoUploading(false);
                        if (loadedUser != null) loadedUser.setPhotoUrl(downloadUrl);
                        Glide.with(binding.ivAvatar)
                                .load(downloadUrl)
                                .transform(new CircleCrop())
                                .into(binding.ivAvatar);
                        binding.ivAvatar.setVisibility(View.VISIBLE);
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.profile_photo_uploaded));
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        showPhotoUploading(false);
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.profile_save_failed));
                    }
                });
    }

    private void showPhotoUploading(boolean uploading) {
        binding.progressPhoto.setVisibility(uploading ? View.VISIBLE : View.GONE);
        binding.btnChangePhoto.setEnabled(!uploading);
    }

    // ============================================================
    // Save
    // ============================================================

    private void onSaveClicked() {
        String displayName = text(binding.etDisplayName).trim();
        String usernameRaw = text(binding.etUsername).trim().toLowerCase();
        String bio = text(binding.etBio).trim();

        if (displayName.isEmpty()) {
            binding.etDisplayName.setError(getString(R.string.profile_display_name_required));
            binding.etDisplayName.requestFocus();
            return;
        }
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            binding.etDisplayName.setError(getString(R.string.profile_display_name_too_long));
            binding.etDisplayName.requestFocus();
            return;
        }
        if (usernameRaw.isEmpty()) {
            binding.etUsername.setError(getString(R.string.profile_username_too_short));
            binding.etUsername.requestFocus();
            return;
        }
        if (!USERNAME_PATTERN.matcher(usernameRaw).matches()) {
            binding.etUsername.setError(getString(R.string.profile_username_invalid));
            binding.etUsername.requestFocus();
            return;
        }
        if (bio.length() > MAX_BIO_LENGTH) {
            binding.etBio.setError(getString(R.string.profile_bio_too_long));
            binding.etBio.requestFocus();
            return;
        }
        binding.etDisplayName.setError(null);
        binding.etUsername.setError(null);
        binding.etBio.setError(null);

        showSaving(true);

        String existingUsername = loadedUser != null && loadedUser.getUsername() != null
                ? loadedUser.getUsername().toLowerCase()
                : "";

        if (!firstTimeSetup && usernameRaw.equals(existingUsername)) {
            saveOtherFields(displayName, bio);
        } else {
            FirestoreHelper.get().reserveUsername(usernameRaw, myUid,
                    new FirestoreHelper.Callback<Void>() {
                        @Override
                        public void onSuccess(@Nullable Void result) {
                            if (binding == null) return;
                            saveOtherFields(displayName, bio);
                        }

                        @Override
                        public void onError(@NonNull Exception e) {
                            if (binding == null) return;
                            showSaving(false);
                            if (e instanceof FirestoreHelper.UsernameTakenException) {
                                binding.etUsername.setError(
                                        getString(R.string.profile_username_taken));
                                binding.etUsername.requestFocus();
                            } else {
                                UiUtils.toast(binding.getRoot(),
                                        getString(R.string.profile_save_failed));
                            }
                        }
                    });
        }
    }

    private void saveOtherFields(@NonNull String displayName, @NonNull String bio) {
        FirestoreHelper.get().updateProfile(myUid, displayName, bio, null,
                new FirestoreHelper.Callback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        if (binding == null) return;
                        showSaving(false);
                        UiUtils.toast(binding.getRoot(), getString(R.string.profile_saved));
                        if (firstTimeSetup) {
                            Intent intent = new Intent(ProfileEditActivity.this,
                                    MainActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                        }
                        finish();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        showSaving(false);
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.profile_save_failed));
                    }
                });
    }

    // ============================================================
    // UI state
    // ============================================================

    private void showLoading(boolean loading) {
        binding.progressLoad.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.scrollContent.setVisibility(loading ? View.GONE : View.VISIBLE);
    }

    private void showSaving(boolean saving) {
        binding.btnSave.setEnabled(!saving);
        binding.progressSave.setVisibility(saving ? View.VISIBLE : View.GONE);
    }

    /**
     * Enables the Save button in first-time setup only when the username
     * field is locally valid. Called on every keystroke via
     * {@link #usernameWatcher}, and again after
     * {@link #bindUser} on initial render.
     */
    private void updateSaveButtonState() {
        String usernameRaw = text(binding.etUsername).trim().toLowerCase();
        boolean valid = !usernameRaw.isEmpty()
                && USERNAME_PATTERN.matcher(usernameRaw).matches();
        binding.btnSave.setEnabled(valid);
    }

    @NonNull
    private String text(@NonNull EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }
}
