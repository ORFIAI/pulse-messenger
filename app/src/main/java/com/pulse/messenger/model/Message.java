package com.pulse.messenger.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Firestore POJO for a message document.
 *
 * <p><b>Storage location:</b>
 * {@code conversations/{conversationId}/messages/{messageId}}.</p>
 *
 * <p><b>Why all message types share one POJO:</b> the message list is
 * rendered by a single {@code RecyclerView}, sorted by one timestamp,
 * paginated by one cursor. Splitting text/image/video/audio/document/voice
 * into separate POJOs would mean six documents per conversation collection
 * (ambiguous on pagination), six parallel queries on every page load, and
 * six adapters. The single-schema approach with a {@code type} discriminator
 * and a nullable {@code media} sub-object is how WhatsApp, Telegram, and
 * Signal actually store messages — it is not a shortcut.</p>
 *
 * <p><b>Fields with real purposes only:</b> nothing here is speculative.
 * Every field is read somewhere — either by an adapter, a Firestore
 * query, or a security rule. If a field is added later, that test must
 * still pass.</p>
 */
public class Message {

    // ============================================================
    // Type constants
    // ============================================================

    public static final String TYPE_TEXT     = "text";
    public static final String TYPE_IMAGE    = "image";
    public static final String TYPE_VIDEO    = "video";
    public static final String TYPE_AUDIO    = "audio";
    public static final String TYPE_DOCUMENT = "document";
    public static final String TYPE_VOICE    = "voice";

    // ============================================================
    // Core fields
    // ============================================================

    /**
     * Firestore document ID. Populated by {@link DocumentId} on read,
     * skipped on write. Used by pagination cursors, reply-target
     * lookups, and delete operations.
     */
    @DocumentId
    private String messageId;

    /**
     * Firebase UID of the sender. Firestore rules enforce that this
     * matches {@code request.auth.uid} on create — the client cannot
     * forge another user's message.
     */
    private String senderId;

    /** Discriminator. One of the {@code TYPE_*} constants above. */
    private String type;

    /**
     * Text content for {@code TYPE_TEXT} messages, or a caption for
     * media messages. Null when the message is media-only with no
     * caption. May be empty (but not null) on a text message the user
     * sent as whitespace — normalization at write time trims it.
     */
    private String text;

    /**
     * Media metadata for non-text messages. Null for
     * {@code TYPE_TEXT}. Required for every media type.
     */
    private Media media;

    /**
     * Server timestamp of the message send. Immutable after write.
     * Used as the pagination cursor and as the sort key for the
     * message list.
     */
    @ServerTimestamp
    private Date timestamp;

    /**
     * Reply context. Null when the message is not a reply. See
     * {@link ReplyTo}.
     */
    private ReplyTo replyTo;

    /**
     * Set by the sender via an edit. Null for an unedited message.
     * Bounded to {@link #EDIT_WINDOW_MS} after {@link #timestamp} by
     * Firestore rules — this field cannot be set on a message older
     * than the window.
     */
    private Date editedAt;

    /**
     * Set to {@code true} when the sender "deletes for everyone."
     * Once true, this flag is immutable (rules forbid unset). The UI
     * renders a tombstone ("This message was deleted") instead of the
     * content. The original {@code text} / {@code media} fields are
     * cleared at the same time as setting this flag, so a deleted
     * message leaves no recoverable content in Firestore.
     */
    private boolean deletedForEveryone;

    /**
     * UIDs that have "deleted for me" this message. Purely a client
     * filter — the document remains readable to those users via the
     * security rules (they are still conversation members), but their
     * adapters skip the row. Absent/null is treated as "no per-user
     * deletes."
     */
    private List<String> deletedFor;

    /**
     * Read receipts — {@code uid → server timestamp of when that
     * recipient's client acknowledged the message}. Sender is NOT
     * included (senders have implicitly "read" their own message).
     * Written by recipients, never by the sender. Bounded size: for
     * a private chat this is exactly one entry; for a large group it
     * is one entry per member that has opened the conversation.
     *
     * <p>Rules for why this is a map instead of an array: we need
     * per-uid timestamps for "message info" dialogs later (showing
     * when each member read), and a map is the only structure that
     * supports per-key updates without read-modify-write races.</p>
     */
    private Map<String, Date> readBy;

    // ============================================================
    // Constants
    // ============================================================

    /**
     * How long after the original send a message can still be edited.
     * Enforced by Firestore rules — an attempt to edit outside this
     * window is rejected at the backend, not just in the UI. This is
     * a deliberate policy: unlimited edits let a sender retroactively
     * change a conversation's meaning, and unbounded edits make
     * "message info" impossible to display coherently.
     */
    public static final long EDIT_WINDOW_MS = 15L * 60L * 1000L;

    // ============================================================
    // Constructors
    // ============================================================

    /** Required by Firestore's deserializer. Do not remove. */
    public Message() {
    }

    // ============================================================
    // Static factories
    // ============================================================

    /**
     * Creates a text message ready to write.
     *
     * <p>Note that {@link #timestamp}, {@link #messageId}, and
     * {@link #readBy} are left unset here. Firestore populates the
     * timestamp server-side on the write; the message ID is assigned
     * by Firestore on {@code add()} (and read back into the returned
     * reference); read receipts are written by recipients, not by the
     * sender. The optimistic-send path will locally assign a temp ID
     * in the UI layer, not here.</p>
     */
    @NonNull
    public static Message newText(@NonNull String senderId,
                                  @NonNull String text,
                                  @Nullable ReplyTo replyTo) {
        Message m = new Message();
        m.senderId = senderId;
        m.type = TYPE_TEXT;
        m.text = text;
        m.replyTo = replyTo;
        return m;
    }

    /**
     * Creates a media message (image, video, audio, document, or
     * voice). The caption field is optional — pass {@code null} or
     * empty for media-only sends.
     *
     * <p>The {@code media} argument must be fully populated by
     * {@code StorageHelper} before this factory is called — the
     * Firestore document must never be written before the upload
     * finishes, or a reader could see a message pointing at a
     * nonexistent Storage object.</p>
     */
    @NonNull
    public static Message newMedia(@NonNull String senderId,
                                   @NonNull String type,
                                   @NonNull Media media,
                                   @Nullable String caption,
                                   @Nullable ReplyTo replyTo) {
        if (!isMediaType(type)) {
            throw new IllegalArgumentException("Not a media type: " + type);
        }
        Message m = new Message();
        m.senderId = senderId;
        m.type = type;
        m.media = media;
        m.text = caption;
        m.replyTo = replyTo;
        return m;
    }

    public static boolean isMediaType(@Nullable String type) {
        return TYPE_IMAGE.equals(type)
                || TYPE_VIDEO.equals(type)
                || TYPE_AUDIO.equals(type)
                || TYPE_DOCUMENT.equals(type)
                || TYPE_VOICE.equals(type);
    }

    // ============================================================
    // Getters / setters
    // ============================================================

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Media getMedia() {
        return media;
    }

    public void setMedia(Media media) {
        this.media = media;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public ReplyTo getReplyTo() {
        return replyTo;
    }

    public void setReplyTo(ReplyTo replyTo) {
        this.replyTo = replyTo;
    }

    public Date getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Date editedAt) {
        this.editedAt = editedAt;
    }

    public boolean isDeletedForEveryone() {
        return deletedForEveryone;
    }

    public void setDeletedForEveryone(boolean deletedForEveryone) {
        this.deletedForEveryone = deletedForEveryone;
    }

    public List<String> getDeletedFor() {
        return deletedFor;
    }

    public void setDeletedFor(List<String> deletedFor) {
        this.deletedFor = deletedFor;
    }

    public Map<String, Date> getReadBy() {
        return readBy;
    }

    public void setReadBy(Map<String, Date> readBy) {
        this.readBy = readBy;
    }

    // ============================================================
    // Convenience predicates
    // ============================================================

    /**
     * {@code true} if the message has been "deleted for everyone."
     * The sender's own bubble also shows a tombstone, so this is
     * not conditional on the viewing user.
     */
    public boolean isTombstone() {
        return deletedForEveryone;
    }

    /**
     * {@code true} if the given user has "deleted for me" this
     * message. Null-safe.
     */
    public boolean isDeletedFor(@Nullable String uid) {
        return uid != null && deletedFor != null && deletedFor.contains(uid);
    }

    /**
     * {@code true} if the given user has read this message. The
     * sender is never in {@code readBy}, so this returns {@code false}
     * when {@code uid} equals {@link #senderId}.
     */
    public boolean isReadBy(@Nullable String uid) {
        return uid != null && readBy != null && readBy.containsKey(uid);
    }

    /**
     * Number of recipients who have read this message. Used to render
     * "seen by N" indicators in groups. Returns 0 for private
     * conversations until the peer opens the chat, and 1 after.
     */
    public int getReadCount() {
        return readBy == null ? 0 : readBy.size();
    }

    /**
     * Can this message still be edited? Both a time-window check and
     * a "not deleted" check. {@code now} is passed in (instead of
     * using {@code System.currentTimeMillis()}) so tests can
     * simulate time travel.
     *
     * <p>The Firestore rules enforce the same window against the
     * server clock — this client method is only used to decide whether
     * to show the "Edit" action in the UI. The rules are the actual
     * authority.</p>
     */
    public boolean isEditableBy(@Nullable String uid, long now) {
        if (uid == null || !uid.equals(senderId)) return false;
        if (deletedForEveryone) return false;
        if (timestamp == null) return false;
        return (now - timestamp.getTime()) <= EDIT_WINDOW_MS;
    }

    /**
     * Best-effort single-line preview of the message body, suitable
     * for the conversation list and for notifications. Returns an
     * empty string for a message that is a tombstone, per-user
     * deleted, or has no content. Never returns {@code null}.
     */
    @NonNull
    public String getPreviewText() {
        if (deletedForEveryone) return "";
        if (type == null) return "";
        if (TYPE_TEXT.equals(type)) {
            return text == null ? "" : text;
        }
        // Media types — the chat list will prepend a localized label
        // (see Conversation.getLastMessagePreview()); the adapter only
        // shows the caption if present, and falls back to empty so the
        // caller can substitute a per-type string.
        return text == null ? "" : text;
    }

    // ============================================================
    // Nested: Media
    // ============================================================

    /**
     * Metadata for a media message. The binary itself is in Firebase
     * Cloud Storage; only these fields live in Firestore.
     *
     * <p>All fields are populated by {@code StorageHelper} on upload
     * and are immutable afterwards. A media message is never "edited"
     * in the caption sense — that goes through {@link Message#text}.
     * If a user wants to change the image itself, they delete and
     * resend; the storage object is not overwritten.</p>
     */
    public static class Media {

        /**
         * Path within the Storage bucket, e.g.
         * {@code conversations/abc123/images/mid456_photo.jpg}.
         * Stored so the sender's client can delete the object when
         * the message is deleted for everyone. Never used to serve
         * the media to readers — for that, use {@link #downloadUrl}.
         */
        private String storagePath;

        /**
         * Long-lived download URL from Storage. Cached by Glide on
         * first use; the URL itself is stable and does not need
         * to be refreshed.
         */
        private String downloadUrl;

        /** MIME type, e.g. {@code image/jpeg}, {@code application/pdf}. */
        private String mimeType;

        /** Original file name as selected by the user. May be empty for camera captures. */
        private String fileName;

        /** Size in bytes. Displayed in the file bubble for documents. */
        private long sizeBytes;

        /** Pixel dimensions for image and video. Zero when unknown. */
        private int width;
        private int height;

        /**
         * Duration in milliseconds for audio, voice, and video.
         * Zero for images and documents.
         */
        private long durationMs;

        /**
         * Thumbnail download URL for video and (optionally) large
         * images. Populated at upload time by {@code MediaUtils}.
         * Null when no thumbnail was generated (e.g., documents,
         * audio without album art).
         */
        private String thumbnailUrl;

        public Media() {
        }

        public String getStoragePath() {
            return storagePath;
        }

        public void setStoragePath(String storagePath) {
            this.storagePath = storagePath;
        }

        public String getDownloadUrl() {
            return downloadUrl;
        }

        public void setDownloadUrl(String downloadUrl) {
            this.downloadUrl = downloadUrl;
        }

        public String getMimeType() {
            return mimeType;
        }

        public void setMimeType(String mimeType) {
            this.mimeType = mimeType;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
        }

        /** {@code true} for {@code image/*} MIME types. */
        public boolean isImage() {
            return mimeType != null && mimeType.startsWith("image/");
        }

        /** {@code true} for {@code video/*} MIME types. */
        public boolean isVideo() {
            return mimeType != null && mimeType.startsWith("video/");
        }

        /** {@code true} for {@code audio/*} MIME types. */
        public boolean isAudio() {
            return mimeType != null && mimeType.startsWith("audio/");
        }

        /** Best-effort display name — file name if present, else "file". */
        @NonNull
        public String getDisplayName() {
            if (fileName != null && !fileName.trim().isEmpty()) return fileName;
            return "file";
        }
    }

    // ============================================================
    // Nested: ReplyTo
    // ============================================================

    /**
     * Denormalized snapshot of the message being replied to. Written
     * at the time the reply is sent so the preview strip renders
     * even if the original is later edited, deleted, or hard-removed
     * by retention.
     *
     * <p>Only fields needed to render the preview strip are copied
     * here. The full original message remains queryable via
     * {@link #messageId} for the "scroll to original" feature; if the
     * original has been deleted for everyone by the time the user
     * taps the preview, the ChatActivity falls back to a Snackbar
     * instead of scrolling.</p>
     */
    public static class ReplyTo {

        /** Document ID of the original message. */
        private String messageId;

        /** UID of the original sender. Used to render the "Replying to X" label. */
        private String senderId;

        /** Type of the original — lets the strip choose the right icon. */
        private String type;

        /**
         * Pre-truncated text for the strip (first ~80 characters of
         * the original text, or the caption for media). Null when the
         * original had no text.
         */
        private String previewText;

        /**
         * Thumbnail URL for media replies. Null for text replies.
         * Populated at reply time from the original message's
         * {@link Media#thumbnailUrl}.
         */
        private String thumbnailUrl;

        public ReplyTo() {
        }

        /**
         * Factory that snapshots the original message into a reply
         * context. Called by the ChatActivity when the user taps
         * "Reply" and then sends — the snapshot is taken from the
         * message's current in-memory state, which is the same state
         * the Firestore document holds.
         */
        @NonNull
        public static ReplyTo from(@NonNull Message original) {
            ReplyTo r = new ReplyTo();
            r.messageId = original.getMessageId();
            r.senderId = original.getSenderId();
            r.type = original.getType();
            r.previewText = truncateForPreview(original.getPreviewText());
            if (original.getMedia() != null) {
                r.thumbnailUrl = original.getMedia().getThumbnailUrl();
            }
            return r;
        }

        private static String truncateForPreview(@Nullable String text) {
            if (text == null) return null;
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return null;
            if (trimmed.length() <= 80) return trimmed;
            return trimmed.substring(0, 77) + "...";
        }

        public String getMessageId() {
            return messageId;
        }

        public void setMessageId(String messageId) {
            this.messageId = messageId;
        }

        public String getSenderId() {
            return senderId;
        }

        public void setSenderId(String senderId) {
            this.senderId = senderId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPreviewText() {
            return previewText;
        }

        public void setPreviewText(String previewText) {
            this.previewText = previewText;
        }

        public String getThumbnailUrl() {
            return thumbnailUrl;
        }

        public void setThumbnailUrl(String thumbnailUrl) {
            this.thumbnailUrl = thumbnailUrl;
        }
    }

    // ============================================================
    // Read-receipt helpers (called by FirestoreHelper)
    // ============================================================

    /**
     * Returns a new {@code readBy} map with {@code uid} added at the
     * supplied timestamp. Does not mutate the current map — the
     * caller assigns the result back via {@link #setReadBy(Map)}.
     *
     * <p>This is a convenience for the in-memory update path. The
     * Firestore write uses a {@code FieldValue.serverTimestamp()}
     * update on the specific map key, so the value stored server-side
     * is authoritative regardless of what this helper sets locally.</p>
     */
    @NonNull
    public Map<String, Date> readByWith(@NonNull String uid, @NonNull Date at) {
        Map<String, Date> copy = readBy == null ? new HashMap<>() : new HashMap<>(readBy);
        copy.put(uid, at);
        return copy;
    }

    /**
     * Returns an unmodifiable view of {@code deletedFor} for UI
     * filtering. Returns an empty list (not null) when the field is
     * absent, so callers never need a null check.
     */
    @NonNull
    public List<String> getDeletedForSafe() {
        return deletedFor == null ? Collections.emptyList() : deletedFor;
    }

    /** Initializes an empty {@code readBy} map (used by the first reader to write). */
    public void ensureReadByMap() {
        if (readBy == null) {
            readBy = new HashMap<>();
        }
    }

    /** Initializes an empty {@code deletedFor} list (used by "delete for me"). */
    public void ensureDeletedForList() {
        if (deletedFor == null) {
            deletedFor = new ArrayList<>();
        }
    }

    // ============================================================
    // Identity
    // ============================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Message)) return false;
        Message other = (Message) o;
        return Objects.equals(messageId, other.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(messageId);
    }

    @NonNull
    @Override
    public String toString() {
        // Deliberately omits text content — messages can contain
        // anything, including content a user would not want in a
        // shared crash log. Only the type and ID are logged.
        return "Message{id='" + messageId + "', type='" + type + "', sender='" + senderId + "'}";
    }
}
