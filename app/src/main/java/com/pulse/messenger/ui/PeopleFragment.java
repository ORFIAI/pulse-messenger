package com.pulse.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.R;
import com.pulse.messenger.adapter.UserAdapter;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.databinding.FragmentPeopleBinding;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * People tab — user search and start-conversation flow.
 *
 * <p><b>Search behavior:</b></p>
 * <ul>
 *   <li>Debounced by {@link #SEARCH_DEBOUNCE_MS} (400 ms) so a
 *       fast typist does not fire a Firestore query per keystroke.</li>
 *   <li>Requires at least {@link #MIN_QUERY_LENGTH} (2) characters
 *       before querying — single-character queries return too many
 *       results and cost a full round-trip for almost no value.</li>
 *   <li>Results come from {@link FirestoreHelper#searchUsers}
 *       (prefix search on username and display name, excluding the
 *       caller). At most 20 results are returned per query.</li>
 *   <li>If a newer query starts before an older query completes, the
 *       older query's result is discarded (see
 *       {@link #currentQueryToken}). This is the standard
 *       "latest-wins" pattern for debounced async search and avoids
 *       an out-of-order response clobbering a newer result.</li>
 * </ul>
 *
 * <p><b>Tap behavior:</b> tapping a search result resolves (or
 * creates) the private conversation with that user via
 * {@link FirestoreHelper#findOrCreatePrivateConversation}, then
 * launches {@link ChatActivity}. Until the conversation resolves,
 * the tapped row's FAB-equivalent action is disabled and a
 * per-row progress is shown — but for simplicity this Phase-3
 * implementation disables the entire list while a conversation
 * is resolving (a single boolean guard, no per-row progress
 * state). This is deliberate: the resolution is very fast (a
 * single transaction) and per-row spinners are visual noise for
 * a sub-second operation. Phase 5 may add per-row progress if
 * the UX needs it.</p>
 *
 * <p><b>Empty state:</b> shown when no query is active (invites
 * the user to type), or when a query returned no results.</p>
 */
public class PeopleFragment extends Fragment {

    private static final String TAG = "PeopleFragment";
    private static final int MIN_QUERY_LENGTH = 2;
    private static final long SEARCH_DEBOUNCE_MS = 400L;

    private FragmentPeopleBinding binding;
    private UserAdapter adapter;
    private String myUid;

    /**
     * Debounce runnable — posted to the view when the user types, and
     * cancelled + replaced on every keystroke. Cancelled in
     * {@code onDestroyView} so a stale query cannot fire after the
     * fragment is torn down.
     */
    @Nullable private Runnable pendingSearch;

    /**
     * Monotonically increasing token for the latest in-flight query.
     * A response with a token less than this value is discarded — it
     * represents a superseded query. This is the "latest-wins"
     * pattern for debounced async search.
     */
    private int currentQueryToken = 0;

    /**
     * Guards against opening two conversations from rapid taps. The
     * conversation-resolution transaction would otherwise run twice,
     * once per tap, and the second would resolve to the same
     * conversation (idempotent) — but two ChatActivity launches
     * would stack.
     */
    private boolean resolvingConversation = false;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentPeopleBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        myUid = user.getUid();

        setupRecyclerView();
        setupSearchField();
        showInviteState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pendingSearch != null) {
            binding.getRoot().removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        binding = null;
    }

    // ============================================================
    // Setup
    // ============================================================

    private void setupRecyclerView() {
        adapter = new UserAdapter(this::onUserTapped);
        binding.rvPeople.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvPeople.setAdapter(adapter);
        binding.rvPeople.setHasFixedSize(true);
    }

    private void setupSearchField() {
        binding.etSearchPeople.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                onQueryChanged(s == null ? "" : s.toString());
            }
        });

        binding.etSearchPeople.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                UiUtils.hideKeyboard(requireContext(), binding.etSearchPeople);
                return true;
            }
            return false;
        });
    }

    // ============================================================
    // Search
    // ============================================================

    private void onQueryChanged(@NonNull String query) {
        if (binding == null) return;

        if (pendingSearch != null) {
            binding.getRoot().removeCallbacks(pendingSearch);
            pendingSearch = null;
        }

        String trimmed = query.trim();

        if (trimmed.length() < MIN_QUERY_LENGTH) {
            // Below the minimum — clear results, show invite, and
            // invalidate any in-flight query so its result is
            // discarded when it arrives.
            currentQueryToken++;
            adapter.submitList(new ArrayList<>());
            showInviteState();
            return;
        }

        showLoadingState();
        pendingSearch = () -> runSearch(trimmed);
        binding.getRoot().postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void runSearch(@NonNull String query) {
        if (binding == null) return;
        final int token = ++currentQueryToken;

        FirestoreHelper.get().searchUsers(query, myUid,
                new FirestoreHelper.Callback<List<User>>() {
                    @Override
                    public void onSuccess(@Nullable List<User> result) {
                        if (binding == null) return;
                        if (token != currentQueryToken) return; // superseded
                        List<User> users = result != null ? result : new ArrayList<>();
                        adapter.submitList(users);
                        if (users.isEmpty()) {
                            showNoResultsState(query);
                        } else {
                            showResults();
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        if (token != currentQueryToken) return;
                        showNoResultsState(query);
                    }
                });
    }

    // ============================================================
    // States
    // ============================================================

    private void showInviteState() {
        binding.progressPeople.setVisibility(View.GONE);
        binding.rvPeople.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.VISIBLE);
        binding.tvEmptyTitle.setText(R.string.people_empty_title);
        binding.tvEmptySubtitle.setText(R.string.people_empty_subtitle);
    }

    private void showLoadingState() {
        binding.progressPeople.setVisibility(View.VISIBLE);
        binding.rvPeople.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.GONE);
    }

    private void showResults() {
        binding.progressPeople.setVisibility(View.GONE);
        binding.rvPeople.setVisibility(View.VISIBLE);
        binding.llEmptyState.setVisibility(View.GONE);
    }

    private void showNoResultsState(@NonNull String query) {
        binding.progressPeople.setVisibility(View.GONE);
        binding.rvPeople.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.VISIBLE);
        binding.tvEmptyTitle.setText(R.string.people_no_results);
        binding.tvEmptySubtitle.setText(query);
    }

    // ============================================================
    // Result tap → conversation
    // ============================================================

    private void onUserTapped(@NonNull User peer) {
        if (resolvingConversation) return;
        if (peer.getUid() == null || peer.getUid().equals(myUid)) return;

        resolvingConversation = true;
        FirestoreHelper.get().findOrCreatePrivateConversation(myUid, peer.getUid(),
                new FirestoreHelper.Callback<Conversation>() {
                    @Override
                    public void onSuccess(@Nullable Conversation result) {
                        resolvingConversation = false;
                        if (binding == null) return;
                        if (result == null) {
                            UiUtils.toast(binding.getRoot(),
                                    getString(R.string.error_generic));
                            return;
                        }
                        openChat(result, peer);
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        resolvingConversation = false;
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.error_generic));
                    }
                });
    }

    private void openChat(@NonNull Conversation conversation, @NonNull User peer) {
        Intent intent = new Intent(requireContext(), ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversation.getConversationId());
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_TYPE, conversation.getType());
        intent.putExtra(ChatActivity.EXTRA_PEER_UID, peer.getUid());
        startActivity(intent);
    }
}
