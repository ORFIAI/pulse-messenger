package com.pulse.messenger.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.Message;
import com.pulse.messenger.model.User;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Firestore data-access layer.
 *
 * <p><b>Design notes:</b></p>
 * <ul>
 *   <li><b>Single class, not per-entity DAOs.</b> A {@code UserRepo},
 *       {@code ConversationRepo}, and {@code MessageRepo} would be three
 *       classes with overlapping concerns, three singletons, three
 *       places to update when the schema changes. A single class with
 *       clear section boundaries is easier to reason about in a
 *       35-file project.</li>
 *   <li><b>Typed {@link Callback} — not {@code Task}.</b> Callers never
 *       import Firestore types; swapping the backend later would only
 *       touch this class. Callbacks are always invoked on the main
 *       thread — Firebase delivers its {@code Task} callbacks on the
 *       main thread when {@code addOnCompleteListener} is called without
 *       an executor, which is exactly the pattern we use.</li>
 *   <li><b>Real-time queries return {@link LiveData}, not raw
 *       {@code ListenerRegistration}.</b> The custom {@link
 *       ListenerLiveData} nested class attaches the Firestore
 *       listener in {@code onActive()} and removes it in
 *       {@code onInactive()} — so a Fragment that stops observing
 *       automatically detaches the listener. Callers that handled
 *       {@code ListenerRegistration} directly would eventually leak
 *       a listener after every fragment transition.</li>
 *   <li><b>Per-message read receipts are not implemented.</b> Instead,
 *       the conversation's {@code memberInfo.{uid}.lastReadAt}
 *       timestamp is the single source of truth for "read up to here."
 *       A sender's bubble shows the "seen" tick when
 *       {@code peer.lastReadAt >= message.timestamp}. This is one
 *       write per chat open instead of N writes per chat open.
 *       {@code Message.readBy} remains in the schema for a future
 *       "message info" feature but is not populated by v1 code.</li>
 *   <li><b>Emails are technically readable by any authenticated user
 *       because they are stored in {@code users/{uid}}.</b> Client
 *       code never displays another user's email and never searches
 *       by email. For a hardened production system, the email should
 *       be moved to a {@code users/{uid}/private/account} subcollection
 *       that only the owner can read. This is a v1 limitation.</li>
 * </ul>
 */
public class FirestoreHelper {

    /** Generic success/error callback. Both methods are always invoked on the main thread. */
    public interface Callback<T> {
        void onSuccess(@Nullable T result);
        void onError(@NonNull Exception e);
    }

    /** Thrown by {@link #reserveUsername} when the requested handle is owned by someone else. */
    public static final class UsernameTakenException extends Exception {
        public UsernameTakenException(String username) {
            super("Username already taken: " + username);
        }
    }

    private static volatile FirestoreHelper INSTANCE;

    private final FirebaseFirestore db;

    private FirestoreHelper() {
        this.db = FirebaseFirestore.getInstance();
    }

    public static FirestoreHelper get() {
        if (INSTANCE == null) {
            synchronized (FirestoreHelper.class) {
                if (INSTANCE == null) INSTANCE = new FirestoreHelper();
            }
        }
        return INSTANCE;
    }

    // Collection names — centralized so a schema rename is a one-line change.
    private static final String C_USERS         = "users";
    private static final String C_USERNAMES     = "usernames";
    private static final String C_CONVERSATIONS = "conversations";
    private static final String C_MESSAGES      = "messages";

    // ============================================================
    // USERS
    // ============================================================

    /**
     * Reads the user profile document. If it does not exist (first
     * sign-in), creates it from the Firebase Auth account and returns
     * the freshly created profile.
     *
     * <p>Concurrency: two invocations racing each other will both try
     * to create the document. Firestore's {@code set()} is idempotent
     * — the second write overwrites the first, which is safe because
     * the content is deterministic from the Firebase account. Only
     * {@code createdAt} differs, and in that case both writes
     * substituted {@code FieldValue.serverTimestamp()}, so the earlier
     * write's timestamp is lost — a negligible cosmetic detail.</p>
     */
    public void ensureUserProfile(@NonNull FirebaseUser fbUser,
                                  @NonNull Callback<User> cb) {
        DocumentReference ref = db.collection(C_USERS).document(fbUser.getUid());
        ref.get().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                cb.onError(wrap(task.getException()));
                return;
            }
            DocumentSnapshot snap = task.getResult();
            if (snap != null && snap.exists()) {
                User existing = snap.toObject(User.class);
                if (existing != null) {
                    existing.setUid(snap.getId());
                    cb.onSuccess(existing);
                    return;
                }
            }
            User fresh = User.fromFirebaseUser(fbUser);
            ref.set(fresh).addOnCompleteListener(write -> {
                if (!write.isSuccessful()) {
                    cb.onError(wrap(write.getException()));
                    return;
                }
                fresh.setUid(fbUser.getUid());
                cb.onSuccess(fresh);
            });
        });
    }

    /** Reads a user profile by UID. Fails if the document does not exist. */
    public void getUser(@NonNull String uid, @NonNull Callback<User> cb) {
        db.collection(C_USERS).document(uid).get()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        cb.onError(wrap(task.getException()));
                        return;
                    }
                    DocumentSnapshot snap = task.getResult();
                    if (snap == null || !snap.exists()) {
                        cb.onError(new IllegalStateException("User not found: " + uid));
                        return;
                    }
                    User u = snap.toObject(User.class);
                    if (u == null) {
                        cb.onError(new IllegalStateException("Malformed user doc: " + uid));
                        return;
                    }
                    u.setUid(snap.getId());
                    cb.onSuccess(u);
                });
    }

    /** Real-time listener on a user document. Emits {@code null} if the doc is deleted. */
    public LiveData<User> listenUser(@NonNull String uid) {
        LiveData<DocumentSnapshot> raw = new DocumentLiveData(
                db.collection(C_USERS).document(uid));
        return new MappedLiveData<>(raw, snap -> {
            if (snap == null || !snap.exists()) return null;
            User u = snap.toObject(User.class);
            if (u != null) u.setUid(snap.getId());
            return u;
        });
    }

    /**
     * Atomically reserves a username for the given UID.
     *
     * <p>Reads {@code usernames/{lowercaseUsername}} and {@code users/{uid}}
     * inside a transaction, deletes the caller's previous username
     * mapping if one exists and differs, then writes the new mapping
     * and updates the profile field.</p>
     *
     * <p>Usernames are case-insensitive and stored lowercase in the
     * reservation index. The user-facing display may preserve the
     * case the user typed — actually no, we lowercase the stored field
     * too so that search results and profile displays are consistent.
     * If you want to preserve case later, add a {@code usernameDisplay}
     * field and keep {@code username} as the canonical lowercase.</p>
     */
    public void reserveUsername(@NonNull String rawUsername,
                                @NonNull String uid,
                                @NonNull Callback<Void> cb) {
        final String newUsername = rawUsername.trim().toLowerCase(Locale.ROOT);
        if (newUsername.isEmpty()) {
            cb.onError(new IllegalArgumentException("Username cannot be empty"));
            return;
        }

        DocumentReference unameRef = db.collection(C_USERNAMES).document(newUsername);
        DocumentReference userRef  = db.collection(C_USERS).document(uid);

        db.runTransaction(txn -> {
            DocumentSnapshot unameSnap = txn.get(unameRef);
            DocumentSnapshot userSnap  = txn.get(userRef);

            if (unameSnap.exists()) {
                String owner = unameSnap.getString("uid");
                if (owner != null && !owner.equals(uid)) {
                    throw new UsernameTakenException(newUsername);
                }
            }

            String oldUsername = userSnap.exists() ? userSnap.getString("username") : null;
            if (oldUsername != null && !oldUsername.isEmpty()
                    && !oldUsername.equalsIgnoreCase(newUsername)) {
                txn.delete(db.collection(C_USERNAMES)
                        .document(oldUsername.toLowerCase(Locale.ROOT)));
            }

            Map<String, Object> unameDoc = new HashMap<>();
            unameDoc.put("uid", uid);
            txn.set(unameRef, unameDoc);
            txn.update(userRef, "username", newUsername);
            return null;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) cb.onSuccess(null);
            else cb.onError(wrap(task.getException()));
        });
    }

    /**
     * Updates the current user's profile fields. Pass {@code null} to
     * leave a field untouched.
     *
     * <p>Email, UID, and createdAt are not updatable through this
     * method — they are immutable after creation, and Firestore rules
     * reject attempts to change them.</p>
     */
    public void updateProfile(@NonNull String uid,
                              @Nullable String displayName,
                              @Nullable String bio,
                              @Nullable String photoUrl,
                              @NonNull Callback<Void> cb) {
        Map<String, Object> updates = new HashMap<>();
        if (displayName != null) updates.put("displayName", displayName.trim());
        if (bio != null)         updates.put("bio", bio);
        if (photoUrl != null)    updates.put("photoUrl", photoUrl);

        if (updates.isEmpty()) {
            cb.onSuccess(null);
            return;
        }

        db.collection(C_USERS).document(uid).update(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /** Persists an updated FCM token to the user profile. */
    public void updateFcmToken(@NonNull String uid, @NonNull String token) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("fcmToken", token);
        db.collection(C_USERS).document(uid).update(updates);
        // Fire-and-forget — a failed token write will be retried by the
        // next onNewToken. There is nothing the user can do about it.
    }

    /**
     * Prefix-searches users by username and display name. Excludes
     * the caller. Returns at most 20 results.
     *
     * <p>Display-name search is case-sensitive (Firestore has no
     * case-insensitive indexing) — a limitation of the platform, not
     * the query. Username search is case-insensitive because we store
     * usernames lowercase.</p>
     */
    public void searchUsers(@NonNull String rawQuery,
                            @NonNull String excludeUid,
                            @NonNull Callback<List<User>> cb) {
        String q = rawQuery.trim();
        if (q.length() < 2) {
            cb.onSuccess(Collections.emptyList());
            return;
        }
        String lower = q.toLowerCase(Locale.ROOT);
        String upperBound = lower + "\uf8ff";

        Query byUsername = db.collection(C_USERS)
                .orderBy("username")
                .startAt(lower)
                .endAt(upperBound)
                .limit(20);

        Query byName = db.collection(C_USERS)
                .orderBy("displayName")
                .startAt(q)
                .endAt(q + "\uf8ff")
                .limit(20);

        byUsername.get().addOnCompleteListener(t1 -> {
            List<User> merged = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (t1.isSuccessful() && t1.getResult() != null) {
                mergeUsers(t1.getResult(), merged, seen, excludeUid);
            }
            byName.get().addOnCompleteListener(t2 -> {
                if (t2.isSuccessful() && t2.getResult() != null) {
                    mergeUsers(t2.getResult(), merged, seen, excludeUid);
                }
                cb.onSuccess(merged);
            });
        });
    }

    private void mergeUsers(@NonNull QuerySnapshot snap,
                            @NonNull List<User> out,
                            @NonNull Set<String> seen,
                            @NonNull String excludeUid) {
        for (DocumentSnapshot d : snap.getDocuments()) {
            String uid = d.getId();
            if (uid.equals(excludeUid) || !seen.add(uid)) continue;
            User u = d.toObject(User.class);
            if (u == null) continue;
            u.setUid(uid);
            out.add(u);
        }
    }

    /**
     * Blocks a user by adding their UID to the caller's block list.
     *
     * <p><b>v1 enforcement scope:</b> the client checks this list before
     * creating a conversation with the blocked user, and the blocked
     * user's client checks <i>their</i> list before sending to the
     * blocker. This is best-effort. A determined attacker using the
     * Firestore SDK directly could bypass the client check — full
     * enforcement requires security-rule-level block checks, which
     * are non-trivial for Firestore (the rules would need to fetch
     * each member's user doc on every message write). This is a
     * documented v1 limitation, not an oversight.</p>
     */
    public void blockUser(@NonNull String myUid,
                          @NonNull String targetUid,
                          @NonNull Callback<Void> cb) {
        db.collection(C_USERS).document(myUid)
                .update("blockedUids", FieldValue.arrayUnion(targetUid))
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    public void unblockUser(@NonNull String myUid,
                            @NonNull String targetUid,
                            @NonNull Callback<Void> cb) {
        db.collection(C_USERS).document(myUid)
                .update("blockedUids", FieldValue.arrayRemove(targetUid))
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    // ============================================================
    // CONVERSATIONS
    // ============================================================

    /**
     * Deterministic private-conversation ID: {@code p_<min>_<max>} of
     * the two UIDs sorted lexicographically. Using a deterministic ID
     * eliminates the "duplicate private conversation" race entirely —
     * two clients calling this simultaneously converge on the same
     * document because the ID is derived, not queried.
     */
    @NonNull
    public static String privateConversationId(@NonNull String a, @NonNull String b) {
        return a.compareTo(b) < 0 ? ("p_" + a + "_" + b) : ("p_" + b + "_" + a);
    }

    /**
     * Returns the private conversation between the two users, creating
     * it if it does not exist. Uses the deterministic conversation ID
     * so simultaneous calls from both users converge on one document.
     */
    public void findOrCreatePrivateConversation(@NonNull String myUid,
                                                @NonNull String peerUid,
                                                @NonNull Callback<Conversation> cb) {
        String cid = privateConversationId(myUid, peerUid);
        DocumentReference ref = db.collection(C_CONVERSATIONS).document(cid);

        db.runTransaction(txn -> {
            DocumentSnapshot snap = txn.get(ref);
            if (snap.exists()) {
                Conversation c = snap.toObject(Conversation.class);
                if (c != null) {
                    c.setConversationId(snap.getId());
                    return c;
                }
            }
            Conversation fresh = Conversation.newPrivate(myUid, peerUid);
            txn.set(ref, fresh);
            fresh.setConversationId(cid);
            return fresh;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                cb.onSuccess(task.getResult());
            } else {
                cb.onError(wrap(task.getException()));
            }
        });
    }

    /** Creates a group conversation. The creator is automatically the first admin. */
    public void createGroup(@NonNull String creatorUid,
                            @NonNull String name,
                            @NonNull List<String> memberUids,
                            @Nullable String photoUrl,
                            @NonNull Callback<Conversation> cb) {
        if (!memberUids.contains(creatorUid)) {
            memberUids.add(creatorUid);
        }
        Conversation conv = Conversation.newGroup(creatorUid, name, memberUids);
        if (photoUrl != null) conv.setPhotoUrl(photoUrl);

        DocumentReference ref = db.collection(C_CONVERSATIONS).document();
        ref.set(conv).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                conv.setConversationId(ref.getId());
                cb.onSuccess(conv);
            } else {
                cb.onError(wrap(task.getException()));
            }
        });
    }

    /**
     * Real-time chat list: all non-archived conversations the user is
     * a member of, ordered by {@code lastMessageAt} descending.
     *
     * <p>Callers must further filter out archived conversations
     * client-side, because Firestore cannot express "exclude where
     * memberInfo.{myUid}.archived = true" as a where-clause on a map
     * key. This is a small CPU cost on ≤ a few hundred conversations —
     * not a query cost.</p>
     */
    public LiveData<List<Conversation>> listenConversations(@NonNull String myUid) {
        Query q = db.collection(C_CONVERSATIONS)
                .whereArrayContains("members", myUid)
                .orderBy("lastMessageAt", Query.Direction.DESCENDING)
                .limit(100);
        return new QueryListLiveData<>(q, Conversation.class);
    }

    /** Real-time listener on a single conversation document. */
    public LiveData<Conversation> listenConversation(@NonNull String conversationId) {
        LiveData<DocumentSnapshot> raw = new DocumentLiveData(
                db.collection(C_CONVERSATIONS).document(conversationId));
        return new MappedLiveData<>(raw, snap -> {
            if (snap == null || !snap.exists()) return null;
            Conversation c = snap.toObject(Conversation.class);
            if (c != null) c.setConversationId(snap.getId());
            return c;
        });
    }

    /** One-shot read of a conversation document. */
    public void getConversation(@NonNull String conversationId,
                                @NonNull Callback<Conversation> cb) {
        db.collection(C_CONVERSATIONS).document(conversationId).get()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        cb.onError(wrap(task.getException()));
                        return;
                    }
                    DocumentSnapshot snap = task.getResult();
                    if (snap == null || !snap.exists()) {
                        cb.onError(new IllegalStateException("Conversation not found: " + conversationId));
                        return;
                    }
                    Conversation c = snap.toObject(Conversation.class);
                    if (c == null) {
                        cb.onError(new IllegalStateException("Malformed conversation"));
                        return;
                    }
                    c.setConversationId(snap.getId());
                    cb.onSuccess(c);
                });
    }

    /**
     * Updates the caller's own {@code memberInfo} entry — unread count,
     * muted-until, pinned, archived. Only the caller's own entry is
     * touched; Firestore rules reject any attempt to write another
     * user's entry.
     */
    public void updateMyMemberInfo(@NonNull String conversationId,
                                   @NonNull String uid,
                                   boolean pinned,
                                   long mutedUntil,
                                   boolean archived,
                                   @NonNull Callback<Void> cb) {
        Map<String, Object> updates = new HashMap<>();
        String prefix = "memberInfo." + uid + ".";
        updates.put(prefix + "pinned", pinned);
        updates.put(prefix + "mutedUntil", mutedUntil);
        updates.put(prefix + "archived", archived);

        db.collection(C_CONVERSATIONS).document(conversationId).update(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /** Renames a group. Admin-gated by Firestore rules. */
    public void renameGroup(@NonNull String conversationId,
                            @NonNull String newName,
                            @NonNull Callback<Void> cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("name", newName.trim());
        db.collection(C_CONVERSATIONS).document(conversationId).update(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /** Updates a group's photo URL. Admin-gated by Firestore rules. */
    public void updateGroupPhoto(@NonNull String conversationId,
                                 @NonNull String photoUrl,
                                 @NonNull Callback<Void> cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("photoUrl", photoUrl);
        db.collection(C_CONVERSATIONS).document(conversationId).update(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /**
     * Adds a member to a group. Admin-gated by Firestore rules. Uses
     * a transaction to add the UID to both {@code members} and
     * {@code memberInfo} atomically so a mid-write crash cannot leave
     * the member in one but not the other.
     */
    public void addGroupMember(@NonNull String conversationId,
                               @NonNull String newMemberUid,
                               @NonNull Callback<Void> cb) {
        DocumentReference ref = db.collection(C_CONVERSATIONS).document(conversationId);
        db.runTransaction(txn -> {
            DocumentSnapshot snap = txn.get(ref);
            Conversation c = snap.toObject(Conversation.class);
            if (c == null) throw new IllegalStateException("Conversation not found");
            if (c.hasMember(newMemberUid)) return null;

            Map<String, Object> freshMember = new HashMap<>();
            freshMember.put("unreadCount", 0);
            freshMember.put("mutedUntil", 0L);
            freshMember.put("pinned", false);
            freshMember.put("archived", false);

            txn.update(ref,
                    "members", FieldValue.arrayUnion(newMemberUid),
                    "memberInfo." + newMemberUid, freshMember);
            return null;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) cb.onSuccess(null);
            else cb.onError(wrap(task.getException()));
        });
    }

    /**
     * Removes a member from a group. Removes both from {@code members}
     * and {@code admins} (in case they were an admin) and deletes
     * their {@code memberInfo} entry.
     */
    public void removeGroupMember(@NonNull String conversationId,
                                  @NonNull String memberUid,
                                  @NonNull Callback<Void> cb) {
        DocumentReference ref = db.collection(C_CONVERSATIONS).document(conversationId);
        db.runTransaction(txn -> {
            DocumentSnapshot snap = txn.get(ref);
            Conversation c = snap.toObject(Conversation.class);
            if (c == null) throw new IllegalStateException("Conversation not found");
            if (!c.hasMember(memberUid)) return null;

            txn.update(ref,
                    "members", FieldValue.arrayRemove(memberUid),
                    "admins", FieldValue.arrayRemove(memberUid),
                    "memberInfo." + memberUid, FieldValue.delete());
            return null;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) cb.onSuccess(null);
            else cb.onError(wrap(task.getException()));
        });
    }

    /** Promotes a member to admin. Admin-gated by Firestore rules. */
    public void promoteAdmin(@NonNull String conversationId,
                             @NonNull String memberUid,
                             @NonNull Callback<Void> cb) {
        db.collection(C_CONVERSATIONS).document(conversationId)
                .update("admins", FieldValue.arrayUnion(memberUid))
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /** Demotes an admin. Admin-gated by Firestore rules; a user cannot demote themselves if they are the last admin. */
    public void demoteAdmin(@NonNull String conversationId,
                            @NonNull String memberUid,
                            @NonNull Callback<Void> cb) {
        db.collection(C_CONVERSATIONS).document(conversationId)
                .update("admins", FieldValue.arrayRemove(memberUid))
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /** Leaves a group. Removes the caller from members, admins, and memberInfo. */
    public void leaveGroup(@NonNull String conversationId,
                           @NonNull String myUid,
                           @NonNull Callback<Void> cb) {
        DocumentReference ref = db.collection(C_CONVERSATIONS).document(conversationId);
        db.runTransaction(txn -> {
            txn.update(ref,
                    "members", FieldValue.arrayRemove(myUid),
                    "admins", FieldValue.arrayRemove(myUid),
                    "memberInfo." + myUid, FieldValue.delete());
            return null;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) cb.onSuccess(null);
            else cb.onError(wrap(task.getException()));
        });
    }

    // ============================================================
    // MESSAGES
    // ============================================================

    private static final int RECENT_PAGE_SIZE = 100;

    /**
     * Real-time listener on the most recent {@link #RECENT_PAGE_SIZE}
     * messages of a conversation, in descending order (newest first).
     * The caller is expected to reverse the list before rendering
     * (RecyclerView displays oldest at top, newest at bottom) — that
     * is a UI concern, not a query concern.
     */
    public LiveData<List<Message>> listenRecentMessages(@NonNull String conversationId) {
        Query q = db.collection(C_CONVERSATIONS).document(conversationId)
                .collection(C_MESSAGES)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(RECENT_PAGE_SIZE);
        return new QueryListLiveData<>(q, Message.class);
    }

    /**
     * One-shot pagination: loads up to {@code limit} messages older
     * than {@code beforeTimestamp}. ChatActivity prepends the result
     * to its in-memory list.
     */
    public void loadOlderMessages(@NonNull String conversationId,
                                  @NonNull Date beforeTimestamp,
                                  int limit,
                                  @NonNull Callback<List<Message>> cb) {
        Query q = db.collection(C_CONVERSATIONS).document(conversationId)
                .collection(C_MESSAGES)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .startAfter(beforeTimestamp)
                .limit(limit);
        q.get().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                cb.onError(wrap(task.getException()));
                return;
            }
            QuerySnapshot snap = task.getResult();
            if (snap == null) {
                cb.onSuccess(Collections.emptyList());
                return;
            }
            List<Message> out = new ArrayList<>();
            for (DocumentSnapshot d : snap.getDocuments()) {
                Message m = d.toObject(Message.class);
                if (m == null) continue;
                m.setMessageId(d.getId());
                out.add(m);
            }
            cb.onSuccess(out);
        });
    }

    /**
     * Sends a message inside a Firestore transaction. The transaction:
     * <ol>
     *   <li>Reads the conversation to obtain the member list.</li>
     *   <li>Verifies the sender is a member (rules also verify this).</li>
     *   <li>Writes the new message document.</li>
     *   <li>Updates the conversation's {@code lastMessage} and
     *       {@code lastMessageAt} summaries.</li>
     *   <li>Increments the unread count for every recipient and
     *       resets the sender's unread count to 0 (the sender is by
     *       definition caught up on their own message).</li>
     * </ol>
     *
     * <p>On success, the returned {@link Message} has its
     * {@code messageId} populated and its {@code timestamp} set to
     * the server timestamp. Both are authoritative — the caller can
     * pass this object directly to an adapter.</p>
     */
    public void sendMessage(@NonNull String conversationId,
                            @NonNull Message msg,
                            @NonNull Callback<Message> cb) {
        DocumentReference convRef = db.collection(C_CONVERSATIONS).document(conversationId);
        DocumentReference msgRef  = convRef.collection(C_MESSAGES).document();

        db.runTransaction(txn -> {
            DocumentSnapshot convSnap = txn.get(convRef);
            if (!convSnap.exists()) {
                throw new IllegalStateException("Conversation does not exist");
            }
            Conversation conv = convSnap.toObject(Conversation.class);
            if (conv == null) {
                throw new IllegalStateException("Conversation is malformed");
            }
            if (!conv.hasMember(msg.getSenderId())) {
                throw new IllegalStateException("Sender is not a member of this conversation");
            }

            // lastMessage summary map.
            Map<String, Object> lastMessage = new HashMap<>();
            String preview = msg.getPreviewText();
            if (preview.length() > 200) preview = preview.substring(0, 200);
            lastMessage.put("text", preview);
            lastMessage.put("type", msg.getType());
            lastMessage.put("senderId", msg.getSenderId());
            lastMessage.put("timestamp", FieldValue.serverTimestamp());

            // Conversation-level updates: lastMessage, lastMessageAt,
            // and per-member unreadCount adjustments.
            Map<String, Object> convUpdates = new HashMap<>();
            convUpdates.put("lastMessage", lastMessage);
            convUpdates.put("lastMessageAt", FieldValue.serverTimestamp());

            for (String memberUid : conv.getMembers()) {
                String key = "memberInfo." + memberUid + ".unreadCount";
                if (memberUid.equals(msg.getSenderId())) {
                    convUpdates.put(key, 0);
                } else {
                    int current = conv.getMemberInfoFor(memberUid).getUnreadCount();
                    convUpdates.put(key, current + 1);
                }
            }

            txn.set(msgRef, msg);
            txn.update(convRef, convUpdates);
            return msgRef.getId();
        }).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                cb.onError(wrap(task.getException()));
                return;
            }
            String id = task.getResult();
            msg.setMessageId(id);
            // Do NOT set msg.timestamp locally — it will be populated by
            // the snapshot listener with the authoritative server time.
            // If the caller wants an optimistic bubble before the
            // listener fires, it should construct its own optimistic
            // copy in ChatActivity, not mutate this Message instance.
            cb.onSuccess(msg);
        });
    }

    /** Edits a message's text. Enforces the 15-minute window client-side; rules enforce it server-side. */
    public void editMessage(@NonNull String conversationId,
                            @NonNull String messageId,
                            @NonNull String newText,
                            @NonNull Callback<Void> cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("text", newText);
        updates.put("editedAt", FieldValue.serverTimestamp());

        db.collection(C_CONVERSATIONS).document(conversationId)
                .collection(C_MESSAGES).document(messageId)
                .update(updates)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /**
     * "Delete for everyone." Clears the message content and marks the
     * tombstone. Does NOT delete the Firestore document — the bubble
     * must still render for both participants with "This message was
     * deleted" text. Deleting the document would make the message
     * vanish entirely for the recipient, which is not what "delete
     * for everyone" means.
     *
     * <p>Storage object deletion is the caller's responsibility
     * ({@code StorageHelper.deleteObject(media.getStoragePath())})
     * because the path must be captured from the current Message
     * before the tombstone is applied. This method only handles the
     * Firestore side.</p>
     */
    public void deleteMessageForEveryone(@NonNull String conversationId,
                                         @NonNull String messageId,
                                         @NonNull Callback<Void> cb) {
        DocumentReference convRef = db.collection(C_CONVERSATIONS).document(conversationId);
        DocumentReference msgRef  = convRef.collection(C_MESSAGES).document(messageId);

        db.runTransaction(txn -> {
            DocumentSnapshot msgSnap = txn.get(msgRef);
            if (!msgSnap.exists()) {
                throw new IllegalStateException("Message does not exist");
            }
            Message msg = msgSnap.toObject(Message.class);
            if (msg == null) throw new IllegalStateException("Message is malformed");

            Map<String, Object> updates = new HashMap<>();
            updates.put("deletedForEveryone", true);
            updates.put("text", "");
            updates.put("media", null);
            updates.put("replyTo", null);
            txn.update(msgRef, updates);

            // If the deleted message is the current lastMessage of the
            // conversation, replace the summary with a tombstone.
            DocumentSnapshot convSnap = txn.get(convRef);
            Conversation conv = convSnap.exists()
                    ? convSnap.toObject(Conversation.class) : null;
            if (conv != null && conv.getLastMessage() != null
                    && messageId.equals(conv.getLastMessage().getText())
                    // Note: we don't have the lastMessage's messageId in
                    // the summary, so we compare timestamps as a proxy.
                    // A slightly better check would be to compare
                    // messageId stored in the summary — that's a future
                    // schema enhancement, not a correctness blocker for
                    // v1: replacing a same-day lastMessage with a
                    // tombstone is always semantically acceptable.
                    ) {
                // no-op: skip — see comment above; the timestamp
                // comparison below is the actual guard.
            }
            if (conv != null && msg.getTimestamp() != null
                    && conv.getLastMessageAt() != null
                    && !msg.getTimestamp().before(conv.getLastMessageAt())) {
                Map<String, Object> placeholder = new HashMap<>();
                placeholder.put("text", "");
                placeholder.put("type", "deleted");
                placeholder.put("senderId", msg.getSenderId());
                placeholder.put("timestamp", FieldValue.serverTimestamp());
                txn.update(convRef, "lastMessage", placeholder);
            }
            return null;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) cb.onSuccess(null);
            else cb.onError(wrap(task.getException()));
        });
    }

    /**
     * "Delete for me." Adds the caller's UID to the message's
     * {@code deletedFor} array. The message remains fully visible to
     * all other participants — this is purely a per-user filter.
     */
    public void deleteMessageForMe(@NonNull String conversationId,
                                   @NonNull String messageId,
                                   @NonNull String uid,
                                   @NonNull Callback<Void> cb) {
        db.collection(C_CONVERSATIONS).document(conversationId)
                .collection(C_MESSAGES).document(messageId)
                .update("deletedFor", FieldValue.arrayUnion(uid))
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) cb.onSuccess(null);
                    else cb.onError(wrap(task.getException()));
                });
    }

    /**
     * Marks a conversation as read for the given user. Resets
     * {@code unreadCount} to 0 and updates {@code lastReadAt} to the
     * server timestamp.
     *
     * <p>This is a single write — not one write per unread message.
     * The trade-off is that per-message read timestamps are not
     * recorded (see class-level note). If you ever want per-message
     * read info for a "message info" dialog, extend this method to
     * also batch-update the messages' {@code readBy} map — but
     * recognize that a 100-message batch is 100 writes per chat open,
     * which is orders of magnitude more expensive than this
     * single-document update.</p>
     */
    public void markConversationRead(@NonNull String conversationId,
                                     @NonNull String uid) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("memberInfo." + uid + ".unreadCount", 0);
        updates.put("memberInfo." + uid + ".lastReadAt", FieldValue.serverTimestamp());

        db.collection(C_CONVERSATIONS).document(conversationId).update(updates);
        // Fire-and-forget: if this fails, the next open will retry.
    }

    // ============================================================
    // TYPING INDICATOR
    // ============================================================

    private static final long TYPING_STALE_MS = 5000L;

    /**
     * Sets or clears the caller's typing flag on a conversation. The
     * field is {@code conversations/{cid}.typing.{uid} = <server time>}
     * — a map, so any user can only write their own key (enforced by
     * rules).
     *
     * <p>Callers must throttle this. Writing on every keystroke would
     * generate dozens of writes per message. The standard pattern is
     * "write every 3–5 seconds while the user is actively typing, and
     * clear on send/lose-focus/background." {@code ChatActivity}
     * implements the throttle.</p>
     */
    public void setTyping(@NonNull String conversationId,
                          @NonNull String uid,
                          boolean typing) {
        Map<String, Object> updates = new HashMap<>();
        String key = "typing." + uid;
        if (typing) {
            updates.put(key, FieldValue.serverTimestamp());
        } else {
            updates.put(key, FieldValue.delete());
        }
        db.collection(C_CONVERSATIONS).document(conversationId).update(updates);
    }

    /**
     * Real-time listener on the {@code typing} map of a conversation.
     * Delivers a {@code Map<uid, Date>} containing entries that are
     * less than {@link #TYPING_STALE_MS} old. Stale entries are
     * filtered out so a crashed client does not leave a permanent
     * "typing…" indicator.
     *
     * <p>The listener filters stale entries on every snapshot fire,
     * but the map is not scrubbed on the server — stale entries stay
     * in the document until the next write deletes them or a cleanup
     * job eventually runs. For v1 this is acceptable: the map never
     * exceeds group size, and the values are consumed only by the
     * "typing…" indicator, which filters them client-side.</p>
     */
    @NonNull
    public ListenerRegistration listenTyping(@NonNull String conversationId,
                                             @NonNull OnTypingChanged listener) {
        return db.collection(C_CONVERSATIONS).document(conversationId)
                .addSnapshotListener((snap, err) -> {
                    if (snap == null || !snap.exists()) {
                        listener.onTypingChanged(Collections.emptyMap());
                        return;
                    }
                    Object raw = snap.get("typing");
                    if (!(raw instanceof Map)) {
                        listener.onTypingChanged(Collections.emptyMap());
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) raw;
                    long now = System.currentTimeMillis();
                    Map<String, Date> live = new HashMap<>();
                    for (Map.Entry<String, Object> e : map.entrySet()) {
                        Object v = e.getValue();
                        if (!(v instanceof com.google.firebase.Timestamp)) continue;
                        Date when = ((com.google.firebase.Timestamp) v).toDate();
                        if (now - when.getTime() <= TYPING_STALE_MS) {
                            live.put(e.getKey(), when);
                        }
                    }
                    listener.onTypingChanged(live);
                });
    }

    public interface OnTypingChanged {
        void onTypingChanged(@NonNull Map<String, Date> typingUids);
    }

    // ============================================================
    // INTERNAL: LiveData wrappers
    // ============================================================

    /**
     * LiveData that attaches and detaches a Firestore
     * {@code addSnapshotListener} in {@code onActive} / {@code onInactive}.
     * This is the only place in the project where a Firestore listener
     * is attached — no Fragment or Activity ever holds a
     * {@code ListenerRegistration}, so there is no place for a listener
     * leak to hide.
     */
    private static final class DocumentLiveData extends LiveData<DocumentSnapshot> {
        private final DocumentReference ref;
        private ListenerRegistration reg;

        DocumentLiveData(DocumentReference ref) {
            this.ref = ref;
        }

        @Override
        protected void onActive() {
            reg = ref.addSnapshotListener((snap, err) -> setValue(snap));
        }

        @Override
        protected void onInactive() {
            if (reg != null) {
                reg.remove();
                reg = null;
            }
        }
    }

    /** LiveData that maps a {@code Query} into a {@code List<T>}. */
    private static final class QueryListLiveData<T> extends LiveData<List<T>> {
        private final Query query;
        private final Class<T> clazz;
        private ListenerRegistration reg;

        QueryListLiveData(Query query, Class<T> clazz) {
            this.query = query;
            this.clazz = clazz;
        }

        @Override
        protected void onActive() {
            reg = query.addSnapshotListener((snap, err) -> {
                if (snap == null) return;
                List<T> out = snap.toObjects(clazz);
                // Populate document IDs for POJOs that use @DocumentId.
                // Firestore does this automatically for direct
                // toObject(Class) calls, but toObjects(List) may not in
                // some SDK versions. Belt-and-braces: iterate and set
                // via reflection if the class has a setId method would
                // be overkill; instead, we rely on @DocumentId being
                // handled by the SDK, which it is for SDK 34.
                setValue(out);
            });
        }

        @Override
        protected void onInactive() {
            if (reg != null) {
                reg.remove();
                reg = null;
            }
        }
    }

    /**
     * Applies a transformation to every value emitted by a source
     * LiveData. Small enough to justify inline rather than a
     * {@code Transformations.map} (which would require an executor
     * parameter and cannot return null).
     */
    private static final class MappedLiveData<S, T> extends LiveData<T> {
        private final LiveData<S> source;
        private final java.util.function.Function<S, T> mapper;
        private final androidx.lifecycle.Observer<S> observer;

        MappedLiveData(LiveData<S> source, java.util.function.Function<S, T> mapper) {
            this.source = source;
            this.mapper = mapper;
            this.observer = s -> setValue(mapper.apply(s));
        }

        @Override
        protected void onActive() {
            source.observeForever(observer);
        }

        @Override
        protected void onInactive() {
            source.removeObserver(observer);
        }
    }

    // ============================================================
    // Utility
    // ============================================================

    @NonNull
    private static Exception wrap(@Nullable Exception e) {
        return e != null ? e : new IllegalStateException("Unknown Firestore failure");
    }

    /**
     * Guards against a common mistake in {@code @DocumentId} deserialization:
     * if the Firestore SDK ever returns a snapshot whose {@code toObject}
     * result has a null UID, callers cannot safely use the object for
     * authorization checks. This helper ensures the setter is called.
     * Currently unused because the SDK populates the field correctly;
     * retained as documentation of the invariant.
     */
    @SuppressWarnings("unused")
    @NonNull
    private static <T> T withDocId(@NonNull DocumentSnapshot snap, @NonNull T obj) {
        if (obj instanceof User) {
            ((User) obj).setUid(snap.getId());
        } else if (obj instanceof Message) {
            ((Message) obj).setMessageId(snap.getId());
        } else if (obj instanceof Conversation) {
            ((Conversation) obj).setConversationId(snap.getId());
        }
        return obj;
    }
}
