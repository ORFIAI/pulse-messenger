package com.pulse.messenger.adapter;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.imageview.ShapeableImageView;
import com.pulse.messenger.R;
import com.pulse.messenger.databinding.ItemAudioPreviewBinding;
import com.pulse.messenger.databinding.ItemMessageReceivedBinding;
import com.pulse.messenger.databinding.ItemMessageSentBinding;
import com.pulse.messenger.model.Message;
import com.pulse.messenger.ui.MediaViewerActivity;
import com.pulse.messenger.util.UiUtils;
import com.pulse.messenger.util.VoicePlayer;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Adapter for the conversation screen's message list.
 *
 * <p><b>Media binding (Phase 8):</b> {@link #bindMediaContainer}
 * branches on {@code message.type}:
 * <ul>
 *   <li>{@link Message#TYPE_IMAGE} → the existing {@link
 *       ShapeableImageView} thumbnail path (unchanged since Phase 7).</li>
 *   <li>{@link Message#TYPE_AUDIO}, {@link Message#TYPE_VOICE} → the
 *       bubble's {@code fl_media} container hosts an inflated {@code
 *       item_audio_preview.xml} card with a play/pause button,
 *       progress bar, and time labels driven by {@link VoicePlayer}.</li>
 *   <li>Any other media type falls through to the text path (caption
 *       only; no preview). Phase 9 adds video/document previews.</li>
 * </ul>
 *
 * <p><b>VoicePlayer + recycled-row safety:</b> every audio callback
 * is guarded by {@link #isCurrent(RecyclerView.ViewHolder, Message)},
 * which checks both {@code holder.getBindingAdapterPosition()} and the
 * message ID at that position. This prevents the specific bug where a
 * row is recycled to a different message while a callback from the
 * previous message is still queued — without the guard, the recycled
 * row would briefly show the wrong playback state. It also
 * prevents "position drift" when the user taps play on one message
 * and the row is subsequently scrolled off-screen; the callbacks
 * resume updating the same views once the row is scrolled back,
 * because the view objects are preserved by RecyclerView and the
 * binding wrapper is created fresh on each bind but wraps the same
 * underlying views.</p>
 *
 * <p><b>One-at-a-time playback:</b> enforced by {@link VoicePlayer}'s
 * singleton design. This adapter never has to track "which row is
 * playing" — it simply asks {@code VoicePlayer.get().getCurrentMessageId()}
 * on every {@code bindMediaContainer} and compares against the current
 * row's message ID. The result is a single source of truth across all
 * rows.</p>
 *
 * <p><b>Read receipts (v1 approach):</b> see {@link #setPeerLastReadAt(Date)}.
 * Per-conversation read state, not per-message.</p>
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // ============================================================
    // Callbacks
    // ============================================================

    public interface OnMessageLongPress {
        void onLongPress(@NonNull Message message, @NonNull View anchor);
    }

    public interface SenderResolver {
        @Nullable String getDisplayName(@NonNull String uid);
    }

    // ============================================================
    // View types
    // ============================================================

    private static final int TYPE_SENT = 1;
    private static final int TYPE_RECEIVED = 2;

    private static final float MAX_BUBBLE_WIDTH_PERCENT = 0.78f;

    // ============================================================
    // State
    // ============================================================

    private final String myUid;
    private final OnMessageLongPress longPressListener;
    private final List<Message> items = new ArrayList<>();

    @Nullable private Date peerLastReadAt;
    @Nullable private String peerDisplayName;
    @Nullable private SenderResolver senderResolver;
    private int maxBubbleWidthPx = -1;

    public MessageAdapter(@NonNull String myUid,
                          @NonNull OnMessageLongPress longPressListener) {
        this.myUid = myUid;
        this.longPressListener = longPressListener;
    }

    // ============================================================
    // External state
    // ============================================================

    public void setPeerLastReadAt(@Nullable Date lastReadAt) {
        if (Objects.equals(peerLastReadAt, lastReadAt)) return;
        this.peerLastReadAt = lastReadAt;
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, items.size());
        }
    }

    public void setPeerDisplayName(@Nullable String name) {
        if (Objects.equals(peerDisplayName, name)) return;
        this.peerDisplayName = name;
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, items.size());
        }
    }

    public void setSenderResolver(@Nullable SenderResolver resolver) {
        if (this.senderResolver == resolver) return;
        this.senderResolver = resolver;
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, items.size());
        }
    }

    // ============================================================
    // List submission
    // ============================================================

    public void submitList(@NonNull List<Message> newList) {
        List<Message> filtered = filterForDisplay(newList);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                new MessageDiff(items, filtered), true);
        items.clear();
        items.addAll(filtered);
        diff.dispatchUpdatesTo(this);
    }

    public void prependList(@NonNull List<Message> olderMessages) {
        List<Message> filtered = filterForDisplay(olderMessages);
        if (filtered.isEmpty()) return;
        items.addAll(0, filtered);
        notifyItemRangeInserted(0, filtered.size());
    }

    @NonNull
    private List<Message> filterForDisplay(@NonNull List<Message> source) {
        List<Message> out = new ArrayList<>(source.size());
        for (Message m : source) {
            if (!m.isDeletedFor(myUid)) {
                out.add(m);
            }
        }
        return out;
    }

    // ============================================================
    // RecyclerView.Adapter
    // ============================================================

    @Override
    public int getItemViewType(int position) {
        Message m = items.get(position);
        return myUid.equals(m.getSenderId()) ? TYPE_SENT : TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SENT) {
            ItemMessageSentBinding b = ItemMessageSentBinding.inflate(inflater, parent, false);
            return new SentVH(b);
        } else {
            ItemMessageReceivedBinding b = ItemMessageReceivedBinding.inflate(inflater, parent, false);
            return new ReceivedVH(b);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message m = items.get(position);
        if (holder instanceof SentVH) {
            bindSent((SentVH) holder, m);
        } else if (holder instanceof ReceivedVH) {
            bindReceived((ReceivedVH) holder, m, position);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ============================================================
    // Sent bubble binding
    // ============================================================

    private void bindSent(@NonNull SentVH holder, @NonNull Message m) {
        ItemMessageSentBinding b = holder.binding;
        Context ctx = b.getRoot().getContext();
        int maxW = computeMaxBubbleWidthPx(ctx);

        if (m.isTombstone()) {
            b.flMedia.setVisibility(View.GONE);
            b.tvMessageText.setVisibility(View.GONE);
            b.tvDeleted.setVisibility(View.VISIBLE);
            b.llReplyPreview.setVisibility(View.GONE);
        } else {
            b.tvDeleted.setVisibility(View.GONE);
            bindMediaContainer(holder, b.flMedia, b.ivMediaThumbnail, m);
            bindReplyPreviewSentIfAny(b, m);
            bindSentBody(b, m, maxW);
        }

        b.tvBubbleTime.setText(UiUtils.formatBubbleTime(m.getTimestamp()));

        if (m.getEditedAt() != null && !m.isTombstone()) {
            b.tvEdited.setVisibility(View.VISIBLE);
        } else {
            b.tvEdited.setVisibility(View.GONE);
        }

        bindStatusIcon(b, m);

        holder.itemView.setOnLongClickListener(v -> {
            longPressListener.onLongPress(m, holder.itemView);
            return true;
        });
    }

    private void bindSentBody(@NonNull ItemMessageSentBinding b,
                              @NonNull Message m,
                              int maxW) {
        String text = m.getText();
        boolean hasText = text != null && !text.trim().isEmpty();

        if (hasText) {
            b.tvMessageText.setVisibility(View.VISIBLE);
            b.tvMessageText.setMaxWidth(maxW);
            b.tvMessageText.setText(text);
        } else {
            b.tvMessageText.setVisibility(View.GONE);
        }
    }

    private void bindReplyPreviewSentIfAny(@NonNull ItemMessageSentBinding b,
                                           @NonNull Message m) {
        if (m.getReplyTo() == null) {
            b.llReplyPreview.setVisibility(View.GONE);
            return;
        }
        b.llReplyPreview.setVisibility(View.VISIBLE);
        Context ctx = b.getRoot().getContext();
        int maxW = computeMaxBubbleWidthPx(ctx);
        b.tvReplySender.setMaxWidth(maxW);
        b.tvReplyPreviewText.setMaxWidth(maxW);
        b.tvReplySender.setText(resolveReplySender(ctx, m.getReplyTo().getSenderId()));
        String preview = m.getReplyTo().getPreviewText();
        if (preview == null || preview.isEmpty()) {
            preview = typeLabel(ctx, m.getReplyTo().getType());
        }
        b.tvReplyPreviewText.setText(preview);
    }

    private void bindStatusIcon(@NonNull ItemMessageSentBinding b, @NonNull Message m) {
        Context ctx = b.getRoot().getContext();

        if (m.isTombstone() || m.getMessageId() == null) {
            b.ivStatus.setVisibility(View.GONE);
            return;
        }

        b.ivStatus.setVisibility(View.VISIBLE);

        boolean read = peerLastReadAt != null
                && m.getTimestamp() != null
                && !peerLastReadAt.before(m.getTimestamp());

        if (read) {
            b.ivStatus.setImageResource(R.drawable.ic_check_double);
            int seenColor = ContextCompat.getColor(ctx, R.color.status_seen);
            b.ivStatus.setImageTintList(ColorStateList.valueOf(seenColor));
        } else {
            b.ivStatus.setImageResource(R.drawable.ic_check_single);
            int metaColor = MaterialColors.getColor(b.ivStatus, R.attr.pulseBubbleMetaSent);
            b.ivStatus.setImageTintList(ColorStateList.valueOf(metaColor));
        }
    }

    // ============================================================
    // Received bubble binding
    // ============================================================

    private void bindReceived(@NonNull ReceivedVH holder, @NonNull Message m, int position) {
        ItemMessageReceivedBinding b = holder.binding;
        Context ctx = b.getRoot().getContext();
        int maxW = computeMaxBubbleWidthPx(ctx);

        bindSenderName(b, m, position);

        if (m.isTombstone()) {
            b.flMedia.setVisibility(View.GONE);
            b.tvMessageText.setVisibility(View.GONE);
            b.tvDeleted.setVisibility(View.VISIBLE);
            b.llReplyPreview.setVisibility(View.GONE);
        } else {
            b.tvDeleted.setVisibility(View.GONE);
            bindMediaContainer(holder, b.flMedia, b.ivMediaThumbnail, m);

            if (m.getReplyTo() != null) {
                b.llReplyPreview.setVisibility(View.VISIBLE);
                b.tvReplySender.setMaxWidth(maxW);
                b.tvReplyPreviewText.setMaxWidth(maxW);
                b.tvReplySender.setText(resolveReplySender(ctx, m.getReplyTo().getSenderId()));
                String preview = m.getReplyTo().getPreviewText();
                if (preview == null || preview.isEmpty()) {
                    preview = typeLabel(ctx, m.getReplyTo().getType());
                }
                b.tvReplyPreviewText.setText(preview);
            } else {
                b.llReplyPreview.setVisibility(View.GONE);
            }

            String text = m.getText();
            boolean hasText = text != null && !text.trim().isEmpty();
            if (hasText) {
                b.tvMessageText.setVisibility(View.VISIBLE);
                b.tvMessageText.setMaxWidth(maxW);
                b.tvMessageText.setText(text);
            } else {
                b.tvMessageText.setVisibility(View.GONE);
            }
        }

        b.tvBubbleTime.setText(UiUtils.formatBubbleTime(m.getTimestamp()));

        holder.itemView.setOnLongClickListener(v -> {
            longPressListener.onLongPress(m, holder.itemView);
            return true;
        });
    }

    private void bindSenderName(@NonNull ItemMessageReceivedBinding b,
                                @NonNull Message m,
                                int position) {
        if (senderResolver == null) {
            b.tvSenderName.setVisibility(View.GONE);
            return;
        }
        boolean isFirstInRun = position == 0
                || !items.get(position - 1).getSenderId().equals(m.getSenderId());
        if (!isFirstInRun) {
            b.tvSenderName.setVisibility(View.GONE);
            return;
        }
        String name = senderResolver.getDisplayName(m.getSenderId());
        if (name != null && !name.trim().isEmpty()) {
            b.tvSenderName.setText(name);
            b.tvSenderName.setVisibility(View.VISIBLE);
        } else {
            b.tvSenderName.setVisibility(View.GONE);
        }
    }

    // ============================================================
    // Shared media binding (image + audio)
    // ============================================================

    /**
     * Toggles the bubble's media container and dispatches to the
     * per-type binding path. Same entry point for sent and received
     * bubbles — only the {@code binding} views (flMedia, thumbnail)
     * differ between the two.
     */
    private void bindMediaContainer(@NonNull RecyclerView.ViewHolder holder,
                                    @NonNull FrameLayout flMedia,
                                    @NonNull ShapeableImageView thumbnail,
                                    @NonNull Message m) {
        if (!isMediaRenderable(m)) {
            flMedia.setVisibility(View.GONE);
            thumbnail.setOnClickListener(null);
            return;
        }

        final Message.Media media = m.getMedia();
        if (media == null) {
            flMedia.setVisibility(View.GONE);
            thumbnail.setOnClickListener(null);
            return;
        }

        flMedia.setVisibility(View.VISIBLE);

        if (Message.TYPE_AUDIO.equals(m.getType())
                || Message.TYPE_VOICE.equals(m.getType())) {
            bindAudioPreview(holder, flMedia, m);
        } else if (Message.TYPE_IMAGE.equals(m.getType())) {
            bindImagePreview(flMedia, thumbnail, m);
        } else {
            // Defensive: isMediaRenderable already gates this, but if
            // a future type is added without a binding path, hide the
            // media container rather than showing an empty square.
            flMedia.setVisibility(View.GONE);
            thumbnail.setOnClickListener(null);
        }
    }

    private void bindImagePreview(@NonNull FrameLayout flMedia,
                                  @NonNull ShapeableImageView thumbnail,
                                  @NonNull Message m) {
        // Hide the audio card if it was previously inflated into this
        // recycled view (from a previous audio bind).
        View audioRoot = flMedia.findViewById(R.id.audio_preview_root);
        if (audioRoot != null) audioRoot.setVisibility(View.GONE);

        thumbnail.setVisibility(View.VISIBLE);

        final Message.Media media = m.getMedia();
        if (media == null) return;

        String url = media.getThumbnailUrl();
        if (url == null || url.isEmpty()) {
            url = media.getDownloadUrl();
        }
        if (url != null && !url.isEmpty()) {
            Glide.with(thumbnail)
                    .load(url)
                    .into(thumbnail);
        }

        final String dlUrl = media.getDownloadUrl();
        final String spath = media.getStoragePath();
        if (dlUrl == null || dlUrl.isEmpty() || spath == null) {
            thumbnail.setOnClickListener(null);
            return;
        }
        final String fname = media.getDisplayName();
        final String mime = media.getMimeType() != null
                ? media.getMimeType() : "application/octet-stream";

        thumbnail.setOnClickListener(v -> {
            Context c = v.getContext();
            Intent intent = MediaViewerActivity.singleItemIntent(c, dlUrl, spath, fname, mime);
            c.startActivity(intent);
        });
    }

    /**
     * Inflates (once per holder) the audio card inside {@code flMedia}
     * and wires its play/pause button, progress bar, and time labels
     * to {@link VoicePlayer}. The inflated card is cached by the
     * recycled view tree — subsequent binds find it via
     * {@code findViewById(R.id.audio_preview_root)} and reuse it
     * without re-inflating.
     */
    private void bindAudioPreview(@NonNull RecyclerView.ViewHolder holder,
                                  @NonNull FrameLayout flMedia,
                                  @NonNull Message m) {
        // Hide the image thumbnail; the audio card is opaque and will
        // cover it anyway, but GONE avoids wasted Glide paints.
        View thumb = flMedia.findViewById(R.id.iv_media_thumbnail);
        if (thumb != null) thumb.setVisibility(View.GONE);

        // Get or lazily inflate the audio card.
        View audioRoot = flMedia.findViewById(R.id.audio_preview_root);
        if (audioRoot == null) {
            audioRoot = LayoutInflater.from(flMedia.getContext())
                    .inflate(R.layout.item_audio_preview, flMedia, false);
            flMedia.addView(audioRoot);
        }
        audioRoot.setVisibility(View.VISIBLE);

        final ItemAudioPreviewBinding b = ItemAudioPreviewBinding.bind(audioRoot);

        final Message.Media media = m.getMedia();
        if (media == null) return;

        // -------- Initial state --------
        String currentId = VoicePlayer.get().getCurrentMessageId();
        boolean isActive = currentId != null && currentId.equals(m.getMessageId());
        boolean isPlaying = isActive && VoicePlayer.get().isPlaying();

        b.btnAudioPlay.setImageResource(isPlaying
                ? R.drawable.ic_pause_circle
                : R.drawable.ic_play_circle);
        b.btnAudioPlay.setEnabled(true);

        b.progressAudio.setProgressCompat(0, false);
        b.tvAudioElapsed.setText(UiUtils.formatDuration(0));
        b.tvAudioDuration.setText(UiUtils.formatDuration(media.getDurationMs()));

        // -------- Click handler --------
        final String dlUrl = media.getDownloadUrl();
        if (dlUrl == null || dlUrl.isEmpty()) {
            b.btnAudioPlay.setEnabled(false);
            return;
        }

        b.btnAudioPlay.setOnClickListener(v -> {
            String activeId = VoicePlayer.get().getCurrentMessageId();
            if (activeId != null && activeId.equals(m.getMessageId())) {
                if (VoicePlayer.get().isPlaying()) {
                    VoicePlayer.get().pause();
                } else {
                    VoicePlayer.get().resume();
                }
                return;
            }
            // A different message is active (or none). Stop it so its
            // callback can reset that row's UI cleanly.
            if (activeId != null) {
                VoicePlayer.get().stop();
            }
            b.btnAudioPlay.setEnabled(false);
            VoicePlayer.get().play(
                    v.getContext(),
                    Uri.parse(dlUrl),
                    m.getMessageId(),
                    new VoicePlayer.Callback() {
                        @Override public void onStarted() {
                            if (!isCurrent(holder, m)) return;
                            b.btnAudioPlay.setEnabled(true);
                            b.btnAudioPlay.setImageResource(R.drawable.ic_pause_circle);
                        }
                        @Override public void onProgress(long positionMs, long durationMs) {
                            if (!isCurrent(holder, m)) return;
                            b.tvAudioElapsed.setText(UiUtils.formatDuration(positionMs));
                            b.tvAudioDuration.setText(UiUtils.formatDuration(durationMs));
                            int pct = durationMs > 0
                                    ? (int) Math.min(100, positionMs * 100 / durationMs)
                                    : 0;
                            b.progressAudio.setProgressCompat(pct, false);
                        }
                        @Override public void onPaused() {
                            if (!isCurrent(holder, m)) return;
                            b.btnAudioPlay.setEnabled(true);
                            b.btnAudioPlay.setImageResource(R.drawable.ic_play_circle);
                        }
                        @Override public void onStopped() {
                            if (!isCurrent(holder, m)) return;
                            b.btnAudioPlay.setEnabled(true);
                            b.btnAudioPlay.setImageResource(R.drawable.ic_play_circle);
                            b.progressAudio.setProgressCompat(0, false);
                            b.tvAudioElapsed.setText(UiUtils.formatDuration(0));
                        }
                        @Override public void onCompleted() {
                            if (!isCurrent(holder, m)) return;
                            b.btnAudioPlay.setEnabled(true);
                            b.btnAudioPlay.setImageResource(R.drawable.ic_play_circle);
                            b.progressAudio.setProgressCompat(0, false);
                            b.tvAudioElapsed.setText(UiUtils.formatDuration(0));
                        }
                        @Override public void onError(@NonNull Exception e) {
                            if (!isCurrent(holder, m)) return;
                            b.btnAudioPlay.setEnabled(true);
                            b.btnAudioPlay.setImageResource(R.drawable.ic_play_circle);
                        }
                    });
        });
    }

    /**
     * Whether the message's media should render inside the bubble.
     * Phase-8 scope: images, audio files, and voice messages.
     * Video and document fall through to the text path.
     */
    private boolean isMediaRenderable(@NonNull Message m) {
        if (m.isTombstone()) return false;
        if (m.getMedia() == null) return false;
        return Message.TYPE_IMAGE.equals(m.getType())
                || Message.TYPE_AUDIO.equals(m.getType())
                || Message.TYPE_VOICE.equals(m.getType());
    }

    /**
     * Whether the given {@code holder} is still bound to the same
     * message at {@code holder.getBindingAdapterPosition()}. Guards
     * every voice callback against recycled-row stale updates.
     */
    private boolean isCurrent(@NonNull RecyclerView.ViewHolder holder, @NonNull Message m) {
        int pos = holder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return false;
        if (pos >= items.size()) return false;
        return Objects.equals(items.get(pos).getMessageId(), m.getMessageId());
    }

    // ============================================================
    // Helpers
    // ============================================================

    private int computeMaxBubbleWidthPx(@NonNull Context ctx) {
        if (maxBubbleWidthPx > 0) return maxBubbleWidthPx;
        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        maxBubbleWidthPx = Math.round(screenWidth * MAX_BUBBLE_WIDTH_PERCENT);
        return maxBubbleWidthPx;
    }

    @NonNull
    private String resolveReplySender(@NonNull Context ctx, @Nullable String senderId) {
        if (senderId == null) return "";
        if (myUid.equals(senderId)) {
            return "You";
        }
        if (peerDisplayName != null && !peerDisplayName.trim().isEmpty()) {
            return peerDisplayName;
        }
        if (senderResolver != null) {
            String name = senderResolver.getDisplayName(senderId);
            if (name != null && !name.trim().isEmpty()) return name;
        }
        return "";
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
            default:                    return "";
        }
    }

    // ============================================================
    // ViewHolders
    // ============================================================

    static final class SentVH extends RecyclerView.ViewHolder {
        final ItemMessageSentBinding binding;

        SentVH(@NonNull ItemMessageSentBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static final class ReceivedVH extends RecyclerView.ViewHolder {
        final ItemMessageReceivedBinding binding;

        ReceivedVH(@NonNull ItemMessageReceivedBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    // ============================================================
    // DiffUtil
    // ============================================================

    private static final class MessageDiff extends DiffUtil.Callback {
        private final List<Message> oldList;
        private final List<Message> newList;

        MessageDiff(@NonNull List<Message> oldList, @NonNull List<Message> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return Objects.equals(
                    oldList.get(oldPos).getMessageId(),
                    newList.get(newPos).getMessageId());
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            Message a = oldList.get(oldPos);
            Message b = newList.get(newPos);

            if (!Objects.equals(a.getText(), b.getText())) return false;
            if (!Objects.equals(a.getType(), b.getType())) return false;
            if (!Objects.equals(a.getSenderId(), b.getSenderId())) return false;
            if (!Objects.equals(a.getTimestamp(), b.getTimestamp())) return false;
            if (a.isDeletedForEveryone() != b.isDeletedForEveryone()) return false;
            if (!Objects.equals(a.getEditedAt(), b.getEditedAt())) return false;

            Message.ReplyTo ra = a.getReplyTo();
            Message.ReplyTo rb = b.getReplyTo();
            if (ra == null && rb != null) return false;
            if (ra != null && rb == null) return false;
            if (ra != null) {
                if (!Objects.equals(ra.getMessageId(), rb.getMessageId())) return false;
                if (!Objects.equals(ra.getSenderId(), rb.getSenderId())) return false;
                if (!Objects.equals(ra.getPreviewText(), rb.getPreviewText())) return false;
                if (!Objects.equals(ra.getType(), rb.getType())) return false;
            }

            Message.Media ma = a.getMedia();
            Message.Media mb = b.getMedia();
            if (ma == null && mb != null) return false;
            if (ma != null && mb == null) return false;
            if (ma != null) {
                if (!Objects.equals(ma.getDownloadUrl(), mb.getDownloadUrl())) return false;
                if (!Objects.equals(ma.getThumbnailUrl(), mb.getThumbnailUrl())) return false;
                if (ma.getDurationMs() != mb.getDurationMs()) return false;
            }

            return true;
        }
    }
}
