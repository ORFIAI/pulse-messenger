package com.pulse.messenger.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Firestore POJO for a conversation document.
 *
 * <p><b>Storage location:</b> {@code conversations/{conversationId}}.
 * Messages live in the {@code messages} subcollection and are NEVER
 * loaded to render the chat list — the summary fields here are the
 * entire reason the chat list is a single query instead of N+1.</p>
 *
 * <p><b>Two conversation types:</b> {@link #TYPE_PRIVATE} (two
 * members) and {@link #TYPE_GROUP} (two or more members, with
 * {@link #admins}). See the Phase-1 design notes for details.</p>
 *
 * <p><b>Typing map (Phase 8):</b> {@link #typing} is a
 * {@code uid → last-keystroke-timestamp} map written by
 * {@code FirestoreHelper.setTyping(conversationId, uid, boolean)} as
 * {@code conversations/{cid}.typing.{uid} = serverTimestamp} or
 * {@code FieldValue.delete()} on stop. The map is populated by
 * Firestore's automatic POJO deserializer on every snapshot, so the
 * in-memory {@link Conversation} always reflects the live typing
 * state. Consumers must filter by freshness via
 * {@link #getTypingExcluding(String, long)} — stale entries are not
 * automatically scrubbed server-side (a crashed client cannot clean
 * up after itself), so the caller's fresh-window filter is what
 * prevents a permanent "typing…" indicator.</p>
 *
 * <p><b>Field-name linkage (do not rename):</b> the {@link #typing}
 * field name must match the {@code "typing"} key used by
 * {@code FirestoreHelper.setTyping}'s dotted update path. Renaming
 * the field would break deserialization silently — {@code setTyping}
 * writes to {@code typing.uid}, but a differently-named POJO field
 * would never populate. If a future refactor needs a different
 * name, change both the POJO field and the {@code setTyping} write
 * path in the same commit.</p>
 */
public class Conversation {

    // ============================================================
    // Type constants
    // ============================================================

    public static final String TYPE_PRIVATE = "private";
    public static final String TYPE_GROUP   = "group";

    // ============================================================
    // Core fields
    // ============================================================

    /** Firestore document ID. Populated by {@link DocumentId}, skipped on write. */
    @DocumentId
    private String conversationId;

    /** One of {@link #TYPE_PRIVATE} or {@link #TYPE_GROUP}. Immutable after creation. */
    private String type;

    /**
     * Group display name. Null for {@link #TYPE_PRIVATE} (UI derives
     * the peer's name from their {@link User} document). Required for
     * {@link #TYPE_GROUP}.
     */
    private String name;

    /**
     * Group photo URL. Null for {@link #TYPE_PRIVATE}, null OR
     * populated for {@link #TYPE_GROUP}.
     */
    private String photoUrl;

    /** UID of the user who created the conversation. */
    private String createdBy;

    /** Server timestamp of conversation creation. Immutable after write. */
    @ServerTimestamp
    private Date createdAt;

    /** UIDs of all current members. */
    private List<String> members;

    /** UIDs of group admins. Empty for {@link #TYPE_PRIVATE}. */
    private List<String> admins;

    /** Denormalized summary of the most recent message. */
    private LastMessage lastMessage;

    /** Server timestamp of the most recent message. Sort key for the chat list. */
    @ServerTimestamp
    private Date lastMessageAt;

    /**
     * Per-user chat metadata. Key is the UID, value is a
     * {@link MemberInfo}. The map (not a subcollection) is what keeps
     * the chat-list query at 1 read per conversation.
     */
    private Map<String, MemberInfo> memberInfo;

    /**
     * Live typing state: {@code uid → server timestamp of the most
     * recent keystroke}. Written by
     * {@code FirestoreHelper.setTyping(conversationId, uid, boolean)}
     * on the {@code typing.{uid}} dotted path, so only the calling
     * user's own key can be set (Firestore rules enforce this — see
     * the Phase-1 security design notes).
     *
     * <p>May be null when no one has typed in this conversation since
     * the last stop. Entries are NEVER removed automatically by the
     * server after a crash or disconnect; consumers must filter by
     * freshness via {@link #getTypingExcluding(String, long)}.</p>
     *
     * <p><b>Do NOT use {@link ServerTimestamp} on this field.</b> It
     * is a map of per-key server timestamps written via dotted
     * updates — not a single server-timestamped field. The annotation
     * is only meaningful on individual {@link Date} fields, not on
     * maps.</p>
     */
    private Map<String, Date> typing;

    // ============================================================
    // Constructors
    // ============================================================

    /** Required by Firestore's deserializer. */
    public Conversation() {
    }

    // ============================================================
    // Static factories
    // ============================================================

    /**
     * Creates a private conversation between two users. Members is a
     * 2-element list; admins is empty; name/photoUrl are null.
     *
     * <p>Creation is only ever done through
     * {@code FirestoreHelper.findOrCreatePrivateConversation(a, b)},
     * which uses the deterministic ID {@code p_<min>_<max>} to
     * eliminate the duplicate-private-chat race. This factory does
     * not deduplicate — callers must go through the helper.</p>
     */
    @NonNull
    public static Conversation newPrivate(@NonNull String createdBy,
                                          @NonNull String peerUid) {
        Conversation c = new Conversation();
        c.type = TYPE_PRIVATE;
        c.createdBy = createdBy;
        c.members = java.util.Arrays.asList(createdBy, peerUid);
        c.admins = Collections.emptyList();
        c.memberInfo = new HashMap<>();
        c.memberInfo.put(createdBy, MemberInfo.fresh());
        c.memberInfo.put(peerUid, MemberInfo.fresh());
        return c;
    }

    /**
     * Creates a group conversation. The creator is added to admins
     * automatically — there is no way to create a group with no
     * admins, which avoids an orphaned-group state.
     */
    @NonNull
    public static Conversation newGroup(@NonNull String createdBy,
                                        @NonNull String name,
                                        @NonNull List<String> memberUids) {
        if (!memberUids.contains(createdBy)) {
            throw new IllegalArgumentException("Creator must be in the members list");
        }
        Conversation c = new Conversation();
        c.type = TYPE_GROUP;
        c.createdBy = createdBy;
        c.name = name;
        c.members = new java.util.ArrayList<>(memberUids);
        c.admins = new java.util.ArrayList<>();
        c.admins.add(createdBy);
        c.memberInfo = new HashMap<>();
        for (String uid : memberUids) {
            c.memberInfo.put(uid, MemberInfo.fresh());
        }
        return c;
    }

    // ============================================================
    // Getters / setters
    // ============================================================

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
    }

    public List<String> getMembers() {
        return members;
    }

    public void setMembers(List<String> members) {
        this.members = members;
    }

    public List<String> getAdmins() {
        return admins;
    }

    public void setAdmins(List<String> admins) {
        this.admins = admins;
    }

    public LastMessage getLastMessage() {
        return lastMessage;
    }

    public void setLastMessage(LastMessage lastMessage) {
        this.lastMessage = lastMessage;
    }

    public Date getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Date lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }

    public Map<String, MemberInfo> getMemberInfo() {
        return memberInfo;
    }

    public void setMemberInfo(Map<String, MemberInfo> memberInfo) {
        this.memberInfo = memberInfo;
    }

    /**
     * Returns the raw typing map (may be null). Most callers should
     * use {@link #getTypingExcluding(String, long)} instead, which
     * filters by freshness and excludes the current user.
     */
    @Nullable
    public Map<String, Date> getTyping() {
        return typing;
    }

    public void setTyping(Map<String, Date> typing) {
        this.typing = typing;
    }

    // ============================================================
    // Type predicates
    // ============================================================

    public boolean isGroup() {
        return TYPE_GROUP.equals(type);
    }

    public boolean isPrivate() {
        return TYPE_PRIVATE.equals(type);
    }

    // ============================================================
    // Membership / admin predicates (null-safe)
    // ============================================================

    public boolean hasMember(@Nullable String uid) {
        return uid != null && members != null && members.contains(uid);
    }

    public boolean isAdmin(@Nullable String uid) {
        return uid != null && admins != null && admins.contains(uid);
    }

    public int getMemberCount() {
        return members == null ? 0 : members.size();
    }

    // ============================================================
    // Per-user metadata (null-safe)
    // ============================================================

    @NonNull
    public MemberInfo getMemberInfoFor(@Nullable String uid) {
        if (uid == null || memberInfo == null) {
            return MemberInfo.fresh();
        }
        MemberInfo info = memberInfo.get(uid);
        return info != null ? info : MemberInfo.fresh();
    }

    public int getUnreadCountFor(@Nullable String uid) {
        return getMemberInfoFor(uid).getUnreadCount();
    }

    public boolean isMutedFor(@Nullable String uid) {
        return getMemberInfoFor(uid).isMuted();
    }

    public boolean isPinnedFor(@Nullable String uid) {
        return getMemberInfoFor(uid).isPinned();
    }

    public boolean isArchivedFor(@Nullable String uid) {
        return getMemberInfoFor(uid).isArchived();
    }

    @Nullable
    public String getPeerUid(@Nullable String viewerUid) {
        if (!isPrivate() || members == null || viewerUid == null) {
            return null;
        }
        for (String m : members) {
            if (!viewerUid.equals(m)) {
                return m;
            }
        }
        return null;
    }

    // ============================================================
    // Typing helpers (Phase 8)
    // ============================================================

    /**
     * Returns the subset of the typing map that is both fresh (within
     * {@code staleMs} of the current time) and not the excluded
     * user's own entry. Never returns null.
     *
     * <p>Use this when rendering "X is typing…" indicators. The
     * {@code excludeUid} parameter is the current user's UID — the
     * caller does not want to see "you are typing…" in their own chat
     * header.</p>
     *
     * <p><b>Freshness filter is required, not optional.</b> A user
     * who crashes mid-typing leaves a stale entry in Firestore that
     * will remain there until either (a) they come back and start
     * typing again (overwriting the timestamp), or (b) they stop
     * typing cleanly (removing the entry). A crashed client cannot
     * clean up after itself, so the client-side freshness filter is
     * the only mechanism that prevents a permanently-stuck
     * "typing…" indicator. The staleMs parameter should typically be
     * {@code 5000} (matching the throttle interval used by
     * {@code FirestoreHelper.setTyping}) — long enough to bridge the
     * gap between two consecutive keystrokes, short enough that a
     * crash does not leave the indicator stuck for minutes.</p>
     *
     * @param excludeUid the current user's UID (excluded from the
     *                   result). Null is treated as "exclude nothing."
     * @param staleMs    the maximum age of a typing entry that still
     *                   counts as "live." Non-positive values are
     *                   treated as zero (i.e., no entry is fresh).
     */
    @NonNull
    public Map<String, Date> getTypingExcluding(@Nullable String excludeUid,
                                                 long staleMs) {
        if (typing == null || typing.isEmpty() || staleMs <= 0) {
            return Collections.emptyMap();
        }
        long now = System.currentTimeMillis();
        Map<String, Date> out = new HashMap<>();
        for (Map.Entry<String, Date> e : typing.entrySet()) {
            String uid = e.getKey();
            Date at = e.getValue();
            if (uid == null || at == null) continue;
            if (excludeUid != null && excludeUid.equals(uid)) continue;
            long age = now - at.getTime();
            if (age >= 0 && age <= staleMs) {
                out.put(uid, at);
            }
        }
        return out;
    }

    /**
     * Convenience: {@code true} if at least one other user is
     * currently typing (per the freshness window). Equivalent to
     * {@code !getTypingExcluding(excludeUid, staleMs).isEmpty()} —
     * provided so call sites can express "should we show the typing
     * indicator?" without an intermediate map allocation.
     */
    public boolean hasLiveTyping(@Nullable String excludeUid, long staleMs) {
        if (typing == null || typing.isEmpty() || staleMs <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Date> e : typing.entrySet()) {
            String uid = e.getKey();
            Date at = e.getValue();
            if (uid == null || at == null) continue;
            if (excludeUid != null && excludeUid.equals(uid)) continue;
            long age = now - at.getTime();
            if (age >= 0 && age <= staleMs) {
                return true;
            }
        }
        return false;
    }

    // ============================================================
    // Nested: LastMessage summary
    // ============================================================

    /**
     * Denormalized summary of the most recent message. Every field
     * here is a copy of the corresponding field from the message
     * document, taken at the moment of the last-message transaction.
     */
    public static class LastMessage {

        private String text;
        private String type;
        private String senderId;
        @ServerTimestamp
        private Date timestamp;

        public LastMessage() {
        }

        @NonNull
        public static LastMessage from(@NonNull Message msg) {
            LastMessage lm = new LastMessage();
            lm.senderId = msg.getSenderId();
            lm.type = msg.getType();
            lm.timestamp = msg.getTimestamp();
            String preview = msg.getPreviewText();
            if (preview.length() > 200) {
                preview = preview.substring(0, 200);
            }
            lm.text = preview;
            return lm;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getSenderId() {
            return senderId;
        }

        public void setSenderId(String senderId) {
            this.senderId = senderId;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }
    }

    // ============================================================
    // Nested: per-user chat metadata
    // ============================================================

    /**
     * Per-user chat metadata. One of these exists for every member.
     * Written only by the owning user (Firestore rules enforce that
     * the outer document update can only touch the caller's entry in
     * {@code memberInfo}).
     */
    public static class MemberInfo {

        private int unreadCount;
        private long mutedUntil;
        private boolean pinned;
        private boolean archived;
        @ServerTimestamp
        private Date lastReadAt;

        public MemberInfo() {
        }

        @NonNull
        public static MemberInfo fresh() {
            MemberInfo mi = new MemberInfo();
            mi.unreadCount = 0;
            mi.mutedUntil = 0L;
            mi.pinned = false;
            mi.archived = false;
            mi.lastReadAt = null;
            return mi;
        }

        public int getUnreadCount() {
            return unreadCount;
        }

        public void setUnreadCount(int unreadCount) {
            this.unreadCount = unreadCount;
        }

        public long getMutedUntil() {
            return mutedUntil;
        }

        public void setMutedUntil(long mutedUntil) {
            this.mutedUntil = mutedUntil;
        }

        public boolean isMuted() {
            return mutedUntil > System.currentTimeMillis();
        }

        public boolean isPinned() {
            return pinned;
        }

        public void setPinned(boolean pinned) {
            this.pinned = pinned;
        }

        public boolean isArchived() {
            return archived;
        }

        public void setArchived(boolean archived) {
            this.archived = archived;
        }

        public Date getLastReadAt() {
            return lastReadAt;
        }

        public void setLastReadAt(Date lastReadAt) {
            this.lastReadAt = lastReadAt;
        }
    }

    // ============================================================
    // Identity
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Conversation)) return false;
        Conversation other = (Conversation) o;
        return Objects.equals(conversationId, other.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(conversationId);
    }

    @NonNull
    @Override
    public String toString() {
        return "Conversation{id='" + conversationId + "', type='" + type + "', members=" + getMemberCount() + "}";
    }
}
