package com.pulse.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.R;
import com.pulse.messenger.adapter.UserAdapter;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.databinding.ActivityGroupCreateBinding;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.UiUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Group creation screen.
 *
 * <p><b>Flow:</b> enter a group name → search for members (debounced)
 * → tap results to toggle selection (chips appear above the search)
 * → tap "Create group" → Firestore write → navigate to
 * {@link ChatActivity} for the new group.</p>
 *
 * <p><b>Scroll fix:</b> {@link #setupRecyclerView()} calls
 * {@code setNestedScrollingEnabled(false)} on the results
 * RecyclerView. Without it, the RecyclerView's internal scroll
 * consumes touch events meant for the outer {@code NestedScrollView},
 * producing a "form won't scroll" feel on API 24-25. With it, the
 * RecyclerView is a "dumb list" that grows to its content height
 * (bounded by the layout's 360dp max-height) and the outer form
 * scrolls as one unit.</p>
 *
 * <p><b>Photo:</b> group photo is not part of this Phase-6 pass.
 * Groups are created without a photo; the header falls back to a
 * colored-initials avatar. Photo upload for groups will be added
 * alongside the eventual {@code GroupInfoActivity} (Phase 6
 * follow-up).</p>
 *
 * <p><b>Creator privileges:</b> the creator is automatically added
 * as a member and promoted to admin by
 * {@link FirestoreHelper#createGroup} — this activity does not need
 * to include the current user in {@code selectedMembers}.</p>
 */
public class GroupCreateActivity extends AppCompatActivity {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final long SEARCH_DEBOUNCE_MS = 400L;
    private static final int MAX_GROUP_NAME_LENGTH = 60;

    private ActivityGroupCreateBinding binding;
    private String myUid;
    private UserAdapter adapter;

    /**
     * Selected members, keyed by UID. A {@code LinkedHashMap} is used
     * so the chip order matches insertion order (the last-added
     * member appears at the end of the chip row).
     */
    private final LinkedHashMap<String, User> selectedMembers = new LinkedHashMap<>();

    @Nullable private Runnable pendingSearch;
    private int currentQueryToken = 0;
    private boolean creatingGroup = false;

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser fbUser = FirebaseAuth.getInstance().getCurrentUser();
        if (fbUser == null) {
            finish();
            return;
        }
        myUid = fbUser.getUid();

        binding = ActivityGroupCreateBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarGroupCreate.setNavigationOnClickListener(v -> finish());

        setupRecyclerView();
        setupSearchField();
        setupCreateButton();

        updateCreateButtonState();
        showEmptyState();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        adapter = new UserAdapter(UserAdapter.MODE_SELECT, this::onMemberToggled);
        adapter.setSelectedUids(selectedMembers.keySet());
        binding.rvGroupMembers.setLayoutManager(new LinearLayoutManager(this));
        binding.rvGroupMembers.setAdapter(adapter);
        binding.rvGroupMembers.setHasFixedSize(false);

        // ------------------------------------------------------------
        // CRITICAL: disable the RecyclerView's own scroll.
        // ------------------------------------------------------------
        // The RecyclerView is inside a NestedScrollView. Without this
        // line, the RecyclerView would consume vertical drag gestures
        // before the outer scrollview sees them, producing a "form is
        // stuck" feel when the results list is short (fewer items than
        // would fill its 360dp max-height). With this line, the outer
        // NestedScrollView owns all scrolling and the RecyclerView
        // simply grows to fit its items.
        //
        // setHasFixedSize(false) — the RecyclerView's content height
        // genuinely varies (0 items → 2 items → 20 items), so the
        // fixed-size optimization would cause stale layout bounds.
        // With wrap_content height, RecyclerView needs to re-measure
        // on every data change; setHasFixedSize(false) makes that
        // explicit.
        binding.rvGroupMembers.setNestedScrollingEnabled(false);
    }

    private void setupSearchField() {
        binding.etGroupMemberSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                onQueryChanged(s == null ? "" : s.toString());
            }
        });

        binding.etGroupMemberSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                UiUtils.hideKeyboard(this, binding.etGroupMemberSearch);
                return true;
            }
            return false;
        });
    }

    private void setupCreateButton() {
        binding.btnCreateGroup.setOnClickListener(v -> onCreateClicked());

        binding.etGroupName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                updateCreateButtonState();
            }
        });
    }

    private void updateCreateButtonState() {
        String name = text(binding.etGroupName).trim();
        boolean validName = !name.isEmpty() && name.length() <= MAX_GROUP_NAME_LENGTH;
        boolean hasMembers = !selectedMembers.isEmpty();
        binding.btnCreateGroup.setEnabled(validName && hasMembers && !creatingGroup);
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
            currentQueryToken++;
            adapter.submitList(new ArrayList<>());
            showEmptyState();
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
                        if (binding == null || token != currentQueryToken) return;
                        List<User> users = result != null ? result : new ArrayList<>();
                        adapter.submitList(users);
                        if (users.isEmpty()) showNoResultsState(query);
                        else showResultsState();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null || token != currentQueryToken) return;
                        showNoResultsState(query);
                    }
                });
    }

    // ============================================================
    // Selection
    // ============================================================

    private void onMemberToggled(@NonNull User user) {
        if (binding == null) return;
        if (user.getUid() == null || user.getUid().equals(myUid)) return;

        String uid = user.getUid();
        if (selectedMembers.containsKey(uid)) {
            selectedMembers.remove(uid);
        } else {
            selectedMembers.put(uid, user);
        }

        refreshChips();
        updateCreateButtonState();
        adapter.setSelectedUids(selectedMembers.keySet());
    }

    private void refreshChips() {
        binding.cgSelectedMembers.removeAllViews();
        if (selectedMembers.isEmpty()) {
            binding.cgSelectedMembers.setVisibility(View.GONE);
            return;
        }
        binding.cgSelectedMembers.setVisibility(View.VISIBLE);
        for (Map.Entry<String, User> entry : selectedMembers.entrySet()) {
            final User user = entry.getValue();
            Chip chip = new Chip(this);
            chip.setText(user.getDisplayLabel());
            chip.setCheckable(false);
            chip.setClickable(false);
            chip.setCloseIconVisible(true);
            chip.setCloseIconContentDescription(
                    getString(R.string.group_info_remove_member));
            chip.setOnCloseIconClickListener(v -> onMemberToggled(user));
            binding.cgSelectedMembers.addView(chip);
        }
    }

    // ============================================================
    // State
    // ============================================================

    private void showEmptyState() {
        binding.progressGroup.setVisibility(View.GONE);
        binding.rvGroupMembers.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.VISIBLE);
        binding.tvEmptyTitle.setText(R.string.people_empty_title);
        binding.tvEmptySubtitle.setText(R.string.people_empty_subtitle);
    }

    private void showLoadingState() {
        binding.progressGroup.setVisibility(View.VISIBLE);
        binding.rvGroupMembers.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.GONE);
    }

    private void showResultsState() {
        binding.progressGroup.setVisibility(View.GONE);
        binding.rvGroupMembers.setVisibility(View.VISIBLE);
        binding.llEmptyState.setVisibility(View.GONE);
    }

    private void showNoResultsState(@NonNull String query) {
        binding.progressGroup.setVisibility(View.GONE);
        binding.rvGroupMembers.setVisibility(View.GONE);
        binding.llEmptyState.setVisibility(View.VISIBLE);
        binding.tvEmptyTitle.setText(R.string.people_no_results);
        binding.tvEmptySubtitle.setText(query);
    }

    // ============================================================
    // Create
    // ============================================================

    private void onCreateClicked() {
        if (creatingGroup) return;

        String name = text(binding.etGroupName).trim();
        if (name.isEmpty()) {
            binding.etGroupName.setError(getString(R.string.group_name_required));
            binding.etGroupName.requestFocus();
            return;
        }
        if (name.length() > MAX_GROUP_NAME_LENGTH) {
            binding.etGroupName.setError(getString(R.string.profile_display_name_too_long));
            binding.etGroupName.requestFocus();
            return;
        }
        if (selectedMembers.isEmpty()) {
            Snackbar.make(binding.getRoot(),
                    R.string.people_search_too_short,
                    Snackbar.LENGTH_LONG).show();
            return;
        }

        binding.etGroupName.setError(null);
        creatingGroup = true;
        binding.btnCreateGroup.setEnabled(false);
        binding.progressCreateGroup.setVisibility(View.VISIBLE);

        List<String> memberUids = new ArrayList<>(selectedMembers.keySet());

        // FirestoreHelper.createGroup adds the creator automatically
        // and promotes them to admin.
        FirestoreHelper.get().createGroup(myUid, name, memberUids, null,
                new FirestoreHelper.Callback<Conversation>() {
                    @Override
                    public void onSuccess(@Nullable Conversation result) {
                        if (binding == null) return;
                        binding.progressCreateGroup.setVisibility(View.GONE);
                        creatingGroup = false;
                        if (result == null) {
                            updateCreateButtonState();
                            UiUtils.toast(binding.getRoot(),
                                    getString(R.string.group_create_failed));
                            return;
                        }
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.group_created));
                        openChat(result);
                        finish();
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        binding.progressCreateGroup.setVisibility(View.GONE);
                        creatingGroup = false;
                        updateCreateButtonState();
                        UiUtils.toast(binding.getRoot(),
                                getString(R.string.group_create_failed));
                    }
                });
    }

    private void openChat(@NonNull Conversation conversation) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversation.getConversationId());
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_TYPE, Conversation.TYPE_GROUP);
        startActivity(intent);
    }

    // ============================================================
    // Utility
    // ============================================================

    @NonNull
    private String text(@NonNull EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }
}
