package com.pulse.messenger.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.R;
import com.pulse.messenger.adapter.ConversationAdapter;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.databinding.FragmentChatsBinding;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat list fragment.
 *
 * <p><b>Data flow:</b></p>
 * <ol>
 *   <li>{@link #onViewCreated} subscribes a lifecycle-bound observer to
 *       {@link FirestoreHelper#listenConversations(String)}.</li>
 *   <li>The first emission arrives almost instantly from Firestore's
 *       persistent cache (configured in {@code App.configureFirestore}),
 *       so the progress indicator is nearly always hidden before the
 *       user notices it.</li>
 *   <li>For each private conversation, {@link #userResolver} supplies the
 *       peer {@link User}. Missing peers are requested in the background
 *       and the list is re-filtered when their documents load.</li>
 *   <li>Archived conversations are filtered out client-side (Firestore
 *       cannot express "map key not equal to true" as a where-clause).</li>
 *   <li>The search field runs a case-insensitive substring filter over the
 *       cached conversation list — no network round-trip per keystroke.</li>
 * </ol>
 *
 * <p><b>Lifecycle:</b> the {@code LiveData} observer is bound with
 * {@code getViewLifecycleOwner()} — it is removed automatically when the
 * Fragment's view is destroyed, so there is no listener leak, no manual
 * detach, and no need for {@code onDestroyView} cleanup of the observer
 * itself.</p>
 *
 * <p><b>Peer caching:</b> {@link #userCache} stores resolved peers for
 * the lifetime of the Fragment's view. A peer missing from the cache is
 * requested once; concurrent binds for the same missing UID are
 * deduplicated by the {@link #pendingUserFetches} guard.</p>
 */
public class ChatsFragment extends Fragment implements ConversationAdapter.UserResolver {

    private static final String TAG = "ChatsFragment";

    // ============================================================
    // ViewBinding & state
    // ============================================================

    private FragmentChatsBinding binding;
    private ConversationAdapter adapter;
    private String myUid;

    /** Full (unfiltered, unarchived) list as received from Firestore. */
    private final List<Conversation> allConversations = new ArrayList<>();

    /** Currently displayed list (after archive + search filtering). */
    private final List<Conversation> displayList = new ArrayList<>();

    /** Resolved peer users. Populated by listenUser callbacks. */
    private final Map<String, User> userCache = new HashMap<>();

    /**
     * UIDs whose fetch has been requested but not yet resolved. Prevents
     * multiple concurrent binds for the same peer from triggering
     * duplicate Firestore reads.
     */
    private final Map<String, Boolean> pendingUserFetches = new HashMap<>();

    /** Current search query (empty = no filter). */
    private String currentQuery = "";

    private LiveData<List<Conversation>> conversationsLiveData;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentChatsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // Should never happen — AuthActivity routes here only when
            // signed in. If it does, bail gracefully rather than NPE.
            return;
        }
        myUid = user.getUid();

        setupRecyclerView();
        setupSearchField();
        setupFab();
        observeConversations();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // LiveData observer is bound to the view lifecycle, so it is
        // automatically detached. We only need to null the binding so
        // it can be garbage-collected.
        if (conversationsLiveData != null) {
            conversationsLiveData.removeObservers(getViewLifecycleOwner());
        }
        binding = null;
    }

    // ============================================================
    // Setup
    // ============================================================

    private void setupRecyclerView() {
        adapter = new ConversationAdapter(
                myUid,
                this,
                conversation -> openConversation(conversation),
                (conversation, anchor) -> showConversationMenu(conversation, anchor));
        binding.rvChats.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvChats.setAdapter(adapter);
        binding.rvChats.setHasFixedSize(true);
    }

    private void setupSearchField() {
        binding.etSearchChats.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                currentQuery = s == null ? "" : s.toString().trim().toLowerCase();
                applyFilterAndSubmit();
            }
        });

        binding.etSearchChats.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                UiUtils.hideKeyboard(requireContext(), binding.etSearchChats);
                return true;
            }
            return false;
        });
    }

    private void setupFab() {
        binding.fabNewChat.setOnClickListener(v -> openPeople());
        binding.fabNewChat.setOnLongClickListener(v -> {
            openGroupCreate();
            return true;
        });
    }

    private void observeConversations() {
        conversationsLiveData = FirestoreHelper.get().listenConversations(myUid);

        // Show the progress spinner only if the first emission does not
        // arrive within a short window. Firestore's persistent cache
        // resolves in <100 ms on a warm start, so the spinner is nearly
        // always hidden before the user notices it.
        binding.progressChats.setVisibility(View.VISIBLE);
        binding.rvChats.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.GONE);

        conversationsLiveData.observe(getViewLifecycleOwner(), conversations -> {
            if (binding == null) return;

            binding.progressChats.setVisibility(View.GONE);

            allConversations.clear();
            if (conversations != null) {
                for (Conversation c : conversations) {
                    // Filter archived conversations client-side.
                    if (!c.isArchivedFor(myUid)) {
                        allConversations.add(c);
                    }
                    // Pre-fetch peers for private conversations so the
                    // adapter's bind gets a cache hit.
                    if (c.isPrivate()) {
                        String peerUid = c.getPeerUid(myUid);
                        if (peerUid != null && !userCache.containsKey(peerUid)
                                && !pendingUserFetches.containsKey(peerUid)) {
                            fetchUser(peerUid);
                        }
                    }
                }
            }
            applyFilterAndSubmit();
        });
    }

    // ============================================================
    // Filter + submit
    // ============================================================

    private void applyFilterAndSubmit() {
        if (binding == null) return;

        List<Conversation> filtered;
        if (currentQuery.isEmpty()) {
            filtered = allConversations;
        } else {
            filtered = new ArrayList<>();
            for (Conversation c : allConversations) {
                if (matchesQuery(c, currentQuery)) {
                    filtered.add(c);
                }
            }
        }

        // Update empty state and list visibility.
        if (filtered.isEmpty()) {
            binding.rvChats.setVisibility(View.GONE);
            binding.llEmptyState.setVisibility(View.VISIBLE);
            if (currentQuery.isEmpty()) {
                binding.tvEmptyTitle.setText(R.string.chats_empty_title);
                binding.tvEmptySubtitle.setText(R.string.chats_empty_subtitle);
            } else {
                binding.tvEmptyTitle.setText(R.string.people_no_results);
                binding.tvEmptySubtitle.setText("");
            }
        } else {
            binding.rvChats.setVisibility(View.VISIBLE);
            binding.llEmptyState.setVisibility(View.GONE);
        }

        displayList.clear();
        displayList.addAll(filtered);
        adapter.submitList(displayList);
    }

    private boolean matchesQuery(@NonNull Conversation c, @NonNull String query) {
        if (c.isGroup()) {
            String name = c.getName();
            return name != null && name.toLowerCase().contains(query);
        }
        String peerUid = c.getPeerUid(myUid);
        if (peerUid == null) return false;
        User peer = userCache.get(peerUid);
        if (peer != null) {
            String label = peer.getDisplayLabel();
            if (label.toLowerCase().contains(query)) return true;
            String uname = peer.getUsername();
            if (uname != null && uname.toLowerCase().contains(query)) return true;
        }
        return false;
    }

    // ============================================================
    // UserResolver implementation (called by the adapter)
    // ============================================================

    @Nullable
    @Override
    public User get(@NonNull String uid) {
        return userCache.get(uid);
    }

    @Override
    public void request(@NonNull String uid) {
        fetchUser(uid);
    }

    private void fetchUser(@NonNull String uid) {
        if (userCache.containsKey(uid) || pendingUserFetches.containsKey(uid)) return;
        pendingUserFetches.put(uid, Boolean.TRUE);

        FirestoreHelper.get().getUser(uid, new FirestoreHelper.Callback<User>() {
            @Override
            public void onSuccess(@Nullable User result) {
                pendingUserFetches.remove(uid);
                if (result != null) {
                    userCache.put(uid, result);
                    if (binding != null) {
                        // Re-filter and re-submit so newly resolved
                        // peer names appear without a full rebind.
                        applyFilterAndSubmit();
                    }
                }
            }

            @Override
            public void onError(@NonNull Exception e) {
                pendingUserFetches.remove(uid);
                // Peer's user doc may have been deleted, or a transient
                // network error. Leave them out of the cache; a retry
                // happens on the next list emission.
            }
        });
    }

    // ============================================================
    // Navigation
    // ============================================================

    private void openConversation(@NonNull Conversation conversation) {
        android.content.Intent intent = new android.content.Intent(
                requireContext(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversation.getConversationId());
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_TYPE, conversation.getType());
        if (conversation.isPrivate()) {
            String peerUid = conversation.getPeerUid(myUid);
            if (peerUid != null) {
                intent.putExtra(ChatActivity.EXTRA_PEER_UID, peerUid);
            }
        }
        startActivity(intent);
    }

    private void openPeople() {
        // Delegates to MainActivity to switch the bottom-nav tab.
        if (requireActivity() instanceof MainActivity) {
            ((MainActivity) requireActivity()).selectPeopleTab();
        }
    }

    private void openGroupCreate() {
        android.content.Intent intent = new android.content.Intent(
                requireContext(), GroupCreateActivity.class);
        startActivity(intent);
    }

    // ============================================================
    // Conversation context menu
    // ============================================================

    private void showConversationMenu(@NonNull Conversation conversation,
                                      @NonNull View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.inflate(R.menu.menu_conversation);

        boolean pinned = conversation.isPinnedFor(myUid);
        boolean muted = conversation.isMutedFor(myUid);

        menu.getMenu().findItem(R.id.action_pin).setTitle(
                pinned ? R.string.conversation_menu_unpin : R.string.conversation_menu_pin);
        menu.getMenu().findItem(R.id.action_mute).setTitle(
                muted ? R.string.conversation_menu_unmute : R.string.conversation_menu_mute);

        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_pin) {
                togglePin(conversation, !pinned);
                return true;
            }
            if (id == R.id.action_mute) {
                toggleMute(conversation, !muted);
                return true;
            }
            if (id == R.id.action_archive) {
                archiveConversation(conversation);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void togglePin(@NonNull Conversation c, boolean pin) {
        FirestoreHelper.get().updateMyMemberInfo(
                c.getConversationId(), myUid,
                pin,
                c.getMemberInfoFor(myUid).getMutedUntil(),
                false,
                simpleCallback(R.string.error_generic));
    }

    private void toggleMute(@NonNull Conversation c, boolean mute) {
        // Mute indefinitely: Long.MAX_VALUE is safe in a Firestore long
        // field and isMuted() compares it correctly against "now".
        long mutedUntil = mute ? Long.MAX_VALUE : 0L;
        FirestoreHelper.get().updateMyMemberInfo(
                c.getConversationId(), myUid,
                c.isPinnedFor(myUid),
                mutedUntil,
                false,
                simpleCallback(R.string.error_generic));
    }

    private void archiveConversation(@NonNull Conversation c) {
        FirestoreHelper.get().updateMyMemberInfo(
                c.getConversationId(), myUid,
                c.isPinnedFor(myUid),
                c.getMemberInfoFor(myUid).getMutedUntil(),
                true,
                new FirestoreHelper.Callback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void result) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.conversation_menu_archive));
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.error_generic));
                    }
                });
    }

    @NonNull
    private FirestoreHelper.Callback<Void> simpleCallback(int errorStringRes) {
        return new FirestoreHelper.Callback<Void>() {
            @Override
            public void onSuccess(@Nullable Void result) {
                // No-op — the Firestore listener drives the UI update.
            }

            @Override
            public void onError(@NonNull Exception e) {
                if (binding == null) return;
                UiUtils.toast(binding.getRoot(), getString(errorStringRes));
            }
        };
    }
}
