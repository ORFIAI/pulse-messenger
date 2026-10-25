package com.pulse.messenger.service;

import android.app.PendingIntent;
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

public class FcmService extends FirebaseMessagingService {

    private static final String TAG = "FcmService";

    private static final String KEY_CONVERSATION_ID   = "conversation_id";
    private static final String KEY_SENDER_ID         = "sender_id";
    private static final String KEY_SENDER_NAME       = "sender_name";
    private static final String KEY_CONVERSATION_NAME = "conversation_name";
    private static final String KEY_CONVERSATION_TYPE = "conversation_type";
    private static final String KEY_TEXT              = "text";
    private static final String KEY_TYPE              = "type";

    @Override
    public void onNewToken(@NonNull String token) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        FirestoreHelper.get().updateFcmToken(user.getUid(), token);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Map<String, String> data = message.getData();
        if (data.isEmpty()) return;

        final String conversationId = data.get(KEY_CONVERSATION_ID);
        final String senderId       = data.get(KEY_SENDER_ID);

        if (isEmpty(conversationId) || isEmpty(senderId)) {
            Log.w(TAG, "Payload missing required key");
            return;
        }

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        final String myUid = user.getUid();

        if (myUid.equals(senderId)) return;

        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            Log.d(TAG, "Notifications disabled — not posting");
            return;
        }

        // We do NOT need goAsync() here because this is a Service, not a BroadcastReceiver.
        FirestoreHelper.get().getConversation(conversationId,
                new FirestoreHelper.Callback<Conversation>() {
                    @Override
                    public void onSuccess(@Nullable Conversation conv) {
                        if (conv != null && conv.isMutedFor(myUid)) {
                            return;
                        }
                        FirestoreHelper.get().getUser(myUid,
                                new FirestoreHelper.Callback<User>() {
                                    @Override
                                    public void onSuccess(@Nullable User me) {
                                        postNotification(data, conv, me);
                                    }
                                    @Override
                                    public void onError(@NonNull Exception e) {
                                        postNotification(data, conv, null);
                                    }
                                });
                    }
                    @Override
                    public void onError(@NonNull Exception e) {
                        postNotification(data, null, null);
                    }
                });
    }

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

        if (isEmpty(conversationId)) return;

        boolean isGroup = Conversation.TYPE_GROUP.equals(conversationType);

        String title;
        if (isGroup) {
            String groupPart = !isEmpty(conversationName)
                    ? conversationName : getString(R.string.group_info_title);
            title = !isEmpty(senderName) ? (senderName + " \u00b7 " + groupPart) : groupPart;
        } else {
            title = !isEmpty(senderName) ? senderName : getString(R.string.app_name);
        }

        boolean previewsEnabled = me == null
                || me.getNotificationPrefs() == null
                || me.getNotificationPrefs().isPreviews();

        String body;
        if (previewsEnabled && !isEmpty(text)) {
            body = text;
        } else if (previewsEnabled) {
            String tl = typeLabel(type);
            body = tl.isEmpty() ? getString(R.string.notification_new_message, title) : tl;
        } else {
            if (isGroup) {
                body = getString(R.string.notification_new_group_message,
                        isEmpty(senderName) ? getString(R.string.app_name) : senderName,
                        isEmpty(conversationName) ? getString(R.string.group_info_title) : conversationName);
            } else {
                body = getString(R.string.notification_new_message,
                        isEmpty(senderName) ? getString(R.string.app_name) : senderName);
            }
        }

        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        intent.putExtra(ChatActivity.EXTRA_CONVERSATION_TYPE,
                conversationType != null ? conversationType : Conversation.TYPE_PRIVATE);
        if (!isGroup && !isEmpty(senderId)) {
            intent.putExtra(ChatActivity.EXTRA_PEER_UID, senderId);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int requestCode = conversationId.hashCode();
        PendingIntent pi = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String channelId = isGroup
                ? getString(R.string.channel_id_groups)
                : getString(R.string.channel_id_messages);

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
            Log.w(TAG, "notify() failed — permission revoked?");
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.trim().isEmpty();
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
}
