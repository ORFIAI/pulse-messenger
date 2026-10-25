package com.pulse.messenger.adapter;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.pulse.messenger.R;
import com.pulse.messenger.databinding.ItemUserBinding;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter for user-search results, with two display modes.
 *
 * <p><b>MODE_MESSAGE (default):</b> used by {@code PeopleFragment}.
 * Each row shows a colored-initials avatar (with photo overlay), the
 * display name, an {@code @username} sub-label, and a "Message" chip
 * on the right. Tapping the row or the chip fires
 * {@link OnUserTap#onTap(User)} — the caller starts a private
 * conversation.</p>
 *
 * <p><b>MODE_SELECT:</b> used by {@code GroupCreateActivity}. Each row
 * shows the same avatar, name, and username, but with a
 * {@code MaterialCheckBox} on the right instead of the "Message" chip.
 * The checkbox is a visual indicator only — it is not independently
 * focusable or clickable. Tapping the row toggles the user's
 * selection via {@link OnUserTap#onTap(User)}; the caller maintains
 * the selected-set and calls {@link #setSelectedUids(Collection)}
 * to refresh the checkmark states.</p>
 *
 * <p><b>Why two modes in one adapter:</b> the row layout
 * ({@code item_user.xml}), the avatar rendering logic, the display
 * name fallback, and the DiffUtil comparison are identical across
 * both modes. Splitting into two adapters would duplicate ~80 lines
 * of view binding for a 15-line difference (mode toggle + checkbox
 * state). One adapter with a mode flag is the correct trade-off for
 * this project's compact architecture.</p>
 *
 * <p><b>What this adapter deliberately does NOT render:</b></p>
 * <ul>
 *   <li><b>Presence dot.</b> Presence is a chat-header concern, not a
 *       search-result concern. See {@code ConversationAdapter}'s class
 *       notes for the full rationale.</li>
 *   <li><b>Last-seen text.</b> Same reasoning.</li>
 *   <li><b>Blocked badge.</b> v1 does not surface blocked state in
 *       search results.</li>
 * </ul>
 */
public class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {

    // ============================================================
    // Modes
    // ============================================================

    /** People tab — "Message" chip on the right, tap row starts a chat. */
    public static final int MODE_MESSAGE = 0;

    /** Group create — checkbox on the right, tap row toggles selection. */
    public static final int MODE_SELECT = 1;

    /** Called when a row (or its "Message" chip, in MODE_MESSAGE) is tapped. */
    public interface OnUserTap {
        void onTap(@NonNull User user);
    }

    // ============================================================
    // State
    // ============================================================

    private final List<User> items = new ArrayList<>();
    private final OnUserTap tapListener;
    private final int mode;

    /**
     * UIDs of users currently selected. Only consulted in
     * {@link #MODE_SELECT}. Initialized to an empty unmodifiable set
     * so {@code contains(...)} never NPEs before the first
     * {@link #setSelectedUids} call.
     */
    @NonNull
    private Set<String> selectedUids = Collections.emptySet();

    // ============================================================
    // Constructors
    // ============================================================

    /**
     * Convenience constructor — equivalent to
     * {@link #UserAdapter(int, OnUserTap)} with {@link #MODE_MESSAGE}.
     * Kept so existing call sites ({@code PeopleFragment}) do not need
     * to be updated when this modification lands.
     */
    public UserAdapter(@NonNull OnUserTap tapListener) {
        this(MODE_MESSAGE, tapListener);
    }

    public UserAdapter(int mode, @NonNull OnUserTap tapListener) {
        this.mode = mode;
        this.tapListener = tapListener;
    }

    // ============================================================
    // Selection state (MODE_SELECT only)
    // ============================================================

    /**
     * Sets the set of UIDs currently selected. Only meaningful in
     * {@link #MODE_SELECT}.
     *
     * <p>The incoming collection is defensively copied into a new
     * {@code HashSet} so that the caller's collection can be mutated
     * afterward (e.g., {@code selectedMembers.keySet()} returns a
     * live view into a {@code LinkedHashMap}) without affecting the
     * adapter's view of the selection.</p>
     *
     * <p>Rather than diff the previous selection against the new one
     * (which would require per-item tracking), the entire visible
     * list is notified with {@code notifyItemRangeChanged}. For
     * search-result lists of at most 20 items this is a single-digit
     * millisecond operation that produces no perceptible animation
     * churn — the checkbox state updates are instant.</p>
     */
    public void setSelectedUids(@NonNull Collection<String> uids) {
        if (mode != MODE_SELECT) return;   // harmless no-op in MESSAGE mode
        this.selectedUids = new HashSet<>(uids);
        if (!items.isEmpty()) {
            notifyItemRangeChanged(0, items.size());
        }
    }

    // ============================================================
    // List submission
    // ============================================================

    public void submitList(@NonNull List<User> newList) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                new UserDiff(items, newList), true);
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
        ItemUserBinding binding = ItemUserBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        User user = items.get(position);
        Context ctx = holder.itemView.getContext();
        ItemUserBinding b = holder.binding;

        // ---------- Name ----------
        String label = user.getDisplayLabel();
        b.tvName.setText(label);

        // ---------- Username sub-label ----------
        String username = user.getUsername();
        if (username != null && !username.trim().isEmpty()) {
            b.tvUsername.setText("@" + username);
            b.tvUsername.setVisibility(View.VISIBLE);
        } else {
            b.tvUsername.setVisibility(View.GONE);
        }

        // ---------- Avatar (initials + photo overlay) ----------
        String initialSeed = user.getUid() != null
                ? user.getUid()
                : (user.getUsername() != null ? user.getUsername() : "?");
        String initialText = UiUtils.avatarInitial(label);
        int initialColor = UiUtils.resolveAvatarColor(ctx, initialSeed);

        b.tvInitials.setText(initialText);
        ViewCompat.setBackgroundTintList(
                b.tvInitials,
                ColorStateList.valueOf(initialColor));

        String photoUrl = user.getPhotoUrl();
        if (photoUrl != null && !photoUrl.isEmpty()) {
            b.ivAvatar.setVisibility(View.VISIBLE);
            Glide.with(b.ivAvatar)
                    .load(photoUrl)
                    .transform(new CircleCrop())
                    .into(b.ivAvatar);
        } else {
            b.ivAvatar.setVisibility(View.GONE);
        }

        // ---------- Mode-dependent trailing control ----------
        if (mode == MODE_SELECT) {
            b.btnMessage.setVisibility(View.GONE);
            b.cbSelected.setVisibility(View.VISIBLE);
            boolean selected = user.getUid() != null
                    && selectedUids.contains(user.getUid());
            b.cbSelected.setChecked(selected);
        } else {
            b.btnMessage.setVisibility(View.VISIBLE);
            b.cbSelected.setVisibility(View.GONE);
        }

        // ---------- Tap targets ----------
        // Row tap is the primary interaction in both modes. In
        // MODE_MESSAGE, the "Message" chip is a redundant affordance
        // that triggers the same action. In MODE_SELECT, the
        // checkbox is not independently clickable (per item_user.xml),
        // so there is only the row tap to worry about.
        holder.itemView.setOnClickListener(v -> tapListener.onTap(user));
        b.btnMessage.setOnClickListener(v -> tapListener.onTap(user));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ============================================================
    // ViewHolder
    // ============================================================

    static final class VH extends RecyclerView.ViewHolder {
        final ItemUserBinding binding;

        VH(@NonNull ItemUserBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    // ============================================================
    // DiffUtil
    // ============================================================

    /**
     * Compares by UID for identity, and by display name, username,
     * and photo URL for content. Identical to the previous version —
     * the mode flag does not affect what fields matter for the diff,
     * because the mode is fixed for a given adapter instance (a new
     * adapter is created per mode by the callers).
     */
    private static final class UserDiff extends DiffUtil.Callback {
        private final List<User> oldList;
        private final List<User> newList;

        UserDiff(@NonNull List<User> oldList, @NonNull List<User> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldPos, int newPos) {
            return Objects.equals(
                    oldList.get(oldPos).getUid(),
                    newList.get(newPos).getUid());
        }

        @Override
        public boolean areContentsTheSame(int oldPos, int newPos) {
            User a = oldList.get(oldPos);
            User b = newList.get(newPos);
            if (!Objects.equals(a.getDisplayName(), b.getDisplayName())) return false;
            if (!Objects.equals(a.getUsername(), b.getUsername())) return false;
            if (!Objects.equals(a.getPhotoUrl(), b.getPhotoUrl())) return false;
            return true;
        }
    }
}
