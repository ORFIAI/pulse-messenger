package com.pulse.messenger.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Firestore POJO for a user profile document.
 *
 * <p><b>Storage location:</b> {@code users/{uid}} — the document ID is
 * always the Firebase UID. The {@link DocumentId} annotation on
 * {@link #uid} tells Firestore to populate that field from the document
 * ID on read, and to skip it when serializing on write (so the doc does
 * not redundantly store its own ID as a field). This is the recommended
 * pattern and avoids a class of "does getUid() return null?" bugs.</p>
 *
 * <p><b>Firestore requirements this class honors:</b></p>
 * <ul>
 *   <li>Public no-arg constructor (Firestore's deserializer needs it).</li>
 *   <li>Standard JavaBean getters/setters for every field.</li>
 *   <li>{@link ServerTimestamp} annotations so the two time fields are
 *       authoritative server times. If the field is {@code null} on
 *       write, Firestore replaces it with the current server timestamp.
 *       This prevents device clock skew from producing {@code lastSeen}
 *       values in the past or in the far future.</li>
 * </ul>
 *
 * <p><b>Security invariants enforced elsewhere (not by this class):</b></p>
 * <ul>
 *   <li>Firestore rules forbid a user from modifying {@link #uid},
 *       {@link #email}, or {@link #createdAt} after creation.</li>
 *   <li>Firestore rules forbid one user from writing another user's
 *       profile at all.</li>
 *   <li>{@link #blockedUids} is written only by the owning user — no
 *       other user can add themselves to or remove themselves from it.</li>
 * </ul>
 */
public class User {

    /**
     * Firebase UID. Populated by {@link DocumentId} on read; ignored on
     * write. Never display this to the user — it is an opaque internal
     * identifier. Never use it in a URL or copy-paste field, only in
     * document paths and authorization checks.
     */
    @DocumentId
    private String uid;

    /** Human-readable name. Shown everywhere the user appears in the UI. */
    private String displayName;

    /**
     * Unique, lowercase handle. Reserved via a transaction against
     * {@code usernames/{handle}} so it is globally unique across the
     * app. May be empty for a user who has not completed onboarding
     * yet (first sign-in before ProfileEditActivity is submitted).
     */
    private String username;

    /**
     * Google account email. Never shown to other users — it is stored
     * for account-recovery and support purposes only, and Firestore
     * rules forbid it from being included in any user-search result.
     */
    private String email;

    /** URL to the profile photo in Firebase Cloud Storage. May be null. */
    private String photoUrl;

    /** Short free-text "about" section. May be empty. */
    private String bio;

    /**
     * Server timestamp of the first profile write. Immutable after
     * creation (Firestore rules reject updates to this field).
     */
    @ServerTimestamp
    private Date createdAt;

    /**
     * Server timestamp of the last recorded online presence. Updated by
     * the presence manager. Distinct from {@link #online}: the UI shows
     * {@code online=true} as "Online" and otherwise falls back to a
     * relative rendering of {@code lastSeen}.
     */
    @ServerTimestamp
    private Date lastSeen;

    /**
     * Presence state mirrored from Firebase Realtime Database. Firestore
     * is not used as the authoritative presence source — RTDB's
     * {@code onDisconnect()} is the only reliable way to clear the flag
     * when the device goes offline unexpectedly. This field is a
     * convenience for queries that want to avoid a second lookup.
     */
    private boolean online;

    /** Privacy preferences (last-seen visibility, online visibility, photo visibility). */
    private Privacy privacy;

    /** Notification preferences (enabled, previews, sound). */
    private NotificationPrefs notificationPrefs;

    /**
     * List of UIDs this user has blocked. Authoritative for the block
     * policy: a blocked user cannot send a direct message to this user,
     * and any existing private conversation is hidden from both sides.
     */
    private List<String> blockedUids;

    /**
     * Latest FCM registration token for this device. Overwritten on
     * every {@code onNewToken} callback and on every sign-in. May be
     * null until the first FCM token is received.
     */
    private String fcmToken;

    /** Required by Firestore's deserializer. Do not remove. */
    public User() {
    }

    // ============================================================
    // Static factory
    // ============================================================

    /**
     * Builds a fresh {@link User} from a signed-in {@link FirebaseUser},
     * suitable for a first-ever profile write.
     *
     * <p>Only fields that come from Google's account are populated.
     * {@link #username} is deliberately left empty so the onboarding
     * screen ({@code ProfileEditActivity}) forces the user to choose
     * one. {@link #createdAt} and {@link #lastSeen} are left null so
     * Firestore populates them with the server timestamp on the first
     * write — the device clock is never trusted for these fields.</p>
     */
    @NonNull
    public static User fromFirebaseUser(@NonNull FirebaseUser fbUser) {
        User u = new User();
        u.uid = fbUser.getUid();
        u.displayName = safeTrim(fbUser.getDisplayName());
        u.email = safeTrim(fbUser.getEmail());
        u.photoUrl = fbUser.getPhotoUrl() != null
                ? fbUser.getPhotoUrl().toString()
                : null;
        u.bio = "";
        u.username = "";
        u.online = true;
        u.privacy = Privacy.defaults();
        u.notificationPrefs = NotificationPrefs.defaults();
        u.blockedUids = new ArrayList<>();
        u.fcmToken = null;
        return u;
    }

    // ============================================================
    // Getters / setters
    // ============================================================

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Date lastSeen) {
        this.lastSeen = lastSeen;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    public Privacy getPrivacy() {
        return privacy;
    }

    public void setPrivacy(Privacy privacy) {
        this.privacy = privacy;
    }

    public NotificationPrefs getNotificationPrefs() {
        return notificationPrefs;
    }

    public void setNotificationPrefs(NotificationPrefs notificationPrefs) {
        this.notificationPrefs = notificationPrefs;
    }

    public List<String> getBlockedUids() {
        return blockedUids;
    }

    public void setBlockedUids(List<String> blockedUids) {
        this.blockedUids = blockedUids;
    }

    public String getFcmToken() {
        return fcmToken;
    }

    public void setFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    // ============================================================
    // Convenience helpers
    // ============================================================

    /**
     * Returns a display label that is safe to render in any UI context.
     * Never returns {@code null}.
     *
     * <p>Priority: display name → username → a generic fallback. The
     * email address is never used as a display label — it is private
     * and must not appear in any shared UI.</p>
     */
    @NonNull
    public String getDisplayLabel() {
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName;
        }
        if (username != null && !username.trim().isEmpty()) {
            return username;
        }
        return "Pulse user";
    }

    /**
     * Returns {@code true} if this user has blocked the given UID.
     * Null-safe: a null or missing blocked list is treated as "no
     * blocked users," which matches the state of a newly-created
     * profile.
     */
    public boolean hasBlocked(@Nullable String otherUid) {
        if (otherUid == null || blockedUids == null) {
            return false;
        }
        return blockedUids.contains(otherUid);
    }

    // ============================================================
    // Privacy prefs
    // ============================================================

    /**
     * Privacy visibility settings. Each tier is one of the string
     * constants below. Stored as strings rather than enums so that
     * adding a new tier in the future does not break deserialization
     * of existing documents.
     */
    public static class Privacy {

        public static final String EVERYONE = "everyone";
        public static final String CONTACTS = "contacts";
        public static final String NOBODY = "nobody";

        private String lastSeen;
        private String online;
        private String profilePhoto;

        public Privacy() {
        }

        /**
         * Fresh privacy block. Every field starts at {@link #EVERYONE}
         * — the standard default for a new chat account; users who
         * want to tighten visibility can do so from Settings without
         * hunting for a hidden default.
         */
        @NonNull
        public static Privacy defaults() {
            Privacy p = new Privacy();
            p.lastSeen = EVERYONE;
            p.online = EVERYONE;
            p.profilePhoto = EVERYONE;
            return p;
        }

        public String getLastSeen() {
            return lastSeen != null ? lastSeen : EVERYONE;
        }

        public void setLastSeen(String lastSeen) {
            this.lastSeen = lastSeen;
        }

        public String getOnline() {
            return online != null ? online : EVERYONE;
        }

        public void setOnline(String online) {
            this.online = online;
        }

        public String getProfilePhoto() {
            return profilePhoto != null ? profilePhoto : EVERYONE;
        }

        public void setProfilePhoto(String profilePhoto) {
            this.profilePhoto = profilePhoto;
        }
    }

    // ============================================================
    // Notification prefs
    // ============================================================

    /**
     * Per-account notification preferences. These are user-level
     * defaults — per-conversation overrides (mute, pin) live on the
     * conversation's {@code memberInfo} map, not here.
     */
    public static class NotificationPrefs {

        private boolean enabled;
        private boolean previews;
        private boolean sound;

        public NotificationPrefs() {
        }

        /** All-on defaults, matching user expectations for a fresh chat install. */
        @NonNull
        public static NotificationPrefs defaults() {
            NotificationPrefs n = new NotificationPrefs();
            n.enabled = true;
            n.previews = true;
            n.sound = true;
            return n;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isPreviews() {
            return previews;
        }

        public void setPreviews(boolean previews) {
            this.previews = previews;
        }

        public boolean isSound() {
            return sound;
        }

        public void setSound(boolean sound) {
            this.sound = sound;
        }
    }

    // ============================================================
    // Utility
    // ============================================================

    private static String safeTrim(@Nullable String value) {
        return value == null ? null : value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User)) return false;
        User other = (User) o;
        return Objects.equals(uid, other.uid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uid);
    }

    @NonNull
    @Override
    public String toString() {
        // Deliberately excludes email and any token — toString() output
        // can end up in logcat or crash reports, and those two fields
        // are the sensitive ones.
        return "User{uid='" + uid + "', username='" + username + "'}";
    }
}
