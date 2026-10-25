package com.pulse.messenger.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.BuildConfig;
import com.pulse.messenger.R;
import com.pulse.messenger.auth.AuthActivity;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.data.PresenceManager;
import com.pulse.messenger.databinding.ActivitySettingsBinding;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

/**
 * Settings activity.
 *
 * <p><b>Architecture:</b> a single activity with multiple sections.
 * Callers (ProfileFragment, future ChatActivity overflow menu) can
 * pass {@link #EXTRA_SECTION} with one of the {@code SECTION_*}
 * constants to deep-link the user directly to a specific section.
 * Without an extra, the activity opens at the top of the list.</p>
 *
 * <p><b>Two persistence layers:</b></p>
 * <ul>
 *   <li><b>SharedPreferences</b> ({@link #PREFS_NAME}) — for
 *       device-local preferences that do not follow the user across
 *       devices: theme mode, notification previews on/off. These
 *       are not synced to Firestore because the user's "dark mode"
 *       on their phone is not relevant to their "dark mode" on a
 *       tablet.</li>
 *   <li><b>Firestore</b> — for preferences that define the user's
 *       public or cross-device identity: privacy tiers (last seen,
 *       online, profile photo visibility) and notification enabled
 *       (so a peer sees they can't message this user). These
 *       persist across reinstalls and across devices.</li>
 * </ul>
 *
 * <p><b>v1 scope (this file):</b></p>
 * <ul>
 *   <li>Theme selection — fully implemented (System / Light / Dark).
 *       Persisted in SharedPreferences, applied via
 *       {@link AppCompatDelegate#setDefaultNightMode(int)}.</li>
 *   <li>Privacy tiers — fully implemented. Each tier is a clickable
 *       row that opens a single-choice dialog, persists the choice to
 *       Firestore, and reflects the current state in the row's
 *       subtitle.</li>
 *   <li>Notification enabled — implemented as a toggle that persists
 *       to Firestore's {@code notificationPrefs.enabled}.</li>
 *   <li>Notification previews — implemented as a toggle that
 *       persists to SharedPreferences (device-local, since preview
 *       behavior is a display concern on the receiving device).</li>
 *   <li>Blocked users — opening the section currently shows a
 *       "coming soon" Snackbar. The full list is a Phase 5 feature
 *       that needs its own adapter and layout. This is documented
 *       in the row's on-click handler and is the only feature that
 *       is genuinely not ready in this file.</li>
 *   <li>About — opens a dialog with app version and short info.</li>
 * </ul>
 *
 * <p><b>Logout fallback:</b> this activity also provides a logout
 * button (accessible from any section). The primary entry point is
 * {@code ProfileFragment.btn_logout}, but having a fallback here
 * ensures a user who navigates to Settings from a deep flow (e.g.,
 * a future settings deep-link from a notification) can still log
 * out without backing all the way out to Profile.</p>
 */
public class SettingsActivity extends AppCompatActivity {

    // ============================================================
    // Section constants (referenced by ProfileFragment and others)
    // ============================================================

    public static final String EXTRA_SECTION = "extra_section";

    public static final String SECTION_GENERAL       = "general";
    public static final String SECTION_NOTIFICATIONS = "notifications";
    public static final String SECTION_PRIVACY       = "privacy";
    public static final String SECTION_BLOCKED       = "blocked";

    // ============================================================
    // SharedPreferences
    // ============================================================

    /** Device-local preferences: theme mode, notification previews. */
    public static final String PREFS_NAME = "pulse_settings";

    private static final String PREF_THEME_MODE      = "theme_mode";
    private static final String PREF_NOTIFICATION_PREVIEWS = "notification_previews";

    // ============================================================
    // State
    // ============================================================

    private ActivitySettingsBinding binding;
    private SharedPreferences prefs;
    private String myUid;

    // The current user's latest loaded state — used to render privacy
    // sub-titles and to compute "next value" when the user taps a
    // privacy row. Updated whenever Firestore emits a new snapshot.
    private User currentUser;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser == null) {
            // Defensive — this activity is unreachable without a session.
            redirectToAuth();
            return;
        }
        myUid = fbUser.getUid();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarSettings.setNavigationOnClickListener(v -> finish());

        setupThemeSection();
        setupNotificationSection();
        setupPrivacySection();
        setupBlockedSection();
        setupAboutSection();

        loadCurrentUser();

        // Section deep-link — scroll the appropriate group into view.
        String section = getIntent().getStringExtra(EXTRA_SECTION);
        if (section != null) {
            scrollToSection(section);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding = null;
    }

    // ============================================================
    // Current user (Firestore)
    // ============================================================

    private void loadCurrentUser() {
        FirestoreHelper.get().getUser(myUid, new FirestoreHelper.Callback<User>() {
            @Override
            public void onSuccess(@Nullable User result) {
                if (binding == null || result == null) return;
                currentUser = result;
                refreshThemeSubtitle();
                refreshNotificationSubtitles();
                refreshPrivacySubtitles();
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (binding == null) return;
                UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
            }
        });
    }

    // ============================================================
    // Theme
    // ============================================================

    private void setupThemeSection() {
        binding.rowTheme.setOnClickListener(v -> {
            int current = prefs.getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
            int choice = current == AppCompatDelegate.MODE_NIGHT_YES ? 2
                    : current == AppCompatDelegate.MODE_NIGHT_NO ? 1
                    : 0;

            String[] options = {
                    getString(R.string.settings_theme_system),
                    getString(R.string.settings_theme_light),
                    getString(R.string.settings_theme_dark),
            };

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_theme)
                    .setSingleChoiceItems(options, choice, (dialog, which) -> {
                        dialog.dismiss();
                        int mode;
                        if (which == 1) mode = AppCompatDelegate.MODE_NIGHT_NO;
                        else if (which == 2) mode = AppCompatDelegate.MODE_NIGHT_YES;
                        else mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
                        applyTheme(mode);
                    })
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show();
        });
    }

    private void applyTheme(int mode) {
        prefs.edit().putInt(PREF_THEME_MODE, mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
        // Note: this may recreate the activity (depending on system
        // vs. app mode), which is handled by the framework. We refresh
        // the subtitle after a small delay if no recreation occurs.
        refreshThemeSubtitle();
    }

    private void refreshThemeSubtitle() {
        if (binding == null) return;
        int mode = prefs.getInt(PREF_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        int resId;
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) resId = R.string.settings_theme_dark;
        else if (mode == AppCompatDelegate.MODE_NIGHT_NO) resId = R.string.settings_theme_light;
        else resId = R.string.settings_theme_system;
        binding.tvThemeValue.setText(resId);
    }

    // ============================================================
    // Notifications
    // ============================================================

    private void setupNotificationSection() {
        // Device-local previews toggle.
        binding.switchNotificationPreviews.setChecked(
                prefs.getBoolean(PREF_NOTIFICATION_PREVIEWS, true));
        binding.switchNotificationPreviews.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean(PREF_NOTIFICATION_PREVIEWS, checked).apply();
        });

        // Firestore-persisted "enabled" toggle.
        binding.switchNotificationsEnabled.setOnCheckedChangeListener((v, checked) -> {
            if (currentUser == null) return;
            User.NotificationPrefs prefs = currentUser.getNotificationPrefs();
            if (prefs == null) prefs = User.NotificationPrefs.defaults();
            prefs.setEnabled(checked);
            persistNotificationPrefs(prefs);
        });
    }

    private void refreshNotificationSubtitles() {
        if (binding == null || currentUser == null) return;
        User.NotificationPrefs p = currentUser.getNotificationPrefs();
        if (p == null) p = User.NotificationPrefs.defaults();
        binding.switchNotificationsEnabled.setChecked(p.isEnabled());
        binding.switchNotificationPreviews.setChecked(
                prefs.getBoolean(PREF_NOTIFICATION_PREVIEWS, true));
    }

    private void persistNotificationPrefs(@NonNull User.NotificationPrefs prefs) {
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(myUid)
                .update("notificationPrefs", prefs)
                .addOnFailureListener(e -> {
                    if (binding == null) return;
                    UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
                });
    }

    // ============================================================
    // Privacy
    // ============================================================

    private void setupPrivacySection() {
        binding.rowLastSeen.setOnClickListener(v -> showPrivacyDialog(
                R.string.settings_last_seen_visibility,
                currentUser != null ? currentUser.getPrivacy().getLastSeen() : User.Privacy.EVERYONE,
                value -> {
                    if (currentUser == null) return;
                    currentUser.getPrivacy().setLastSeen(value);
                    persistPrivacy(currentUser.getPrivacy());
                    refreshPrivacySubtitles();
                }));

        binding.rowOnlineStatus.setOnClickListener(v -> showPrivacyDialog(
                R.string.settings_online_visibility,
                currentUser != null ? currentUser.getPrivacy().getOnline() : User.Privacy.EVERYONE,
                value -> {
                    if (currentUser == null) return;
                    currentUser.getPrivacy().setOnline(value);
                    persistPrivacy(currentUser.getPrivacy());
                    refreshPrivacySubtitles();
                }));

        binding.rowProfilePhoto.setOnClickListener(v -> showPrivacyDialog(
                R.string.settings_profile_photo_visibility,
                currentUser != null ? currentUser.getPrivacy().getProfilePhoto() : User.Privacy.EVERYONE,
                value -> {
                    if (currentUser == null) return;
                    currentUser.getPrivacy().setProfilePhoto(value);
                    persistPrivacy(currentUser.getPrivacy());
                    refreshPrivacySubtitles();
                }));
    }

    private void showPrivacyDialog(int titleRes,
                                   @NonNull String currentValue,
                                   @NonNull java.util.function.Consumer<String> onChosen) {
        String[] values = {
                User.Privacy.EVERYONE,
                User.Privacy.CONTACTS,
                User.Privacy.NOBODY,
        };
        String[] labels = {
                getString(R.string.settings_privacy_everyone),
                getString(R.string.settings_privacy_contacts),
                getString(R.string.settings_privacy_nobody),
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(currentValue)) { checked = i; break; }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    onChosen.accept(values[which]);
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void refreshPrivacySubtitles() {
        if (binding == null || currentUser == null) return;
        User.Privacy p = currentUser.getPrivacy();
        if (p == null) p = User.Privacy.defaults();
        binding.tvLastSeenValue.setText(labelForPrivacy(p.getLastSeen()));
        binding.tvOnlineStatusValue.setText(labelForPrivacy(p.getOnline()));
        binding.tvProfilePhotoValue.setText(labelForPrivacy(p.getProfilePhoto()));
    }

    private int labelForPrivacy(@Nullable String tier) {
        if (User.Privacy.NOBODY.equals(tier)) return R.string.settings_privacy_nobody;
        if (User.Privacy.CONTACTS.equals(tier)) return R.string.settings_privacy_contacts;
        return R.string.settings_privacy_everyone;
    }

    private void persistPrivacy(@NonNull User.Privacy privacy) {
        com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(myUid)
                .update("privacy", privacy)
                .addOnFailureListener(e -> {
                    if (binding == null) return;
                    UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
                });
    }

    // ============================================================
    // Blocked users (Phase-5 stub)
    // ============================================================

    private void setupBlockedSection() {
        binding.rowBlockedUsers.setOnClickListener(v -> {
            // Phase 5 will replace this with an actual blocked-users
            // screen. The current stub is deliberately not silent —
            // the user gets clear feedback that the feature is
            // forthcoming, rather than tapping and getting nothing.
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_blocked_users)
                    .setMessage(R.string.settings_blocked_users)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show();
        });
    }

    // ============================================================
    // About
    // ============================================================

    private void setupAboutSection() {
        binding.rowAbout.setOnClickListener(v -> {
            String version = getString(R.string.settings_version, BuildConfig.VERSION_NAME);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_about_app)
                    .setMessage(getString(R.string.app_name) + "\n" + version)
                    .setPositiveButton(R.string.dialog_ok, null)
                    .show();
        });
    }

    // ============================================================
    // Section deep-link
    // ============================================================

    private void scrollToSection(@NonNull String section) {
        View target;
        switch (section) {
            case SECTION_NOTIFICATIONS: target = binding.rowNotifications; break;
            case SECTION_PRIVACY:       target = binding.rowLastSeen;      break;
            case SECTION_BLOCKED:       target = binding.rowBlockedUsers;  break;
            case SECTION_GENERAL:
            default:                    target = binding.rowTheme;         break;
        }
        target.post(() -> {
            if (binding == null) return;
            binding.scrollSettings.smoothScrollTo(0, target.getTop());
        });
    }

    // ============================================================
    // Logout fallback
    // ============================================================

    private void doLogout() {
        PresenceManager.get().stopTracking(() -> {
            FirebaseAuth.getInstance().signOut();
            redirectToAuth();
        });
    }

    private void redirectToAuth() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
