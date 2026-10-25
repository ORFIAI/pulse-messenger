package com.pulse.messenger.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.google.android.material.color.MaterialColors;
import com.pulse.messenger.R;
import com.pulse.messenger.databinding.ItemConversationBinding;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.Message;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Adapter for the chat list.
 *
 * <p><b>Data flow:</b> {@code ChatsFragment} observes a
 * {@code LiveData<List<Conversation>>} from
 * {@link com.pulse.messenger.data.FirestoreHelper} and calls
 * {@link #submitList(List)} on each emission. {@link DiffUtil} computes
 * the minimal set of row updates, so a message arriving in one
 * conversation does not force a full rebind of all rows.</p>
 *
 * <p><b>Peer resolution:</b> private conversations store only member
 * UIDs; the peer's name and photo live in their {@code User} document.
 * The fragment supplies a {@link UserResolver} that provides the
 * already-fetched {@code User} for a peer UID, or requests a fetch if
 * not present. The fragment is expected to deduplicate requests and
 * re-submit the list when the peer fetch completes.</p>
 *
 * <p><b>v1 limitations (documented, not bugs):</b></p>
 * <ul>
 *   <li>Presence dot on the chat-list avatar is intentionally not
 *       rendered. Presence is RTDB-only and this adapter reads
 *       Firestore; adding per-row RTDB subscriptions for 20+
 *       conversations would cost real battery and bandwidth for a
 *       minor visual. The peer's presence is shown in the chat header
 *       instead.</li>
 *   <li>Draft text is not rendered in the preview. Draft persistence
 *       is a v2 feature.</li>
 *   <li>Row is not animated on message arrival; only insert/remove
 *       animations are provided by RecyclerView's default item
 *       animator. Content updates (last-message text change) happen
 *       instantly without transition — matching most modern chat
 *       apps, which also do not animate this in place.</li>
 * </ul>
 *
 * <p><b>View IDs consumed from {@code item_conversation.xml}:</b></p>
 * <ul>
 *   <li>{@code iv_avatar}      — photo ImageView (hidden when no photo)</li>
 *   <li>{@code tv_initials}    — initial TextView (fallback for photo)</li>
 *   <li>{@code tv_name}        — display name</li>
 *   <li>{@code tv_preview}     — last-message preview</li>
 *   <li>{@code tv_time}        — formatted timestamp</li>
 *   <li>{@code tv_unread_badge}— unread count pill (hidden when zero)</li>
 *   <li>{@code iv_pinned}      — small pin indicator (hidden when not pinned)</li>
 *   <li>{@code iv_muted}       — small mute indicator (hidden when not muted)</li>
 * </ul>
 * The corresponding binding fields (ViewBinding camelCase) are
 * {@code binding.ivAvatar}, {@code binding.tvInitials}, etc.
 */
public class ConversationAdapter
        extends RecyclerView.Adapter<ConversationAdapter.VH> {

    // ============================================================
    // Callbacks
    // ============================================================

    /** Tap → open the chat screen. */
    public interface OnConversationClick {
        void onClick(@NonNull Conversation conversation);
    }

    /** Long-press → show the pin/mute/archive/delete context menu. */
    public interface OnConversationLongClick {
        void onLongClick(@NonNull Conversation conversation, @NonNull View anchor);
    }

    /**
     * Provides access to peer {@link User} objects for private
     * conversations. The fragment is expected to maintain a
     * {@code Map<String, User>} cache and to deduplicate
     * {@link #request(String)} calls (a peer that has already been
     * requested but not yet resolved must not trigger a second
     * network call on every bind).
     */
    public interface UserResolver {
        @Nullable User get(@NonNull String uid);
        void request(@NonNull String uid);
    }

    // ============================================================
    // State
    // ============================================================

    private final List<Conversation> items = new ArrayList<>();
    private final String myUid;
    private final UserResolver userResolver;
    private final OnConversationClick clickListener;
    private final OnConversationLongClick longClickListener;

    public ConversationAdapter(@NonNull String myUid,
                               @NonNull UserResolver userResolver,
                               @NonNull OnConversationClick clickListener,
                               @Nullable OnConversationLongClick longClickListener) {
        this.myUid = myUid;
        this.userResolver = userResolver;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    // ============================================================
    // List submission
    // ============================================================

    public void submitList(@NonNull List<Conversation> newList) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                new ConversationDiff(items, newList), true);
        items.clear();
        items.addAll(newList);
        diff.dispatchUpdatesTo(this);
    }

    // ============================================================
    // RecyclerView.Adapter
    // ============================================================

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemConversationBinding binding = ItemConversationBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        Conversation conv = items.get(position);
        Context ctx = holder.itemView.getContext();

        // ---------- Name and avatar depend on conversation type ----------
        String displayName;
        String photoUrl;
        String initialSeed;   // used for the fallback colored circle
        String initialText;

        if (conv.isGroup()) {
            displayName = conv.getName() != null && !conv.getName().trim().isEmpty()
                    ? conv.getName()
                    : ctx.getString(R.string.group_info_title);
            photoUrl = conv.getPhotoUrl();
            initialSeed = conv.getConversationId();
            initialText = UiUtils.avatarInitial(displayName);
        } else {
            String peerUid = conv.getPeerUid(myUid);
            User peer = peerUid != null ? userResolver.get(peerUid) : null;
            if (peer == null && peerUid != null) {
                // Trigger fetch — the fragment deduplicates.
                userResolver.request(peerUid);
            }
            if (peer != null) {
                displayName = peer.getDisplayLabel();
                photoUrl = peer.getPhotoUrl();
            } else {
                displayName = "";   // leave blank until resolved
                photoUrl = null;
            }
            initialSeed = peerUid != null ? peerUid : conv.getConversationId();
            initialText = peer != null ? UiUtils.avatarInitial(peer.getDisplayLabel()) : "?";
        }

        holder.binding.tvName.setText(displayName);

        // ---------- Avatar: initials underneath, photo on top ----------
        // The initials TextView is ALWAYS rendered (colored by the
        // deterministic palette). The photo ImageView is layered above
        // and, when it loads successfully, covers the initials. This
        // gives a graceful fallback during slow photo loads and on
        // load failures.
        int initialColor = UiUtils.resolveAvatarColor(ctx, initialSeed);
        holder.binding.tvInitials.setText(initialText);
        ViewCompat.setBackgroundTintList(
                holder.binding.tvInitials,
                ColorStateList.valueOf(initialColor));

        if (photoUrl != null && !photoUrl.isEmpty()) {
            holder.binding.ivAvatar.setVisibility(View.VISIBLE);
            Glide.with(holder.binding.ivAvatar)
                    .load(photoUrl)
                    .transform(new CircleCrop())
                    .into(holder.binding.ivAvatar);
        } else {
            holder.binding.ivAvatar.setVisibility(View.GONE);
        }

        // ---------- Timestamp ----------
        Date ts = conv.getLastMessageAt();
        holder.binding.tvTime.setText(ts != null
                ? UiUtils.formatChatListTime(ts)
                : "");

        // ---------- Preview ----------
        holder.binding.tvPreview.setText(buildPreview(ctx, conv));

        // ---------- Unread badge ----------
        int unread = conv.getUnreadCountFor(myUid);
        boolean muted = conv.isMutedFor(myUid);
        if (unread > 0) {
            holder.binding.tvUnreadBadge.setVisibility(View.VISIBLE);
            holder.binding.tvUnreadBadge.setText(
                    unread > 99 ? ctx.getString(R.string.conversation_you_prefix, "99+")
                               : (unread > 99 ? "99+" : String.valueOf(unread)));
            // Simplify: build the label once.
            String label = unread > 99 ? "99+" : String.valueOf(unread);
            holder.binding.tvUnreadBadge.setText(label);

            // Tint muted conversations differently so unread counts
            // are visually quieter when the user has muted them.
            int badgeColor = MaterialColors.getColor(holder.itemView,
                    muted ? com.google.android.material.R.attr.colorSurfaceVariant
                          : com.google.android.material.R.attr.colorPrimary);
            ViewCompat.setBackgroundTintList(
                    holder.binding.tvUnreadBadge, ColorStateList.valueOf(badgeColor));
            int textColor = MaterialColors.getColor(holder.itemView,
                    muted ? com.google.android.material.R.attr.colorOnSurfaceVariant
                          : com.google.android.material.R.attr.colorOnPrimary);
            holder.binding.tvUnreadBadge.setTextColor(textColor);
        } else {
            holder.binding.tvUnreadBadge.setVisibility(View.GONE);
        }

        // ---------- Pin / mute indicators ----------
        holder.binding.ivPinned.setVisibility(
                conv.isPinnedFor(myUid) ? View.VISIBLE : View.GONE);
        holder.binding.ivMuted.setVisibility(
                muted ? View.VISIBLE : View.GONE);

        // ---------- Click / long-click ----------
        holder.itemView.setOnClickListener(v ->
                clickListener.onClick(conv));

        if (longClickListener != null) {
            holder.itemView.setOnLongClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                longClickListener.onLongClick(items.get(pos), holder.itemView);
                return true;
            });
        } else {
            holder.itemView.setOnLongClickListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ============================================================
    // Preview text builder
    // ============================================================

    /**
     * Builds the preview line shown in the chat list.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>No last message → empty (the row is still tappable; the
     *       chat screen will show "No messages yet").</li>
     *   <li>Last message sent by me → prefix "You: ".</li>
     *   <li>Text message → the text.</li>
     *   <li>Media message with a caption → the caption.</li>
     *   <li>Media message without a caption → the localized type
     *       label ("📷 Photo", "🎥 Video", "🎤 Voice message", ...).</li>
     *   <li>Tombstone (deleted for everyone) → the "Message deleted"
     *       string.</li>
     * </ul>
     */
    @NonNull
    private String buildPreview(@NonNull Context ctx, @NonNull Conversation conv) {
        Conversation.LastMessage lm = conv.getLastMessage();
        if (lm == null) {
            return "";
        }

        if ("deleted".equals(lm.getType())) {
            return ctx.getString(R.string.chat_message_deleted_placeholder);
        }

        String prefix = myUid.equals(lm.getSenderId())
                ? ctx.getString(R.string.conversation_you_prefix)
                : "";

        String body = lm.getText() != null ? lm.getText().trim() : "";

        if (!body.isEmpty()) {
            return prefix + body;
        }

        // No text — use the localized media-type label.
        String typeLabel = typeLabel(ctx, lm.getType());
        if (typeLabel.isEmpty()) {
            return prefix;
        }
        return prefix + typeLabel;
    }

    @NonNull
    private String typeLabel(@NonNull Context ctx, @Nullable String type) {
        if (type == null) return "";
        switch (type) {
            case Message.TYPE_IMAGE:    return ctx.getString(R.string.conversation_type_image);
            case Message.TYPE_VIDEO:    return ctx.getString(R.string.conversation_type_video);
            case Message.TYPE_AUDIO:    return ctx.getString(R.string.conversation_type_audio);
            case Message.TYPE_VOICE:    return ctx.getString(R.string.conversation_type_voice);
            case Message.TYPE_DOCUMENT: return ctx.getString(R.string.conversation_type_document);
            case Message.TYPE_TEXT:     return "";
            default:                    return "";
        }
    }

    // ============================================================
    // ViewHolder
    // ============================================================

    static final class VH extends RecyclerView.ViewHolder {
        final ItemConversationBinding binding;

        VH(@NonNull ItemConversationBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    // ============================================================
    // DiffUtil
    // ============================================================

    /**
     * Compares conversations by the fields that affect rendering only.
     * UIDs, createdAt, and members are not compared because changing
     * them does not change what the row displays — {@code lastMessage},
     * {@code lastMessageAt}, the per-user memberInfo, and (for groups)
     * the name/photo are the only fields that can produce a visual
     * difference.
     *
     * <p>Per-user fields (unread count, muted, pinned, archived) are
     * compared against the current user's entry only. Another user's
     * memberInfo change does not affect this row.</p>
     */
    private final class ConversationDiff extends DiffUtil.Callback {
        private final List<Conversation> oldList;
        private final List<Conversation> newList;

        ConversationDiff(@NonNull List<Conversation> oldList,
                         @NonNull List<Conversation> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return Objects.equals(
                    oldList.get(oldPos).getConversationId(),
                    newList.get(newPos).getConversationId());
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Conversation a = oldList.get(oldPos);
            Conversation b = newList.get(newPos);
            if (!Objects.equals(a.getName(), b.getName())) return false;
            if (!Objects.equals(a.getPhotoUrl(), b.getPhotoUrl())) return false;
            if (!Objects.equals(a.getLastMessageAt(), b.getLastMessageAt())) return false;

            Conversation.LastMessage la = a.getLastMessage();
            Conversation.LastMessage lb = b.getLastMessage();
            if (la == null && lb != null) return false;
            if (la != null && lb == null) return false;
            if (la != null) {
                if (!Objects.equals(la.getText(), lb.getText())) return false;
                if (!Objects.equals(la.getType(), lb.getType())) return false;
                if (!Objects.equals(la.getSenderId(), lb.getSenderId())) return false;
            }

            Conversation.MemberInfo ma = a.getMemberInfoFor(myUid);
            Conversation.MemberInfo mb = b.getMemberInfoFor(myUid);
            if (ma.getUnreadCount() != mb.getUnreadCount()) return false;
            if (ma.isPinned() != mb.isPinned()) return false;
            if (ma.isMuted() != mb.isMuted()) return false;
            if (ma.isArchived() != mb.isArchived()) return false;
            return true;
        }
    }
}
