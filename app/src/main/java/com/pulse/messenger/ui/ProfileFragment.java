package com.pulse.messenger.ui;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.BuildConfig;
import com.pulse.messenger.R;
import com.pulse.messenger.auth.AuthActivity;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.data.PresenceManager;
import com.pulse.messenger.databinding.FragmentProfileBinding;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

/**
 * Profile tab — own profile and settings navigation.
 *
 * <p><b>Data flow:</b> observes
 * {@link FirestoreHelper#listenUser(String)} on the signed-in UID,
 * so any profile edit committed by {@code ProfileEditActivity}
 * propagates back to this header in real time (the user sees their
 * new name / bio / photo immediately upon returning from the edit
 * screen). The listener is bound to {@code getViewLifecycleOwner()},
 * so it detaches automatically when the fragment view is destroyed —
 * no manual cleanup required.</p>
 *
 * <p><b>What this fragment does NOT do (Phase 4 will add):</b></p>
 * <ul>
 *   <li>Full {@code SettingsActivity} implementation — clicking
 *       the "Settings" row currently opens a stub activity that will
 *       be built out in Phase 4 (theme, notification toggles,
 *       privacy preferences, cache management, about).</li>
 *   <li>Full {@code NotificationSettingsActivity} /
 *       {@code PrivacySettingsActivity} — those will be sections
 *       within the eventual SettingsActivity, not separate
 *       activities. The "Notifications" and "Privacy" rows in this
 *       fragment open {@code SettingsActivity} with an extra
 *       indicating which section to scroll to. This matches the
 *       standard Material settings pattern (one screen, multiple
 *       sections, deep-link to a specific section).</li>
 *   <li>Full {@code BlockedUsersActivity} — the "Blocked users" row
 *       is stubbed to open the same SettingsActivity with an
 *       {@code EXTRA_SECTION_BLOCKED} extra that routes to the
 *       blocked-users list once it exists. The current stub shows
 *       a "coming soon" Snackbar.</li>
 * </ul>
 *
 * <p><b>Log out flow (critical ordering):</b>
 * {@code PresenceManager.stopTracking(onComplete)} must run
 * <b>before</b> {@code FirebaseAuth.signOut()}. The presence
 * manager's Firestore mirror write requires an active
 * authenticated session — Firestore rules will reject it after
 * sign-out. The stopTracking callback is invoked on the main
 * thread after the local cleanup, so sign-out proceeds from
 * inside it.</p>
 */
public class ProfileFragment extends Fragment {

    private FragmentProfileBinding binding;
    private String myUid;
    private LiveData<User> userLiveData;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentProfileBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;   // should never happen; MainActivity guards this
        myUid = user.getUid();

        setupClickListeners();
        observeProfile();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (userLiveData != null) {
            userLiveData.removeObservers(getViewLifecycleOwner());
        }
        binding = null;
    }

    // ============================================================
    // Setup
    // ============================================================

    private void setupClickListeners() {
        binding.btnEditProfile.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), ProfileEditActivity.class);
            startActivity(intent);
        });

        binding.rowNotifications.setOnClickListener(v -> openSettingsSection(
                SettingsActivity.SECTION_NOTIFICATIONS));
        binding.rowPrivacy.setOnClickListener(v -> openSettingsSection(
                SettingsActivity.SECTION_PRIVACY));
        binding.rowBlocked.setOnClickListener(v -> openSettingsSection(
                SettingsActivity.SECTION_BLOCKED));
        binding.rowSettings.setOnClickListener(v -> openSettingsSection(
                SettingsActivity.SECTION_GENERAL));

        binding.btnLogout.setOnClickListener(v -> confirmLogout());
    }

    private void observeProfile() {
        userLiveData = FirestoreHelper.get().listenUser(myUid);
        userLiveData.observe(getViewLifecycleOwner(), user -> {
            if (binding == null || user == null) return;
            bindHeader(user);
        });
    }

    // ============================================================
    // Header binding
    // ============================================================

    private void bindHeader(@NonNull User user) {
        String label = user.getDisplayLabel();
        String initialSeed = user.getUid() != null ? user.getUid() : "?";
        String initialText = UiUtils.avatarInitial(label);
        int initialColor = UiUtils.resolveAvatarColor(requireContext(), initialSeed);

        binding.tvInitials.setText(initialText);
        ViewCompat.setBackgroundTintList(
                binding.tvInitials,
                ColorStateList.valueOf(initialColor));

        String photoUrl = user.getPhotoUrl();
        if (photoUrl != null && !photoUrl.isEmpty()) {
            binding.ivProfileAvatar.setVisibility(View.VISIBLE);
            Glide.with(binding.ivProfileAvatar)
                    .load(photoUrl)
                    .transform(new CircleCrop())
                    .into(binding.ivProfileAvatar);
        } else {
            binding.ivProfileAvatar.setVisibility(View.GONE);
        }

        binding.tvProfileName.setText(label);

        String username = user.getUsername();
        if (username != null && !username.trim().isEmpty()) {
            binding.tvProfileUsername.setText("@" + username);
            binding.tvProfileUsername.setVisibility(View.VISIBLE);
        } else {
            binding.tvProfileUsername.setVisibility(View.GONE);
        }

        String bio = user.getBio();
        if (bio != null && !bio.trim().isEmpty()) {
            binding.tvProfileBio.setText(bio);
            binding.tvProfileBio.setVisibility(View.VISIBLE);
        } else {
            binding.tvProfileBio.setVisibility(View.GONE);
        }
    }

    // ============================================================
    // Navigation
    // ============================================================

    private void openSettingsSection(@NonNull String section) {
        Intent intent = new Intent(requireContext(), SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_SECTION, section);
        startActivity(intent);
    }

    // ============================================================
    // Logout
    // ============================================================

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_logout_confirm_title)
                .setMessage(R.string.settings_logout_confirm_message)
                .setNegativeButton(R.string.dialog_cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.settings_logout, (d, w) -> doLogout())
                .show();
    }

    /**
     * Logout sequence — the ordering is critical:
     *
     * <ol>
     *   <li>{@code PresenceManager.stopTracking(...)} — writes
     *       "offline" to RTDB and mirrors it to Firestore. This
     *       MUST happen before sign-out because the Firestore
     *       mirror write needs an active authenticated session.</li>
     *   <li>On the stopTracking callback (main thread, after local
     *       cleanup), call {@code FirebaseAuth.signOut()}.</li>
     *   <li>Navigate to {@code AuthActivity} with
     *       {@code FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK}
     *       so the signed-out user cannot press Back and return to
     *       a stale MainActivity shell.</li>
     * </ol>
     */
    private void doLogout() {
        PresenceManager.get().stopTracking(() -> {
            FirebaseAuth.getInstance().signOut();
            Intent intent = new Intent(requireContext(), AuthActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        });
    }
}
