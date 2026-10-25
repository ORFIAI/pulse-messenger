package com.pulse.messenger.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.R;
import com.pulse.messenger.auth.AuthActivity;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.data.PresenceManager;
import com.pulse.messenger.databinding.ActivityMainBinding;
import com.pulse.messenger.model.User;

/**
 * Main activity — hosts the Chats / People / Profile bottom navigation.
 *
 * <p><b>Architecture:</b> a single {@code FragmentContainerView} swaps
 * three fragments via
 * {@link FragmentManager#beginTransaction()}. Fragments are tagged
 * and cached by {@code findFragmentByTag}; switching tabs re-attaches
 * an existing fragment instead of recreating it. This preserves the
 * chat list's scroll position and its LiveData subscriptions when
 * the user taps between tabs — the standard behavior users expect
 * from a bottom-nav shell.</p>
 *
 * <p><b>Back-stack policy:</b> the bottom navigation always shows the
 * <em>current</em> tab; the back button exits the app. There is no
 * "return to Chats tab" — pressing back on the People tab closes the
 * app, matching Material Design's bottom navigation guidelines. Users
 * who want the Chats tab tap it explicitly.</p>
 *
 * <p><b>Session routing:</b> this activity assumes the user is
 * signed in. {@code AuthActivity} is the launcher and only routes here
 * after Firebase reports a signed-in user. If somehow this activity is
 * reached without a user (e.g., a stale task from an old install), it
 * redirects to {@code AuthActivity} and finishes — never crashes, never
 * shows a broken shell.</p>
 *
 * <p><b>Presence:</b> {@link PresenceManager#startTracking(String)} is
 * called from {@code onCreate} once the user is confirmed. This is
 * the deferred Phase-2 integration — the manager's lifecycle observer
 * then handles online/offline transitions automatically for the
 * remainder of the app's session. Signout is responsible for calling
 * {@link PresenceManager#stopTracking(Runnable)} (see
 * {@code ProfileFragment}'s logout flow, Phase 4).</p>
 *
 * <p><b>Phase-3 changes from the Phase-1 placeholder:</b></p>
 * <ul>
 *   <li>Layout: {@code activity_main.xml} is replaced with a
 *       {@code CoordinatorLayout} containing a
 *       {@code FragmentContainerView} and a
 *       {@code BottomNavigationView}.</li>
 *   <li>Removed: the placeholder {@code describeUser} method and its
 *       two TextView IDs are no longer bound here.</li>
 *   <li>Added: tab switching, fragment caching, {@code
 *       selectPeopleTab()} / {@code selectChatsTab()} public methods
 *       for cross-fragment navigation, and presence tracking
 *       initialization.</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    // Fragment tags — used as the FragmentManager's internal cache keys.
    private static final String TAG_CHATS   = "tag_chats";
    private static final String TAG_PEOPLE  = "tag_people";
    private static final String TAG_PROFILE = "tag_profile";

    // Menu item IDs from res/menu/menu_bottom_nav.xml
    private static final int NAV_CHATS   = R.id.nav_chats;
    private static final int NAV_PEOPLE  = R.id.nav_people;
    private static final int NAV_PROFILE = R.id.nav_profile;

    private ActivityMainBinding binding;
    private int currentTabId = NAV_CHATS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // Defensive redirect — this should never happen in normal
            // flow (AuthActivity is the launcher and only routes here
            // when signed in), but a stale task or a revoked session
            // could leave the app on this activity without a user.
            redirectToAuth();
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupBottomNav(savedInstanceState);
        startPresence(user.getUid());

        // Ensure the signed-in user has a profile document. If this is
        // the very first sign-in the profile is created here, before
        // any fragment tries to read it. The callback fires on the
        // main thread once Firestore confirms.
        FirestoreHelper.get().ensureUserProfile(user, new FirestoreHelper.Callback<User>() {
            @Override public void onSuccess(@androidx.annotation.Nullable User result) { }
            @Override public void onError(@androidx.annotation.NonNull Exception e) {
                // Profile bootstrap failure is non-fatal — the profile
                // edit screen will show "profile not found" if the user
                // navigates there before the write succeeds, and it will
                // retry on next open. Do not show a Snackbar here.
            }
        });
    }

    // ============================================================
    // Bottom navigation
    // ============================================================

    private void setupBottomNav(@androidx.annotation.Nullable Bundle savedInstanceState) {
        binding.bottomNav.setOnItemSelectedListener(this::onNavItemSelected);
        binding.bottomNav.setOnItemReselectedListener(item ->
                // Reselecting the current tab is a no-op. Do not scroll
                // to top or refresh — that produces a jarring jump when
                // users tap the already-selected tab by accident.
                {});

        // Restore the previously selected tab on configuration change
        // (rotation, theme switch). Otherwise default to Chats.
        int restoreId = savedInstanceState != null
                ? savedInstanceState.getInt("currentTabId", NAV_CHATS)
                : NAV_CHATS;

        if (restoreId != NAV_CHATS) {
            binding.bottomNav.setSelectedItemId(restoreId);
            // The listener fires the fragment swap.
        } else {
            binding.bottomNav.setSelectedItemId(NAV_CHATS);
            // Ensure a first-tab fragment exists even if the framework
            // did not restore one (fresh install / clean launch).
            if (getSupportFragmentManager().findFragmentByTag(TAG_CHATS) == null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new ChatsFragment(), TAG_CHATS)
                        .commit();
            }
        }
    }

    private boolean onNavItemSelected(@NonNull android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == currentTabId) {
            return false;   // Already selected; no-op.
        }
        if (id == NAV_CHATS)   { switchTo(TAG_CHATS,   ChatsFragment::new,   id); return true; }
        if (id == NAV_PEOPLE)  { switchTo(TAG_PEOPLE,  PeopleFragment::new,  id); return true; }
        if (id == NAV_PROFILE) { switchTo(TAG_PROFILE, ProfileFragment::new, id); return true; }
        return false;
    }

    /**
     * Swaps to the fragment with the given tag, creating it via
     * {@code factory} on first use and re-attaching on subsequent
     * uses. {@code newTabId} is stored as the current selection so
     * consecutive taps on the same tab are correctly no-ops.
     */
    private void switchTo(@NonNull String tag,
                          @NonNull java.util.function.Supplier<Fragment> factory,
                          int newTabId) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment existing = fm.findFragmentByTag(tag);

        androidx.fragment.app.FragmentTransaction tx = fm.beginTransaction();
        if (existing == null) {
            tx.replace(R.id.fragment_container, factory.get(), tag);
        } else {
            tx.replace(R.id.fragment_container, existing, tag);
        }
        tx.commit();

        currentTabId = newTabId;
    }

    // ============================================================
    // Public navigation API (used by fragments)
    // ============================================================

    /**
     * Switches the bottom navigation to the People tab. Called by
     * {@code ChatsFragment} when the FAB is tapped.
     */
    public void selectPeopleTab() {
        binding.bottomNav.setSelectedItemId(NAV_PEOPLE);
    }

    /** Switches the bottom navigation to the Chats tab. */
    public void selectChatsTab() {
        binding.bottomNav.setSelectedItemId(NAV_CHATS);
    }

    /** Switches the bottom navigation to the Profile tab. */
    public void selectProfileTab() {
        binding.bottomNav.setSelectedItemId(NAV_PROFILE);
    }

    // ============================================================
    // Presence
    // ============================================================

    private void startPresence(@NonNull String uid) {
        PresenceManager.get().startTracking(uid);
        // The manager installs ProcessLifecycleOwner as an observer and
        // handles online/offline transitions internally. There is no
        // additional work here — startTracking is idempotent for the
        // same UID (a second call while already tracking is a no-op
        // that just ensures the user is online).
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("currentTabId", currentTabId);
    }

    // ============================================================
    // Session redirect (defensive)
    // ============================================================

    private void redirectToAuth() {
        Intent intent = new Intent(this, AuthActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
