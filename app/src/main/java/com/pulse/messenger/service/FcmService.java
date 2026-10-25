package com.pulse.messenger.service;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.pulse.messenger.R;
import com.pulse.messenger.data.FirestoreHelper;
import com.pulse.messenger.model.Conversation;
import com.pulse.messenger.model.Message;
import com.pulse.messenger.model.User;
import com.pulse.messenger.ui.ChatActivity;

import java.util.Map;

/**
 * Firebase Cloud Messaging receiver — data-only payload handling.
 *
 * <p><b>Architecture summary (per Phase-1 design):</b></p>
 * <ul>
 *   <li>The Cloud Function on the Firebase project triggers on
 *       {@code conversations/{cid}/messages/{mid}}.onCreate and sends
 *       a <b>data-only</b> FCM message to each recipient's
 *       {@code fcmToken}.</li>
 *   <li>This service receives that message via
 *       {@link #onMessageReceived(RemoteMessage)} (data-only messages
 *       are ALWAYS delivered here regardless of app foreground state;
 *       notification payloads are not).</li>
 *   <li>The service checks mute state and user preferences, then
 *       posts a notification built entirely client-side.</li>
 *   <li>Tapping the notification deep-links to
 *       {@link ChatActivity}.</li>
 * </ul>
 *
 * <p><b>Payload contract (the Cloud Function MUST provide these keys):</b></p>
 * <ul>
 *   <li>{@code conversation_id} (required) — Firestore document ID.</li>
 *   <li>{@code sender_id} (required) — Firebase UID of the message sender.</li>
 *   <li>{@code conversation_type} (required) — {@code "private"} or
 *       {@code "group"}. Determines the notification channel and the
 *       title formatting.</li>
 *   <li>{@code type} (required) — message type
 *       ({@code text/image/video/audio/document/voice}), used to
 *       render "📷 Photo" etc. as a body fallback when previews are on
 *       but the message has no caption.</li>
 *   <li>{@code sender_name} (required for DMs; recommended for groups)
 *       — the sender's display name. Client uses it as the title for
 *       DMs and prepends it to the group title for groups.</li>
 *   <li>{@code conversation_name} (required for groups) — the group's
 *       name.</li>
 *   <li>{@code text} (optional) — the message body / caption. If
 *       present AND the user has previews enabled, it becomes the
 *       notification body. If absent (media without caption) or
 *       previews are disabled, the body falls back to a type label or
 *       a generic "New message from ..." message.</li>
 * </ul>
 *
 * <p><b>What this service does NOT do:</b></p>
 * <ul>
 *   <li>It does <b>not</b> send FCM messages. Client-side FCM sending
 *       requires a service-account credential embedded in the APK —
 *       which is a security violation and produces "why can any user
 *       impersonate my app" incidents. All sends go through the Cloud
 *       Function, which uses the Firebase Admin SDK with ADC.</li>
 *   <li>It does <b>not</b> implement Direct Reply. That is a Phase-9
 *       polish item and requires an additional {@code RemoteInput}
 *       action + a "handle reply" flow that writes back to Firestore.</li>
 *   <li>It does <b>not</b> group multiple conversations under one
 *       summary notification. Each conversation produces one
 *       notification (with a stable ID per conversation, so new
 *       messages replace the previous notification for that chat).
 *       Grouping is a Phase-9 polish item.</li>
 *   <li>It does <b>not</b> suppress notifications while the app is in
 *       the foreground. This is a v1 simplification: many messaging
 *       apps do suppress, but suppressing requires cross-activity
 *       state tracking (which conversation is currently visible) —
 *       adding that now would introduce a new static-state coupling
 *       between the service and the activity. Phase-9 will add a
 *       static "active conversation ID" tracker for in-app
 *       suppression.</li>
 * </ul>
 *
 * <p><b>Requires {@code POST_NOTIFICATIONS} on Android 13+:</b> if the
 * permission has not been granted (or the user has disabled
 * notifications in system settings),
 * {@link NotificationManagerCompat#areNotificationsEnabled()} returns
 * {@code false} and the service exits early — silent, but the app
 * behaves correctly (no crash, no broken notification). The runtime
 * permission request should be added in a Phase-9 Settings/Profile
 * flow; the current app posts notifications only when the user has
 * granted the permission via the OS prompt or system settings.</p>
 */
public class FcmService extends FirebaseMessagingService {

    private static final String TAG = "FcmService";

    // Payload keys — must match the Cloud Function's payload.
    private static final String KEY_CONVERSATION_ID   = "conversation_id";
    private static final String KEY_SENDER_ID         = "sender_id";
    private static final String KEY_SENDER_NAME       = "sender_name";
    private static final String KEY_CONVERSATION_NAME = "conversation_name";
    private static final String KEY_CONVERSATION_TYPE = "conversation_type";
    private static final String KEY_TEXT              = "text";
    private static final String KEY_TYPE              = "type";

    // ============================================================
    // Token refresh
    // ============================================================

    /**
     * Called whenever FCM issues a new registration token (app install,
     * data clear, token rotation by FCM itself, restore to a new
     * device). Updates {@code users/{uid}.fcmToken} so the Cloud
     * Function targets the correct device.
     *
     * <p>Fire-and-forget — {@link FirestoreHelper#updateFcmToken} does
     * not expose a callback. If the write fails (no network at
     * rotation time), the next token refresh will overwrite the stale
     * token. The user's next outgoing message also re-writes the token
     * on the send path, as an additional safety net.</p>
     */
    @Override
    public void onNewToken(@NonNull String token) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            return;
        }
        FirestoreHelper.get().updateFcmToken(user.getUid(), token);
    }

    // ============================================================
    // Message handling
    // ============================================================

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (data.isEmpty()) {
            return;
        }

        final String conversationId = data.get(KEY_CONVERSATION_ID);
        final String senderId       = data.get(KEY_SENDER_ID);

        if (isEmpty(conversationId) || isEmpty(senderId)) {
            Log.w(TAG, "Payload missing required key (conversation_id or sender_id)");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // Not signed in — the message is probably stale (was queued
            // while the user was signed out, delivered after a fresh
            // install). Silently ignore.
            return;
        }
        final String myUid = user.getUid();

        // Defensive: never notify the sender about their own message.
        // The Cloud Function should exclude the sender, but a buggy or
        // misconfigured function should not produce self-notifications.
        if (myUid.equals(senderId)) {
            return;
        }

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled — not posting");
            return;
        }

        // goAsync keeps the service alive for up to 10s while we
        // complete the Firestore reads. Without it, the system could
        // kill the service mid-read on low-memory devices.
     final android.app.Service.PendingResult pendingResult = goAsync();

        FirestoreHelper.get().getConversation(conversationId,
                new FirestoreHelper.Callback<Conversation>() {
                    @Override
                    public void onSuccess(@Nullable Conversation conv) {
                        if (conv != null && conv.isMutedFor(myUid)) {
                            pendingResult.finish();
                            return;
                        }
                        FirestoreHelper.get().getUser(myUid,
                                new FirestoreHelper.Callback<User>() {
                                    @Override
                                    public void onSuccess(@Nullable User me) {
                                        postNotification(data, conv, me);
                                        pendingResult.finish();
                                    }
                                    @Override
                                    public void onError(@NonNull Exception e) {
                                        postNotification(data, conv, null);
                                        pendingResult.finish();
                                    }
                                });
                    }
                    @Override
                    public void onError(@NonNull Exception e) {
                        // Conversation doc not readable (deleted, or
                        // rules mismatch). Post with defaults — better
                        // than silently dropping the notification.
                        postNotification(data, null, null);
                        pendingResult.finish();
                    }
                });
    }

    // ============================================================
    // Notification building
    // ============================================================

    /**
     * Builds and posts the notification. Handles all fallbacks for
     * missing payload fields, respects the user's preview preference,
     * and uses a per-conversation notification ID so successive
     * messages replace the previous notification for the same
     * conversation rather than stacking.
     */
    private void postNotification(@NonNull Map<String, String> data,
                                   @Nullable Conversation conv,
                                   @Nullable User me) {
        final String conversationId   = data.get(KEY_CONVERSATION_ID);
        final String senderId         = data.get(KEY_SENDER_ID);
        final String senderName       = data.get(KEY_SENDER_NAME);
        final String conversationName = data.get(KEY_CONVERSATION_NAME);
        final String conversationType = data.get(KEY_CONVERSATION_TYPE);
        final String text             = data.get(KEY_TEXT);
        final String type             = data.get(KEY_TYPE);

        if (isEmpty(conversationId)) return;   // shouldn't happen, defensive

        boolean isGroup = Conversation.TYPE_GROUP.equals(conversationType);

        // -------- Title --------
        String title;
        if (isGroup) {
            String groupPart = !isEmpty(conversationName)
                    ? conversationName : getString(R.string.group_info_title);
            // "Sender Name · Group Name" — standard group format.
            title = !isEmpty(senderName) ? (senderName + " \u00b7 " + groupPart) : groupPart;
        } else {
            title = !isEmpty(senderName) ? senderName : getString(R.string.app_name);
        }

        // -------- Body --------
        boolean previewsEnabled = me == null
                || me.getNotificationPrefs() == null
                || me.getNotificationPrefs().isPreviews();

        String body;
        if (previewsEnabled && !isEmpty(text)) {
            body = text;
        } else if (previewsEnabled) {
            // Media message without caption — show the type label
            String tl = typeLabel(type);
            body = tl.isEmpty() ? getString(R.string.notification_new_message, title) : tl;
        } else {
            // Previews disabled — generic message, no content leak.
            if (isGroup) {
                body = getString(R.string.notification_new_group_message,
                        isEmpty(senderName) ? getString(R.string.app_name) : senderName,
                        isEmpty(conversationName) ? getString(R.string.group_info_title) : conversationName);
            } else {
                body = getString(R.string.notification_new_message,
                        isEmpty(senderName) ? getString(R.string.app_name) : senderName);
            }
        }

        // -------- Deep-link intent --------
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_TYPE,
                conversationType != null ? conversationType : Conversation.TYPE_PRIVATE);
        if (!isGroup && !isEmpty(senderId)) {
            intent.putExtra(ChatActivity.EXTRA_PEER_UID, senderId);
        }
        // CLEAR_TOP reuses an existing ChatActivity if it is already on
        // the stack (the activity is launchMode=singleTop in the
        // manifest), and the intent's new extras trigger onNewIntent.
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int requestCode = conversationId.hashCode();
        PendingIntent pi = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // -------- Channel --------
        String channelId = isGroup
                ? getString(R.string.channel_id_groups)
                : getString(R.string.channel_id_messages);

        // -------- ID & build --------
        int notificationId = (conversationId.hashCode() & 0x7FFFFFFF);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setColor(ContextCompat.getColor(this, R.color.pulse_accent))
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                        .setAutoCancel(true)
                        .setContentIntent(pi)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setWhen(System.currentTimeMillis());

        try {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS permission was revoked between our
            // areNotificationsEnabled() check and the notify() call —
            // extremely unlikely, but the catch keeps the service from
            // crashing if it happens.
            Log.w(TAG, "notify() failed — permission revoked?");
        }
    }

    // ============================================================
    // Utilities
    // ============================================================

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Localized label for a media message type. Same strings used by
     * the chat list previews and MessageAdapter — keeps the
     * notification body consistent with the in-app presentation.
     * Returns an empty string for {@code TYPE_TEXT} (text messages
     * should always have real text; if we hit this branch with a text
     * message, it means the payload was malformed and the fallback
     * "New message from ..." body is used instead).
     */
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
}
