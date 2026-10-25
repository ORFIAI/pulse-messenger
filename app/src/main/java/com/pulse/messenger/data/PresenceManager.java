package com.pulse.messenger.data;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Presence tracking backed by Firebase Realtime Database, with a
 * Firestore mirror for the current user's own online state.
 *
 * <p><b>Why RTDB for presence:</b> Firestore has no equivalent of
 * RTDB's {@code onDisconnect()} server-side trigger. When a device
 * goes offline — airplane mode, battery dies, process killed by the
 * OS, network tower handoff — Firestore has no way to automatically
 * flip a document field to {@code false}. RTDB's {@code onDisconnect()}
 * registers a server-side handler that fires on socket drop and
 * writes {@code offline} + the disconnect timestamp within seconds.
 * This is the only reliable mechanism Firebase offers for presence,
 * and it is the exact reason every production Firebase messaging app
 * uses RTDB (or a Cloud Function on RTDB) for presence rather than
 * Firestore.</p>
 *
 * <p><b>Two storage locations, two purposes:</b></p>
 * <ul>
 *   <li><b>RTDB {@code /status/{uid}}</b> — the authoritative presence
 *       record. Read only by {@code ChatActivity} (for the peer's
 *       online/last-seen in the chat header) and by the writing
 *       device itself. Never read by the chat list.</li>
 *   <li><b>Firestore {@code users/{uid}.online} + {@code .lastSeen}</b>
 *       — a best-effort mirror. Written on every online/offline
 *       transition <i>by the client</i>. Used only by classes that
 *       read the {@code User} POJO and want a cheap offline-state hint
 *       without a second RTDB subscription.</li>
 * </ul>
 *
 * <p><b>Mirror staleness (documented limitation):</b> if the app
 * process is killed abruptly, RTDB correctly flips to offline via
 * {@code onDisconnect} but the Firestore mirror stays {@code true}
 * until the user next opens the app (which writes {@code online=true}
 * again — but only the *client* can write the mirror; server-side
 * mirroring requires a Cloud Function trigger on {@code /status},
 * which is out of scope for v1). Consumers of the Firestore mirror
 * must treat it as a hint, not an authority. The chat header uses
 * RTDB for the peer's presence — never Firestore — so it is always
 * accurate within a few seconds of a real transition.</p>
 *
 * <p><b>Multi-device (documented limitation):</b> this manager assumes
 * one active device per user. If the same user opens the app on two
 * devices, both write to {@code /status/{uid}}; the last write wins,
 * and one device going offline fires {@code onDisconnect} for the
 * whole user (which is what the RTDB path structure permits). Per-
 * device presence ({@code /status/{uid}/{deviceId}}) is a v2 feature
 * that requires a different RTDB rule shape and an aggregator; it is
 * not implemented here.</p>
 *
 * <p><b>Lifecycle:</b> presence is driven by {@link ProcessLifecycleOwner},
 * not by Activity lifecycle. {@link ProcessLifecycleOwner} fires
 * {@code ON_START} when the first activity starts and {@code ON_STOP}
 * when the last one stops — and it debounces configuration changes
 * (rotations, theme switches) with a 700 ms delay so that a rotation
 * does not produce two presence writes. This is the correct tool for
 * the job: {@code ActivityLifecycleCallbacks} would fire on every
 * rotation and require reimplementing the same debounce.</p>
 *
 * <p><b>Write suppression:</b> {@link #goOnline()} is a no-op if the
 * user is already online; {@link #scheduleOffline()} debounces the
 * offline write by {@link #OFFLINE_DEBOUNCE_MS} so a brief app switch
 * (notification shade, permission dialog) does not produce a
 * spurious offline/online pair. Only sustained background states
 * produce the write.</p>
 */
public class PresenceManager implements DefaultLifecycleObserver {

    /** Delay before treating a background transition as a real offline event. */
    private static final long OFFLINE_DEBOUNCE_MS = 1500L;

    private static final String RTDB_STATUS = "status";
    private static final String FIRESTORE_USERS = "users";

    // ============================================================
    // Listener
    // ============================================================

    /**
     * Callback for peer-presence subscriptions. {@code lastSeen} is
     * null when the peer has no recorded presence (never opened the
     * app since we began tracking). It is never in the future —
     * server timestamps are authoritative.
     */
    public interface PresenceListener {
        void onPresence(boolean online, @Nullable Date lastSeen);
    }

    // ============================================================
    // Singleton
    // ============================================================

    private static volatile PresenceManager INSTANCE;

    private final FirebaseDatabase rtdb;
    private final FirebaseFirestore db;
    private final Handler mainHandler;

    // ------------------------------------------------------------------
    // Local tracking state. All fields below are main-thread-only.
    // Every public method must be called from the main thread. The
    // class does not synchronize these fields because there is no
    // scenario where presence is written from a background thread —
    // lifecycle callbacks are main-thread, and startTracking /
    // stopTracking are called from Activities (main-thread).
    // ------------------------------------------------------------------

    @Nullable private String trackedUid;
    @Nullable private DatabaseReference statusRef;
    private boolean trackingStarted;
    private boolean isOnline;
    @Nullable private Runnable pendingOffline;

    private PresenceManager() {
        this.rtdb = FirebaseDatabase.getInstance();
        this.db = FirebaseFirestore.getInstance();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static PresenceManager get() {
        if (INSTANCE == null) {
            synchronized (PresenceManager.class) {
                if (INSTANCE == null) INSTANCE = new PresenceManager();
            }
        }
        return INSTANCE;
    }

    // ============================================================
    // Tracking lifecycle
    // ============================================================

    /**
     * Begins presence tracking for the given UID. Call this once,
     * immediately after a successful sign-in — typically from
     * {@code MainActivity.onCreate} or from the post-auth navigation
     * in {@code AuthActivity}, after
     * {@code FirestoreHelper.ensureUserProfile} has completed.
     *
     * <p>Idempotent when called with the same UID as an active
     * tracking session. Throws {@link IllegalStateException} when
     * called with a <i>different</i> UID while a session is active —
     * that pattern only occurs after a {@link #stopTracking(Runnable)}
     * was forgotten, which is a bug that should surface loudly rather
     * than silently orphan the previous user's presence record.</p>
     *
     * <p>Registration order matters: {@code onDisconnect()} is
     * registered <b>before</b> the initial {@code online} write. If the
     * client crashed between those two steps, the server would still
     * have a pending onDisconnect handler that fires on the next
     * disconnect — the alternative (writing online first) leaves the
     * user stuck as {@code online} forever if the client never
     * registers a disconnect handler.</p>
     */
    public void startTracking(@NonNull String uid) {
        if (trackingStarted) {
            if (uid.equals(trackedUid)) {
                // Idempotent re-attach — user opened the app again
                // while already tracked. Ensure we are online.
                goOnline();
                return;
            }
            throw new IllegalStateException(
                    "Already tracking uid " + trackedUid
                            + "; call stopTracking() before tracking a different user");
        }

        trackingStarted = true;
        trackedUid = uid;
        statusRef = rtdb.getReference(RTDB_STATUS).child(uid);

        // Register the server-side disconnect handler FIRST.
        Map<String, Object> offline = new HashMap<>();
        offline.put("state", "offline");
        offline.put("lastChanged", ServerValue.TIMESTAMP);
        statusRef.onDisconnect().setValue(offline);

        // Now mark online.
        goOnline();

        // Observe the app-wide foreground / background lifecycle.
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
    }

    /**
     * Ends presence tracking and writes a final offline record for the
     * tracked user. Call this from the sign-out flow <b>before</b>
     * {@code FirebaseAuth.signOut()} — the Firestore mirror write
     * requires an active authenticated session, and Firestore rules
     * will reject it after sign-out.
     *
     * <p>The Firestore write is fire-and-forget. Waiting for it would
     * block sign-out on network latency, and if the device is offline
     * the wait could exceed any reasonable timeout. In the worst case
     * (offline device, signout completes before the queued write
     * reaches the server), the mirror stays {@code online=true} until
     * the next sign-in refreshes it — a documented limitation. The
     * RTDB write, by contrast, is synchronous from the client's
     * perspective (it queues on the local connection and flushes
     * before the socket closes).</p>
     *
     * <p>{@code onComplete} runs on the main thread immediately after
     * local cleanup. Sign-out can proceed from inside it.</p>
     */
    public void stopTracking(@Nullable Runnable onComplete) {
        if (!trackingStarted) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // Cancel any pending debounced offline write — we are writing
        // explicitly right now.
        if (pendingOffline != null) {
            mainHandler.removeCallbacks(pendingOffline);
            pendingOffline = null;
        }

        String uid = trackedUid;

        // Write offline to RTDB and cancel the onDisconnect handler.
        // Cancel AFTER the write so that if the write is queued but
        // the socket drops first, onDisconnect still fires — belt and
        // braces.
        if (statusRef != null) {
            Map<String, Object> offline = new HashMap<>();
            offline.put("state", "offline");
            offline.put("lastChanged", ServerValue.TIMESTAMP);
            statusRef.setValue(offline);
            statusRef.onDisconnect().cancel();
        }

        // Mirror to Firestore (best-effort; user is still authed at
        // this point so the rules will accept the write).
        if (uid != null) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("online", false);
            updates.put("lastSeen", FieldValue.serverTimestamp());
            db.collection(FIRESTORE_USERS).document(uid).update(updates);
        }

        // Stop observing the process lifecycle.
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);

        // Clear local state.
        trackedUid = null;
        statusRef = null;
        isOnline = false;
        trackingStarted = false;

        if (onComplete != null) onComplete.run();
    }

    /** {@code true} if a user is currently being tracked. */
    public boolean isTracking() {
        return trackingStarted;
    }

    // ============================================================
    // Process lifecycle
    // ============================================================

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        // App came to the foreground (or a configuration change
        // completed — ProcessLifecycleOwner debounces those).
        goOnline();
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // App went to the background. Debounce the offline write so
        // a brief app switch does not produce a spurious state
        // change.
        scheduleOffline();
    }

    // ============================================================
    // Internal state transitions
    // ============================================================

    /**
     * Marks the user online. Cancels any pending debounced offline
     * write. No-op (no write) if already online — this is what
     * prevents repeated writes when multiple Activities start/stop
     * inside the same foreground session.
     */
    private void goOnline() {
        if (!trackingStarted || statusRef == null) return;

        // Cancel a pending offline write if one is queued.
        if (pendingOffline != null) {
            mainHandler.removeCallbacks(pendingOffline);
            pendingOffline = null;
        }

        if (isOnline) return;

        isOnline = true;
        Map<String, Object> online = new HashMap<>();
        online.put("state", "online");
        online.put("lastChanged", ServerValue.TIMESTAMP);
        statusRef.setValue(online);

        mirrorOnlineToFirestore(true);
    }

    /**
     * Schedules a debounced offline write. If the app returns to the
     * foreground within {@link #OFFLINE_DEBOUNCE_MS}, {@link #goOnline()}
     * cancels the pending write and no network traffic is generated.
     */
    private void scheduleOffline() {
        if (!trackingStarted) return;
        if (!isOnline) return;
        if (pendingOffline != null) return;   // already scheduled

        pendingOffline = () -> {
            pendingOffline = null;
            writeOfflineNow();
        };
        mainHandler.postDelayed(pendingOffline, OFFLINE_DEBOUNCE_MS);
    }

    /** Writes the offline state to RTDB and Firestore, unconditionally. */
    private void writeOfflineNow() {
        if (!trackingStarted || statusRef == null) return;
        if (!isOnline) return;

        isOnline = false;
        Map<String, Object> offline = new HashMap<>();
        offline.put("state", "offline");
        offline.put("lastChanged", ServerValue.TIMESTAMP);
        statusRef.setValue(offline);

        mirrorOnlineToFirestore(false);
    }

    /**
     * Mirrors the current online state to Firestore. Fire-and-forget —
     * a failed mirror write is not recoverable (no network, rules
     * rejected due to a bug, etc.) and has no user-visible impact
     * beyond a stale hint on the next cold read of the user document.
     */
    private void mirrorOnlineToFirestore(boolean online) {
        if (trackedUid == null) return;
        Map<String, Object> updates = new HashMap<>();
        updates.put("online", online);
        updates.put("lastSeen", FieldValue.serverTimestamp());
        db.collection(FIRESTORE_USERS).document(trackedUid).update(updates);
    }

    // ============================================================
    // Peer subscriptions
    // ============================================================

    /**
     * Subscribes to a peer's presence. The returned {@link ValueEventListener}
     * MUST be passed to {@link #stopListeningPeer(String, ValueEventListener)}
     * in the caller's {@code onStop()} — otherwise the RTDB connection
     * stays open for the caller's entire process lifetime.
     *
     * <p>Callers (specifically {@code ChatActivity}) are responsible
     * for enforcing the peer's privacy preference: even if this
     * manager reports {@code online=true} and a fresh {@code lastSeen},
     * the chat header must hide these when the peer's
     * {@code User.privacy.online} is {@code "nobody"}. This manager
     * intentionally returns the raw state and leaves the privacy
     * decision to the UI layer, where the peer's {@code User} document
     * is already loaded.</p>
     */
    @NonNull
    public ValueEventListener listenPeerPresence(@NonNull String peerUid,
                                                 @NonNull PresenceListener listener) {
        DatabaseReference ref = rtdb.getReference(RTDB_STATUS).child(peerUid);
        ValueEventListener vel = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean online = false;
                Date lastSeen = null;

                if (snapshot.exists()) {
                    Object stateObj = snapshot.child("state").getValue();
                    if ("online".equals(stateObj)) {
                        online = true;
                    }
                    Object tsObj = snapshot.child("lastChanged").getValue();
                    if (tsObj instanceof Long) {
                        long ms = (Long) tsObj;
                        // Sanity-clamp: a value in the far future would
                        // produce confusing "last seen in 2099" text.
                        // ServerValue.TIMESTAMP is authoritative; this
                        // guard is only against pathological states.
                        long now = System.currentTimeMillis();
                        if (ms > 0 && ms <= now + 60_000L) {
                            lastSeen = new Date(ms);
                        }
                    }
                }

                listener.onPresence(online, lastSeen);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Presence is best-effort. A cancelled listener means
                // the client lost permission to read /status/{peerUid}
                // (unexpected) or the RTDB connection was lost. Deliver
                // an "unknown" state so the caller can fall back to
                // "Offline" rather than freezing on the last-known
                // state.
                listener.onPresence(false, null);
            }
        };
        ref.addValueEventListener(vel);
        return vel;
    }

    /**
     * Removes a peer-presence listener previously returned by
     * {@link #listenPeerPresence}. Safe to call with {@code null} —
     * the caller may not have received a listener if setup was
     * interrupted.
     */
    public void stopListeningPeer(@NonNull String peerUid,
                                  @Nullable ValueEventListener vel) {
        if (vel == null) return;
        rtdb.getReference(RTDB_STATUS).child(peerUid).removeEventListener(vel);
    }
}
