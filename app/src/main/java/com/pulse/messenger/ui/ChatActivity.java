package com.pulse.messenger.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ValueEventListener;
import com.pulse.messenger.R;
import com.pulse.messenger.adapter.MessageAdapter;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.data.PresenceManager;
import com.pulse.messenger.data.StorageHelper;
import com.pulse.messenger.databinding.ActivityChatBinding;
import com.pulse.messenger.databinding.DialogVoiceRecordBinding;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.Message;
import com.pulse.messenger.model.User;
import com.pulse.messenger.util.MediaUtils;
import com.pulse.messenger.util.UiUtils;
import com.pulse.messenger.util.VoiceRecorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Conversation screen.
 *
 * <p><b>This file's scope:</b> text messaging, image messaging (send
 * + receive + view), voice recording (hold-to-record gesture), all
 * message actions (reply / edit / delete / copy), typing indicator
 * (own-write + peer-render), presence, and status icons.</p>
 *
 * <p><b>Phase-8 addition (this file):</b> the attach sheet's "Voice
 * message" option now launches the full voice recording flow —
 * permission request, recording overlay sheet, hold-to-record /
 * slide-to-cancel / release-to-send gesture, upload via
 * {@link StorageHelper}, and Firestore send via the existing pipeline.
 * Recording is cancelled on: tap X, release in cancel zone, drag-down
 * of the sheet, and activity destroy. The 100 ms timer updates the
 * sheet's timer label while recording.</p>
 *
 * <p><b>Still "coming soon":</b> Video, Audio file, and Document
 * attach options.</p>
 */
public class ChatActivity extends AppCompatActivity {

    // Intent extras — must match ChatsFragment's and PeopleFragment's
    // startActivity calls, and the FcmService PendingIntent.
    public static final String EXTRA_CONVERSATION_ID   = "conversation_id";
    public static final String EXTRA_CONVERSATION_TYPE = "conversation_type";
    public static final String EXTRA_PEER_UID          = "peer_uid";

    // ------------------------------------------------------------------
    // Typing constants
    // ------------------------------------------------------------------
    private static final long TYPING_THROTTLE_MS = 3000L;
    private static final long TYPING_CLEAR_MS = 5000L;
    private static final long TYPING_REFRESH_DELAY_MS = 500L;

    private static final int OLDER_PAGE_SIZE = 50;
    private static final int SCROLL_TRIGGER_THRESHOLD = 5;

    // ------------------------------------------------------------------
    // Voice recording constants
    // ------------------------------------------------------------------
    /** How far the user must slide UP from the initial touch point to enter the cancel zone. */
    private static final float VOICE_CANCEL_THRESHOLD_DP = 80f;

    private ActivityChatBinding binding;
    private String myUid;
    private String conversationId;
    private String peerUid;
    private boolean isGroup;

    private MessageAdapter adapter;
    private LinearLayoutManager layoutManager;

    // ------------------------------------------------------------------
    // Remote state
    // ------------------------------------------------------------------
    private LiveData<List<Message>> messagesLiveData;
    private LiveData<Conversation> conversationLiveData;
    private LiveData<User> peerUserLiveData;
    private ValueEventListener peerPresenceListener;

    // ------------------------------------------------------------------
    // Local state
    // ------------------------------------------------------------------
    @Nullable private Conversation currentConversation;
    @Nullable private User peerUser;
    @Nullable private Message replyingTo;
    @Nullable private Message editing;
    @Nullable private String oldestLoadedTimestampKey;
    private boolean peerOnline = false;
    @Nullable private Date peerLastSeen;

    private long lastTypingWriteMs = 0L;
    private boolean typingActive = false;
    private boolean loadingOlderInFlight = false;

    // ------------------------------------------------------------------
    // Upload state (image + voice)
    // ------------------------------------------------------------------
    private boolean uploadingMedia = false;
    @Nullable private Snackbar uploadProgressSnackbar;

    // ------------------------------------------------------------------
    // Typing self-refresh
    // ------------------------------------------------------------------
    @Nullable private Runnable typingRefreshRunnable;

    // ------------------------------------------------------------------
    // Voice recording state
    // ------------------------------------------------------------------
    @Nullable private VoiceRecorder voiceRecorder;
    @Nullable private BottomSheetDialog voiceDialog;
    @Nullable private DialogVoiceRecordBinding voiceBinding;
    @Nullable private Runnable voiceTimerRunnable;
    private final Handler voiceTimerHandler = new Handler(Looper.getMainLooper());
    private long voiceRecordStartMs = 0L;
    private boolean voiceCancelZone = false;
    private float voiceTouchDownY = 0f;
    private int voiceCancelThresholdPx = 0;
    private int voiceHintNormalColor = 0;

    // ============================================================
    // Activity result launchers (registered before onCreate completes)
    // ============================================================

    private final ActivityResultLauncher<PickVisualMediaRequest> photoPicker =
            registerForActivityResult(
                    new ActivityResultContracts.PickVisualMedia(),
                    uri -> {
                        if (uri != null) onPhotoPicked(uri);
                    });

    private final ActivityResultLauncher<String> recordAudioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            showVoiceRecordSheet();
                        } else if (binding != null) {
                            UiUtils.toast(binding.getRoot(),
                                    getString(R.string.voice_mic_permission_denied));
                        }
                    });

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        myUid = user.getUid();

        Intent intent = getIntent();
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID);
        peerUid = intent.getStringExtra(EXTRA_PEER_UID);
        String type = intent.getStringExtra(EXTRA_CONVERSATION_TYPE);
        isGroup = Conversation.TYPE_GROUP.equals(type);

        if (conversationId == null) {
            finish();
            return;
        }

        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.toolbarChat.setNavigationOnClickListener(v -> finish());
        binding.toolbarChat.setOnClickListener(v -> openChatInfo());

        setupRecyclerView();
        setupComposer();
        observeConversation();
        observeMessages();

        if (!isGroup && peerUid != null) {
            observePeerUser(peerUid);
        }

        FirestoreHelper.get().markConversationRead(conversationId, myUid);
    }

    @Override
    protected void onStop() {
        super.onStop();
        clearTypingFlagIfActive();
        if (peerPresenceListener != null && peerUid != null) {
            PresenceManager.get().stopListeningPeer(peerUid, peerPresenceListener);
            peerPresenceListener = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (messagesLiveData != null) messagesLiveData.removeObservers(this);
        if (conversationLiveData != null) conversationLiveData.removeObservers(this);
        if (peerUserLiveData != null) peerUserLiveData.removeObservers(this);
        if (uploadProgressSnackbar != null) {
            uploadProgressSnackbar.dismiss();
            uploadProgressSnackbar = null;
        }
        if (typingRefreshRunnable != null && binding != null) {
            binding.getRoot().removeCallbacks(typingRefreshRunnable);
            typingRefreshRunnable = null;
        }

        // Voice cleanup — dismiss the sheet (which nulls recorder via
        // the onDismiss listener), then release defensively in case
        // dismissal did not run (e.g. activity destroyed mid-creation).
        if (voiceDialog != null) {
            voiceDialog.dismiss();
            voiceDialog = null;
        }
        stopVoiceTimer();
        if (voiceRecorder != null) {
            voiceRecorder.release();
            voiceRecorder = null;
        }
        voiceBinding = null;

        binding = null;
    }

    // ============================================================
    // RecyclerView
    // ============================================================

    private void setupRecyclerView() {
        adapter = new MessageAdapter(myUid, this::onMessageLongPressed);
        layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(layoutManager);
        binding.rvMessages.setAdapter(adapter);

        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy >= 0) return;
                if (loadingOlderInFlight) return;
                if (layoutManager.findFirstVisibleItemPosition() < SCROLL_TRIGGER_THRESHOLD) {
                    loadOlderMessages();
                }
            }
        });
    }

    private void scrollToBottom(boolean smooth) {
        int count = adapter.getItemCount();
        if (count == 0) return;
        if (smooth) {
            binding.rvMessages.smoothScrollToPosition(count - 1);
        } else {
            binding.rvMessages.scrollToPosition(count - 1);
        }
    }

    // ============================================================
    // Composer
    // ============================================================

    private void setupComposer() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                updateSendButtonState();
                handleTypingKeystroke(s == null ? "" : s.toString());
            }
        });

        binding.btnSend.setOnClickListener(v -> sendCurrentInput());
        binding.btnCancelReply.setOnClickListener(v -> clearReplyAndEdit());
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
    }

    private void updateSendButtonState() {
        String text = text(binding.etMessage).trim();
        boolean hasText = !text.isEmpty();
        boolean editingSameText = editing != null
                && editing.getText() != null
                && editing.getText().equals(text);
        boolean enabled = hasText && !editingSameText && !uploadingMedia;
        binding.btnSend.setEnabled(enabled);
    }

    // ============================================================
    // Observers
    // ============================================================

    private void observeConversation() {
        conversationLiveData = FirestoreHelper.get().listenConversation(conversationId);
        conversationLiveData.observe(this, conv -> {
            if (conv == null) return;
            currentConversation = conv;

            if (peerUid != null && adapter != null) {
                adapter.setPeerLastReadAt(
                        conv.getMemberInfoFor(peerUid).getLastReadAt());
            }

            refreshHeaderTitle();
        });
    }

    private void observePeerUser(@NonNull String uid) {
        peerUserLiveData = FirestoreHelper.get().listenUser(uid);
        peerUserLiveData.observe(this, user -> {
            if (user == null) return;
            peerUser = user;
            if (adapter != null) adapter.setPeerDisplayName(user.getDisplayLabel());
            refreshHeaderTitle();
            refreshPeerPresence();
        });
    }

    private void observeMessages() {
        messagesLiveData = FirestoreHelper.get().listenRecentMessages(conversationId);
        messagesLiveData.observe(this, messages -> {
            if (messages == null) return;

            if (!messages.isEmpty()) {
                Message oldest = messages.get(messages.size() - 1);
                if (oldest.getTimestamp() != null) {
                    oldestLoadedTimestampKey = String.valueOf(oldest.getTimestamp().getTime());
                }
            }

            List<Message> chronological = new ArrayList<>(messages);
            Collections.reverse(chronological);
            adapter.submitList(chronological);

            if (currentConversation != null && peerUid != null) {
                adapter.setPeerLastReadAt(
                        currentConversation.getMemberInfoFor(peerUid).getLastReadAt());
            }

            showEmptyStateIfNeeded(chronological);

            binding.rvMessages.post(() -> {
                if (isNearBottom()) scrollToBottom(true);
            });
        });
    }

    private boolean isNearBottom() {
        return layoutManager.findLastVisibleItemPosition()
                >= adapter.getItemCount() - 2;
    }

    private void showEmptyStateIfNeeded(@NonNull List<Message> messages) {
        if (messages.isEmpty()) {
            binding.llEmptyState.setVisibility(View.VISIBLE);
            binding.rvMessages.setVisibility(View.GONE);
        } else {
            binding.llEmptyState.setVisibility(View.GONE);
            binding.rvMessages.setVisibility(View.VISIBLE);
        }
    }

    // ============================================================
    // Header
    // ============================================================

    private void refreshHeaderTitle() {
        if (currentConversation == null) return;
        String title;
        String photoUrl;

        if (isGroup) {
            title = currentConversation.getName() != null
                    ? currentConversation.getName() : getString(R.string.group_info_title);
            photoUrl = currentConversation.getPhotoUrl();
        } else {
            if (peerUser != null) {
                title = peerUser.getDisplayLabel();
                photoUrl = peerUser.getPhotoUrl();
            } else {
                title = getString(R.string.state_loading);
                photoUrl = null;
            }
        }

        binding.tvChatTitle.setText(title);
        renderHeaderAvatar(photoUrl, title);
        updateHeaderSubtitle();
    }

    private void renderHeaderAvatar(@Nullable String photoUrl, @NonNull String label) {
        String seed = isGroup
                ? (conversationId != null ? conversationId : "group")
                : (peerUid != null ? peerUid : "peer");

        String initialText = UiUtils.avatarInitial(label);
        int initialColor = UiUtils.resolveAvatarColor(this, seed);
        binding.tvHeaderInitials.setText(initialText);
        ViewCompat.setBackgroundTintList(
                binding.tvHeaderInitials,
                android.content.res.ColorStateList.valueOf(initialColor));

        if (photoUrl != null && !photoUrl.isEmpty()) {
            binding.ivHeaderAvatar.setVisibility(View.VISIBLE);
            com.bumptech.glide.Glide.with(binding.ivHeaderAvatar)
                    .load(photoUrl)
                    .transform(new com.bumptech.glide.load.resource.bitmap.CircleCrop())
                    .into(binding.ivHeaderAvatar);
        } else {
            binding.ivHeaderAvatar.setVisibility(View.GONE);
        }
    }

    private void updateHeaderSubtitle() {
        String subtitle = null;

        if (currentConversation != null
                && currentConversation.hasLiveTyping(myUid, TYPING_CLEAR_MS)) {
            subtitle = getString(R.string.chat_typing);
        }

        if (subtitle == null && !isGroup && peerUser != null) {
            String visibility = peerUser.getPrivacy() != null
                    ? peerUser.getPrivacy().getOnline()
                    : User.Privacy.EVERYONE;
            if (User.Privacy.NOBODY.equals(visibility)) {
                subtitle = null;
            } else if (peerOnline) {
                subtitle = getString(R.string.chat_online);
            } else {
                subtitle = UiUtils.formatLastSeen(this, peerLastSeen);
            }
        }

        if (subtitle == null || subtitle.isEmpty()) {
            binding.tvChatSubtitle.setVisibility(View.GONE);
        } else {
            binding.tvChatSubtitle.setVisibility(View.VISIBLE);
            binding.tvChatSubtitle.setText(subtitle);
        }

        scheduleTypingRefreshIfNeeded();
    }

    // ============================================================
    // Typing self-refresh
    // ============================================================

    private void scheduleTypingRefreshIfNeeded() {
        if (binding == null) return;

        if (typingRefreshRunnable != null) {
            binding.getRoot().removeCallbacks(typingRefreshRunnable);
            typingRefreshRunnable = null;
        }

        if (currentConversation == null
                || !currentConversation.hasLiveTyping(myUid, TYPING_CLEAR_MS)) {
            return;
        }

        typingRefreshRunnable = () -> {
            typingRefreshRunnable = null;
            if (binding != null) updateHeaderSubtitle();
        };
        binding.getRoot().postDelayed(typingRefreshRunnable,
                TYPING_CLEAR_MS + TYPING_REFRESH_DELAY_MS);
    }

    // ============================================================
    // Presence
    // ============================================================

    private void refreshPeerPresence() {
        if (peerUid == null) return;
        if (peerPresenceListener != null) {
            PresenceManager.get().stopListeningPeer(peerUid, peerPresenceListener);
        }
        peerPresenceListener = PresenceManager.get().listenPeerPresence(peerUid,
                (online, lastSeen) -> {
                    peerOnline = online;
                    peerLastSeen = lastSeen;
                    updateHeaderSubtitle();
                });
    }

    // ============================================================
    // Typing — own
    // ============================================================

    private void handleTypingKeystroke(@NonNull String currentText) {
        if (currentText.isEmpty()) {
            clearTypingFlagIfActive();
            return;
        }
        long now = System.currentTimeMillis();
        if (!typingActive || now - lastTypingWriteMs > TYPING_THROTTLE_MS) {
            lastTypingWriteMs = now;
            typingActive = true;
            FirestoreHelper.get().setTyping(conversationId, myUid, true);
        }
    }

    private void clearTypingFlagIfActive() {
        if (!typingActive) return;
        typingActive = false;
        FirestoreHelper.get().setTyping(conversationId, myUid, false);
    }

    // ============================================================
    // Pagination
    // ============================================================

    private void loadOlderMessages() {
        if (loadingOlderInFlight) return;
        if (oldestLoadedTimestampKey == null) return;

        loadingOlderInFlight = true;
        Date oldest = new Date(Long.parseLong(oldestLoadedTimestampKey));

        FirestoreHelper.get().loadOlderMessages(conversationId, oldest, OLDER_PAGE_SIZE,
                new FirestoreHelper.Callback<List<Message>>() {
                    @Override
                    public void onSuccess(@Nullable List<Message> result) {
                        loadingOlderInFlight = false;
                        if (binding == null) return;
                        if (result == null || result.isEmpty()) {
                            oldestLoadedTimestampKey = null;
                            return;
                        }
                        List<Message> chronological = new ArrayList<>(result);
                        Collections.reverse(chronological);
                        adapter.prependList(chronological);

                        Message oldestNew = chronological.get(0);
                        if (oldestNew.getTimestamp() != null) {
                            oldestLoadedTimestampKey =
                                    String.valueOf(oldestNew.getTimestamp().getTime());
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        loadingOlderInFlight = false;
                    }
                });
    }

    // ============================================================
    // Sending — text
    // ============================================================

    private void sendCurrentInput() {
        if (uploadingMedia) return;
        String body = text(binding.etMessage).trim();
        if (body.isEmpty()) return;

        if (editing != null) {
            commitEdit(editing, body);
            return;
        }

        Message reply = replyingTo;
        binding.etMessage.setText("");
        clearReplyAndEdit();

        Message msg = Message.newText(myUid, body, reply != null
                ? Message.ReplyTo.from(reply) : null);

        FirestoreHelper.get().sendMessage(conversationId, msg,
                new FirestoreHelper.Callback<Message>() {
                    @Override public void onSuccess(@Nullable Message result) { }
                    @Override public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        Snackbar.make(binding.getRoot(),
                                R.string.error_send_failed,
                                Snackbar.LENGTH_LONG).show();
                    }
                });
    }

    private void commitEdit(@NonNull Message original, @NonNull String newText) {
        clearReplyAndEdit();
        FirestoreHelper.get().editMessage(conversationId,
                original.getMessageId(), newText,
                new FirestoreHelper.Callback<Void>() {
                    @Override public void onSuccess(@Nullable Void result) { }
                    @Override public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
                    }
                });
    }

    // ============================================================
    // Reply / edit state
    // ============================================================

    private void startReply(@NonNull Message original) {
        replyingTo = original;
        editing = null;
        showReplyStrip(original);
    }

    private void startEdit(@NonNull Message original) {
        editing = original;
        replyingTo = null;
        binding.etMessage.setText(original.getText() != null ? original.getText() : "");
        binding.etMessage.setSelection(binding.etMessage.getText() != null
                ? binding.etMessage.getText().length() : 0);
        UiUtils.showKeyboard(this, binding.etMessage);
        showEditStrip();
    }

    private void clearReplyAndEdit() {
        replyingTo = null;
        editing = null;
        binding.llReplyStrip.setVisibility(View.GONE);
        binding.etMessage.setText("");
    }

    private void showReplyStrip(@NonNull Message original) {
        binding.llReplyStrip.setVisibility(View.VISIBLE);
        binding.tvReplySender.setText(replySenderLabel(original));
        String preview = original.getPreviewText();
        if (TextUtils.isEmpty(preview)) {
            preview = typeLabel(original.getType());
        }
        binding.tvReplyText.setText(preview);
    }

    private void showEditStrip() {
        binding.llReplyStrip.setVisibility(View.VISIBLE);
        binding.tvReplySender.setText(R.string.msg_editing);
        binding.tvReplyText.setText("");
    }

    @NonNull
    private String replySenderLabel(@NonNull Message original) {
        if (myUid.equals(original.getSenderId())) return "You";
        if (peerUser != null && !isGroup) return peerUser.getDisplayLabel();
        return "";
    }

    @NonNull
    private String typeLabel(@Nullable String type) {
        if (type == null) return "";
        switch (type) {
            case Message.TYPE_IMAGE:    return getString(R.string.conversation_type_image);
            case Message.TYPE_VIDEO:    return getString(R.string.conversation_type_video);
            case Message.TYPE_AUDIO:    return getString(R.string.conversation_type_audio);
            case Message.TYPE_VOICE:    return getString(R.string.conversation_type_voice);
            case Message.TYPE_DOCUMENT: return getString(R.string.conversation_type_document);
            default:                    return "";
        }
    }

    // ============================================================
    // Attach menu
    // ============================================================

    private void showAttachSheet() {
        if (binding == null || uploadingMedia) return;

        View sheetView = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_attach, binding.getRoot(), false);

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        dialog.setContentView(sheetView);

        View optionPhoto    = sheetView.findViewById(R.id.option_photo);
        View optionVideo    = sheetView.findViewById(R.id.option_video);
        View optionAudio    = sheetView.findViewById(R.id.option_audio);
        View optionDocument = sheetView.findViewById(R.id.option_document);
        View optionVoice    = sheetView.findViewById(R.id.option_voice);

        if (optionPhoto != null) {
            optionPhoto.setOnClickListener(v -> {
                dialog.dismiss();
                photoPicker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            });
        }
        if (optionVideo != null) {
            optionVideo.setOnClickListener(v -> {
                dialog.dismiss();
                showNotYetAvailable(R.string.attach_video);
            });
        }
        if (optionAudio != null) {
            optionAudio.setOnClickListener(v -> {
                dialog.dismiss();
                showNotYetAvailable(R.string.attach_audio);
            });
        }
        if (optionDocument != null) {
            optionDocument.setOnClickListener(v -> {
                dialog.dismiss();
                showNotYetAvailable(R.string.attach_document);
            });
        }
        if (optionVoice != null) {
            optionVoice.setOnClickListener(v -> {
                dialog.dismiss();
                startVoiceRecordingFlow();
            });
        }

        dialog.show();
    }

    private void showNotYetAvailable(int typeLabelRes) {
        if (binding == null) return;
        String msg = getString(typeLabelRes) + " \u2014 coming soon";
        Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_SHORT).show();
    }

    // ============================================================
    // Image upload
    // ============================================================

    private void onPhotoPicked(@NonNull Uri uri) {
        if (binding == null) return;

        long size = MediaUtils.querySize(this, uri);
        if (size > 0 && !MediaUtils.isWithinLimit(size, Message.TYPE_IMAGE)) {
            UiUtils.toast(binding.getRoot(),
                    getString(R.string.error_file_too_large,
                            UiUtils.formatFileSize(MediaUtils.MAX_IMAGE_BYTES)));
            return;
        }

        uploadAndSendMedia(uri, Message.TYPE_IMAGE);
    }

    // ============================================================
    // Shared upload/send pipeline (image + voice)
    // ============================================================

    private void uploadAndSendMedia(@NonNull Uri uri, @NonNull String type) {
        if (binding == null || uploadingMedia) return;

        setUploadingState(true);

        uploadProgressSnackbar = Snackbar.make(
                binding.getRoot(),
                getString(R.string.attach_uploading, 0),
                Snackbar.LENGTH_INDEFINITE);
        uploadProgressSnackbar.show();

        StorageHelper.get().uploadMedia(
                this, conversationId, uri, type,
                new StorageHelper.ProgressCallback<Message.Media>() {
                    @Override
                    public void onProgress(int percent) {
                        if (uploadProgressSnackbar != null) {
                            uploadProgressSnackbar.setText(
                                    getString(R.string.attach_uploading, percent));
                        }
                    }

                    @Override
                    public void onSuccess(@NonNull Message.Media media) {
                        if (binding == null) {
                            dismissUploadSnackbar();
                            return;
                        }
                        Message msg = Message.newMedia(myUid, type, media, null, null);
                        FirestoreHelper.get().sendMessage(conversationId, msg,
                                new FirestoreHelper.Callback<Message>() {
                                    @Override
                                    public void onSuccess(@Nullable Message result) {
                                        dismissUploadSnackbar();
                                        setUploadingState(false);
                                    }
                                    @Override
                                    public void onError(@NonNull Exception e) {
                                        dismissUploadSnackbar();
                                        setUploadingState(false);
                                        if (binding == null) return;
                                        Snackbar.make(binding.getRoot(),
                                                R.string.error_send_failed,
                                                Snackbar.LENGTH_LONG).show();
                                    }
                                });
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        dismissUploadSnackbar();
                        setUploadingState(false);
                        if (binding == null) return;
                        Snackbar.make(binding.getRoot(),
                                R.string.attach_upload_failed,
                                Snackbar.LENGTH_LONG).show();
                    }
                });
    }

    private void setUploadingState(boolean uploading) {
        uploadingMedia = uploading;
        if (binding == null) return;
        binding.btnAttach.setEnabled(!uploading);
        binding.etMessage.setEnabled(!uploading);
        updateSendButtonState();
    }

    private void dismissUploadSnackbar() {
        if (uploadProgressSnackbar != null) {
            uploadProgressSnackbar.dismiss();
            uploadProgressSnackbar = null;
        }
    }

    // ============================================================
    // Voice recording flow
    // ============================================================

    /**
     * Entry point from the attach sheet's "Voice message" option.
     * Checks RECORD_AUDIO permission; if missing, requests it and
     * defers to the permission launcher's callback; otherwise shows
     * the recording sheet immediately.
     */
    private void startVoiceRecordingFlow() {
        if (binding == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        showVoiceRecordSheet();
    }

    private void showVoiceRecordSheet() {
        if (binding == null || voiceDialog != null) return;

        voiceRecorder = new VoiceRecorder(this);

        voiceBinding = DialogVoiceRecordBinding.inflate(LayoutInflater.from(this));
        voiceDialog = new BottomSheetDialog(this);
        voiceDialog.setContentView(voiceBinding.getRoot());
        voiceDialog.setCancelable(true);
        voiceDialog.setCanceledOnTouchOutside(true);

        voiceHintNormalColor = voiceBinding.tvVoiceHint.getCurrentTextColor();
        voiceCancelThresholdPx = UiUtils.dp(this, VOICE_CANCEL_THRESHOLD_DP);

        voiceBinding.btnVoiceCancel.setOnClickListener(v -> cancelRecording());
        voiceBinding.btnVoiceRecord.setOnTouchListener(this::onMicTouch);

        voiceDialog.setOnDismissListener(d -> {
            stopVoiceTimer();
            if (voiceRecorder != null) {
                if (voiceRecorder.isRecording()) voiceRecorder.cancel();
                voiceRecorder.release();
                voiceRecorder = null;
            }
            voiceDialog = null;
            voiceBinding = null;
            voiceCancelZone = false;
        });

        updateVoiceHint();
        voiceDialog.show();
    }

    private void beginRecording() {
        if (voiceRecorder == null) return;
        if (voiceRecorder.isRecording()) return;
        try {
            voiceRecorder.start();
            startVoiceTimer();
            updateVoiceHint();
        } catch (SecurityException e) {
            dismissVoiceSheet();
            if (binding == null) return;
            UiUtils.toast(binding.getRoot(),
                    getString(R.string.voice_mic_permission_denied));
        } catch (Exception e) {
            dismissVoiceSheet();
            if (binding == null) return;
            UiUtils.toast(binding.getRoot(),
                    getString(R.string.voice_recorder_unavailable));
        }
    }

    private void endRecordingAndSend() {
        if (voiceRecorder == null || !voiceRecorder.isRecording()) return;
        stopVoiceTimer();
        updateVoiceHint();  // shows normal state while finalizing

        voiceRecorder.stop(new VoiceRecorder.Callback() {
            @Override
            public void onStopped(@NonNull VoiceRecorder.Recording recording) {
                dismissVoiceSheet();
                if (binding == null) return;
                Uri uri = Uri.fromFile(recording.file);
                uploadAndSendMedia(uri, Message.TYPE_VOICE);
            }

            @Override
            public void onError(@NonNull Exception e) {
                dismissVoiceSheet();
                if (binding == null) return;
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("too short")) {
                    UiUtils.toast(binding.getRoot(),
                            getString(R.string.voice_recording_too_short));
                } else {
                    UiUtils.toast(binding.getRoot(),
                            getString(R.string.error_generic));
                }
            }
        });
    }

    private void cancelRecording() {
        stopVoiceTimer();
        if (voiceRecorder != null && voiceRecorder.isRecording()) {
            voiceRecorder.cancel();
        }
        dismissVoiceSheet();
    }

    private void dismissVoiceSheet() {
        if (voiceDialog != null) {
            voiceDialog.dismiss();
            // onDismiss listener nulls voiceDialog / voiceBinding / voiceRecorder.
        }
        stopVoiceTimer();
    }

    private void updateVoiceHint() {
        if (voiceBinding == null) return;
        boolean recording = voiceRecorder != null && voiceRecorder.isRecording();

        if (!recording) {
            voiceBinding.tvVoiceHint.setText(R.string.voice_record_start);
            voiceBinding.tvVoiceHint.setTextColor(voiceHintNormalColor);
        } else if (voiceCancelZone) {
            voiceBinding.tvVoiceHint.setText(R.string.dialog_cancel);
            voiceBinding.tvVoiceHint.setTextColor(
                    ContextCompat.getColor(this, R.color.status_failed));
        } else {
            voiceBinding.tvVoiceHint.setText(R.string.voice_slide_to_cancel);
            voiceBinding.tvVoiceHint.setTextColor(voiceHintNormalColor);
        }
    }

    private void startVoiceTimer() {
        stopVoiceTimer();
        voiceRecordStartMs = System.currentTimeMillis();
        voiceTimerRunnable = new Runnable() {
            @Override public void run() {
                if (voiceBinding == null) return;
                long elapsed = System.currentTimeMillis() - voiceRecordStartMs;
                voiceBinding.tvVoiceTimer.setText(UiUtils.formatDuration(elapsed));
                voiceTimerHandler.postDelayed(this, 100L);
            }
        };
        voiceTimerHandler.post(voiceTimerRunnable);
    }

    private void stopVoiceTimer() {
        if (voiceTimerRunnable != null) {
            voiceTimerHandler.removeCallbacks(voiceTimerRunnable);
            voiceTimerRunnable = null;
        }
    }

    /**
     * Touch handler for the big mic button. Handles the hold-to-
     * record / slide-up-to-cancel / release-to-send gesture.
     */
    private boolean onMicTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                voiceTouchDownY = event.getRawY();
                voiceCancelZone = false;
                beginRecording();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (voiceRecorder == null || !voiceRecorder.isRecording()) return true;
                float distanceUp = voiceTouchDownY - event.getRawY();
                boolean inZone = distanceUp > voiceCancelThresholdPx;
                if (inZone != voiceCancelZone) {
                    voiceCancelZone = inZone;
                    updateVoiceHint();
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (voiceRecorder == null || !voiceRecorder.isRecording()) return true;
                if (voiceCancelZone) {
                    cancelRecording();
                } else {
                    endRecordingAndSend();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                if (voiceRecorder != null && voiceRecorder.isRecording()) {
                    cancelRecording();
                }
                return true;
        }
        return false;
    }

    // ============================================================
    // Message actions (long-press menu)
    // ============================================================

    private void onMessageLongPressed(@NonNull Message msg, @NonNull View anchor) {
        if (msg.isTombstone()) {
            showDeleteForMeOnly(anchor, msg);
            return;
        }

        boolean isMine = myUid.equals(msg.getSenderId());
        boolean canEdit = isMine && msg.isEditableBy(myUid, System.currentTimeMillis())
                && Message.TYPE_TEXT.equals(msg.getType());

        List<String> labels = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();

        labels.add(getString(R.string.msg_action_reply));
        ids.add(R.id.action_reply);

        if (Message.TYPE_TEXT.equals(msg.getType()) && !TextUtils.isEmpty(msg.getText())) {
            labels.add(getString(R.string.msg_action_copy));
            ids.add(R.id.action_copy);
        }
        if (canEdit) {
            labels.add(getString(R.string.msg_action_edit));
            ids.add(R.id.action_edit);
        }
        labels.add(getString(R.string.msg_action_delete));
        ids.add(R.id.action_delete);

        new MaterialAlertDialogBuilder(this)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int id = ids.get(which);
                    if (id == R.id.action_reply) startReply(msg);
                    else if (id == R.id.action_copy) copyMessage(msg);
                    else if (id == R.id.action_edit) startEdit(msg);
                    else if (id == R.id.action_delete) confirmDelete(msg);
                })
                .show();
    }

    private void copyMessage(@NonNull Message msg) {
        if (TextUtils.isEmpty(msg.getText())) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", msg.getText()));
            UiUtils.toast(binding.getRoot(), getString(R.string.msg_copied_toast));
        }
    }

    private void confirmDelete(@NonNull Message msg) {
        boolean isMine = myUid.equals(msg.getSenderId());
        boolean canDeleteForEveryone = isMine && msg.isEditableBy(myUid, System.currentTimeMillis());

        if (!canDeleteForEveryone) {
            showDeleteForMeOnly(binding.getRoot(), msg);
            return;
        }

        String[] options = {
                getString(R.string.msg_action_delete_for_me),
                getString(R.string.msg_action_delete_for_everyone),
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.msg_action_delete)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) deleteForMe(msg);
                    else deleteForEveryone(msg);
                })
                .show();
    }

    private void showDeleteForMeOnly(@NonNull View anchor, @NonNull Message msg) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.msg_action_delete_for_me)
                .setMessage(R.string.msg_delete_for_me_confirm_message)
                .setNegativeButton(R.string.dialog_cancel, null)
                .setPositiveButton(R.string.dialog_delete, (d, w) -> deleteForMe(msg))
                .show();
    }

    private void deleteForMe(@NonNull Message msg) {
        FirestoreHelper.get().deleteMessageForMe(conversationId, msg.getMessageId(), myUid,
                new FirestoreHelper.Callback<Void>() {
                    @Override public void onSuccess(@Nullable Void result) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(), getString(R.string.msg_deleted_toast));
                    }
                    @Override public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
                    }
                });
    }

    private void deleteForEveryone(@NonNull Message msg) {
        FirestoreHelper.get().deleteMessageForEveryone(conversationId, msg.getMessageId(),
                new FirestoreHelper.Callback<Void>() {
                    @Override public void onSuccess(@Nullable Void result) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(), getString(R.string.msg_deleted_toast));

                        if (msg.getMedia() != null && msg.getMedia().getStoragePath() != null) {
                            StorageHelper.get().deleteObject(
                                    msg.getMedia().getStoragePath(),
                                    new StorageHelper.SimpleCallback() {
                                        @Override public void onSuccess() { }
                                        @Override public void onError(@NonNull Exception e) { }
                                    });
                        }
                    }
                    @Override public void onError(@NonNull Exception e) {
                        if (binding == null) return;
                        UiUtils.toast(binding.getRoot(), getString(R.string.error_generic));
                    }
                });
    }

    // ============================================================
    // Chat info (tap header)
    // ============================================================

    private void openChatInfo() {
        // Phase-9: for groups, open GroupInfoActivity; for 1:1, open
        // a peer profile view. Currently a no-op.
    }

    // ============================================================
    // Utility
    // ============================================================

    @NonNull
    private String text(@NonNull EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString();
    }
}
