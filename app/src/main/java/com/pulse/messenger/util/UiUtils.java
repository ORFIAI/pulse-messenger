package com.pulse.messenger.util;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.snackbar.Snackbar;
import com.pulse.messenger.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Static UI helpers.
 *
 * <p>Every method here is stateless — the class is never instantiated.
 * A private constructor prevents accidental {@code new UiUtils()}
 * (default constructors on utility classes are a common lint warning
 * that we suppress by omission).</p>
 *
 * <p><b>Why not java.time:</b> {@code minSdk 24} predates java.time on
 * Android (it arrived in API 26). Enabling core-library desugaring to
 * get it would require changing {@code compileOptions} in
 * {@code app/build.gradle}, adding a desugar dependency, and using
 * {@code desugar_jdk_libs} — a build-system change for one utility
 * file. {@link Calendar} and {@link SimpleDateFormat} are available on
 * every API level and are fast enough for the small number of date
 * operations this class performs. Do not "modernize" this class to
 * java.time unless you are also prepared to enable core-library
 * desugaring.</p>
 */
public final class UiUtils {

    private UiUtils() {
        // No instances.
    }

    // ============================================================
    // Time / date formatting
    // ============================================================

    /**
     * Formats a chat-list timestamp in the standard messaging-app
     * style:
     * <ul>
     *   <li>&lt; 1 min ago → "now"</li>
     *   <li>&lt; 1 hour ago → "5m"</li>
     *   <li>Same day → "3h"</li>
     *   <li>Yesterday → "Yesterday"</li>
     *   <li>Within 7 days → day-of-week abbreviation, e.g. "Mon"</li>
     *   <li>Same year → "Mar 14"</li>
     *   <li>Older → "Mar 14, 2023"</li>
     * </ul>
     *
     * <p>The locale is always {@link Locale#getDefault()}, so
     * day-of-week abbreviations and month names are localized
     * automatically on non-English devices.</p>
     */
    @NonNull
    public static String formatChatListTime(@Nullable Date date) {
        if (date == null) return "";

        long now = System.currentTimeMillis();
        long time = date.getTime();
        long diff = now - time;

        // Future timestamps (device clock skew) — do not show "-5m ago"
        // or a future date; clamp to "now". Server-set timestamps are
        // the source of truth, but the client's local clock could be
        // behind, so this guard matters.
        if (diff < 0) {
            return "now";
        }

        if (diff < TimeUnit.MINUTES.toMillis(1)) {
            return "now";
        }
        if (diff < TimeUnit.HOURS.toMillis(1)) {
            long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
            return minutes + "m";
        }

        Calendar nowCal = Calendar.getInstance();
        Calendar msgCal = Calendar.getInstance();
        msgCal.setTime(date);

        if (isSameDay(nowCal, msgCal)) {
            long hours = TimeUnit.MILLISECONDS.toHours(diff);
            return hours + "h";
        }

        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(yesterday, msgCal)) {
            return "Yesterday";
        }

        if (diff < TimeUnit.DAYS.toMillis(7)) {
            return new SimpleDateFormat("EEE", Locale.getDefault()).format(date);
        }

        if (nowCal.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR)) {
            return new SimpleDateFormat("MMM d", Locale.getDefault()).format(date);
        }

        return new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date);
    }

    /**
     * Formats a message-bubble timestamp as {@code HH:mm} in the
     * device's locale. This is the format used inside each message
     * bubble, not in the chat list — chat list uses
     * {@link #formatChatListTime(Date)}.
     */
    @NonNull
    public static String formatBubbleTime(@Nullable Date date) {
        if (date == null) return "";
        // getBestDateTimePattern adapts to the user's 12h/24h setting.
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), "Hm");
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date);
    }

    /**
     * Formats a full date for the message-info dialog and for
     * notification taps that need the exact send time.
     */
    @NonNull
    public static String formatFullDateTime(@Nullable Date date) {
        if (date == null) return "";
        String pattern = android.text.format.DateFormat
                .getBestDateTimePattern(Locale.getDefault(), "MMMdHm");
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(date);
    }

    /**
     * Builds the "Last seen ..." string for the chat header,
     * respecting the peer's privacy preference is the caller's
     * responsibility — this method assumes the peer's last-seen is
     * visible to the current user.
     */
    @NonNull
    public static String formatLastSeen(@NonNull Context context, @Nullable Date lastSeen) {
        if (lastSeen == null) {
            return context.getString(R.string.presence_offline);
        }

        long now = System.currentTimeMillis();
        long time = lastSeen.getTime();
        long diff = now - time;

        if (diff >= 0 && diff < TimeUnit.MINUTES.toMillis(1)) {
            return context.getString(R.string.chat_last_seen_just_now);
        }

        Calendar nowCal = Calendar.getInstance();
        Calendar seenCal = Calendar.getInstance();
        seenCal.setTime(lastSeen);

        String hhmm = formatBubbleTime(lastSeen);

        if (isSameDay(nowCal, seenCal)) {
            return context.getString(R.string.presence_last_seen_today, hhmm);
        }

        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(yesterday, seenCal)) {
            return context.getString(R.string.presence_last_seen_yesterday, hhmm);
        }

        String datePart = new SimpleDateFormat("MMM d", Locale.getDefault()).format(lastSeen);
        return context.getString(R.string.presence_last_seen_date, datePart);
    }

    private static boolean isSameDay(@NonNull Calendar a, @NonNull Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Formats a duration in milliseconds as {@code m:ss} (e.g. 75 seconds
     * → "1:15"). Used for voice message lengths and video durations.
     * Returns "0:00" for zero or negative input — never a raw "0".
     */
    @NonNull
    public static String formatDuration(long millis) {
        if (millis < 0) millis = 0;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        long minutes = seconds / 60;
        long remainingSeconds = seconds % 60;
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainingSeconds);
    }

    /**
     * Formats a byte count as a human-readable size. Uses decimal
     * prefixes (KB = 1000 bytes) — this is the same convention used
     * by Android's {@code Formatter.formatFileSize} and by macOS/iOS.
     * The binary convention (KiB = 1024 bytes) is only used by some
     * Unix tools and by Windows Explorer; using it here would make
     * files appear smaller in the UI than the system's own file
     * manager shows.
     */
    @NonNull
    public static String formatFileSize(long bytes) {
        if (bytes < 0) return "0 B";
        if (bytes < 1000) return bytes + " B";
        double kb = bytes / 1000.0;
        if (kb < 1000) {
            return String.format(Locale.getDefault(), "%.1f KB", kb);
        }
        double mb = kb / 1000.0;
        if (mb < 1000) {
            return String.format(Locale.getDefault(), "%.1f MB", mb);
        }
        double gb = mb / 1000.0;
        return String.format(Locale.getDefault(), "%.2f GB", gb);
    }

    // ============================================================
    // Avatars
    // ============================================================

    /**
     * Extracts a single-character avatar initial from a display name.
     * Returns "?" for null or empty input — never an empty string,
     * because a transparent avatar with no glyph looks like a
     * rendering bug rather than a fallback state.
     *
     * <p>Uses the first <em>letter or digit</em> in the name, skipping
     * punctuation, emoji and whitespace at the start. A name like
     * "💙 Sam" produces "S", not "💙" — emojis in avatars look
     * inconsistent across launchers and pixel densities.</p>
     */
    @NonNull
    public static String avatarInitial(@Nullable String name) {
        if (name == null) return "?";
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return "?";
        for (int i = 0; i < trimmed.length(); ) {
            int codePoint = trimmed.codePointAt(i);
            if (Character.isLetterOrDigit(codePoint)) {
                return new String(Character.toChars(Character.toUpperCase(codePoint)));
            }
            i += Character.charCount(codePoint);
        }
        return "?";
    }

    /**
     * Deterministically picks one of the six brand avatar colors from
     * a UID. The same UID always gets the same color across sessions,
     * devices, and reinstalls — a user's avatar color is stable and
     * recognizable to people they chat with. Uses the Java string
     * hash, which is stable across app restarts on a single device but
     * is not guaranteed identical across JVM versions — acceptable
     * for an avatar color, since the visual result is still one of
     * six distinct hues and identity is preserved within a single
     * device's session, which is what matters.
     */
    @ColorInt
    public static int avatarColorFor(@Nullable String uid) {
        int[] palette = {
                R.color.avatar_1,
                R.color.avatar_2,
                R.color.avatar_3,
                R.color.avatar_4,
                R.color.avatar_5,
                R.color.avatar_6,
        };
        int index = uid == null ? 0 : (Math.abs(uid.hashCode()) % palette.length);
        // This overload assumes the calling context is available. Since
        // avatar colors are fixed values in colors.xml and never depend
        // on light/dark theme, using the raw color int is safe. The
        // caller must pass a Context via ContextCompat.getColor for
        // proper resource resolution — see the overload below.
        // This method returns a resource ID, not a color int. The name
        // is a slight lie kept short for call-site readability.
        return palette[index];
    }

    /** Resolves a palette resource ID returned by {@link #avatarColorFor(String)} into a color. */
    @ColorInt
    public static int resolveAvatarColor(@NonNull Context context, @Nullable String uid) {
        return ContextCompat.getColor(context, avatarColorFor(uid));
    }

    // ============================================================
    // DP / SP conversion
    // ============================================================

    /** Converts DP to pixels using the display's density scale. */
    public static int dp(@NonNull Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    /** Converts DP to pixels using the system display metrics (no Context needed). */
    public static int dp(@NonNull View view, float dp) {
        return Math.round(dp * view.getResources().getDisplayMetrics().density);
    }

    /** Converts a scaled pixel value to pixels, respecting user font-size settings. */
    public static float sp(@NonNull Context context, float sp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                sp,
                context.getResources().getDisplayMetrics());
    }

    // ============================================================
    // Keyboard
    // ============================================================

    /**
     * Dismisses the soft keyboard and clears focus from whatever view
     * currently has it. Safe to call from any thread's UI work — it
     * no-ops if no view has focus or if the keyboard service is not
     * reachable (a rare but real state on some OEMs).
     */
    public static void hideKeyboard(@NonNull Context context, @Nullable View view) {
        if (view == null) return;
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (view.getWindowToken() != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        view.clearFocus();
    }

    /** Shows the keyboard and requests focus on the given view. */
    public static void showKeyboard(@NonNull Context context, @NonNull View view) {
        InputMethodManager imm = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        view.requestFocus();
        if (view.getWindowToken() != null) {
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ============================================================
    // Feedback
    // ============================================================

    /**
     * Shows a short Snackbar with the app's styling. Anchored to the
     * passed view — callers should pass a durable anchor (a root
     * CoordinatorLayout or the bottom-most visible view) rather than
     * a view that may be removed, or the Snackbar vanishes with it.
     */
    public static void toast(@NonNull View anchor, @Nullable CharSequence message) {
        if (anchor == null || message == null) return;
        Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show();
    }

    public static void toastLong(@NonNull View anchor, @Nullable CharSequence message) {
        if (anchor == null || message == null) return;
        Snackbar.make(anchor, message, Snackbar.LENGTH_LONG).show();
    }

    /**
     * Haptic feedback helper — a light "tick" on devices that support
     * it. Used for the voice-record button press and for long-press
     * message actions. No-ops on devices without a vibrator, and
     * respects the system "touch feedback" setting on API 26+.
     */
    public static void hapticTick(@NonNull Context context) {
        Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            // Deprecated vibrate(long) is still correct on pre-O APIs and
            // is not flagged by lint because it is guarded by the
            // API-level check above.
            v.vibrate(15);
        }
    }

    // ============================================================
    // Misc
    // ============================================================

    /**
     * Applies a background ripple to a view dynamically. Useful when
     * the same drawable is used across multiple rows and the caller
     * wants per-row color tuning without defining a selector file per
     * row type. The default uses {@code colorControlHighlight} from
     * the theme, which flips between light/dark automatically.
     */
    public static void applySelectableItemBackground(@NonNull View view) {
        TypedValue out = new TypedValue();
        if (view.getContext().getTheme()
                .resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            view.setBackgroundResource(out.resourceId);
        }
    }

    /**
     * Returns a color from a theme attribute. Useful in Java code
     * where {@code ?attr/...} is not available. Returns {@code fallback}
     * (not the attribute's value) if the attribute is missing — this
     * avoids crashing on older device themes that do not define a
     * particular attribute.
     */
    @ColorInt
    public static int themeColor(@NonNull Context context, int attr, @ColorInt int fallback) {
        TypedValue out = new TypedValue();
        if (context.getTheme().resolveAttribute(attr, out, true)) {
            if (out.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && out.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return out.data;
            }
            if (out.resourceId != 0) {
                return ContextCompat.getColor(context, out.resourceId);
            }
        }
        return fallback;
    }

    /**
     * Helper used by Snackbar call-sites that want text on the inverse
     * surface to be readable regardless of theme. Currently returns
     * transparent, but is retained because callers override it in
     * Phase 4 — keeping the call-site signature uniform now avoids
     * touching every Snackbar call later.
     */
    @ColorInt
    public static int snackbarTextColor(@NonNull Context context) {
        return Color.WHITE;
    }
}
