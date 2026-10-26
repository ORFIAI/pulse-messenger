package com.pulse.messenger.auth;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialUnsupportedException;

import com.google.android.gms.tasks.Task;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.pulse.messenger.R;
import com.pulse.messenger.ui.MainActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AuthActivity — Google-only sign-in gate.
 *
 * <p><b>Entry behavior:</b></p>
 * <ul>
 *   <li>If a Firebase session is already persisted (the common case on
 *       every cold start after the first successful sign-in), the user
 *       is routed directly to {@link MainActivity} without ever seeing
 *       this screen. Firebase Auth's session persistence is automatic
 *       and survives process death, so no token storage is needed in
 *       this class.</li>
 *   <li>Otherwise, the welcome screen inflates and the user taps the
 *       single "Continue with Google" button to begin the only supported
 *       authentication flow.</li>
 * </ul>
 *
 * <p><b>Two-phase Google flow:</b></p>
 * <ol>
 *   <li><b>Silent phase:</b> ask Credential Manager for a Google ID
 *       token from <i>already-authorized</i> accounts only, with
 *       {@code autoSelectEnabled=true}. If exactly one account is
 *       present on the device and it has previously authorized this
 *       app, Credential Manager returns it with minimal UI — no
 *       interaction required.</li>
 *   <li><b>Explicit phase:</b> if the silent phase throws
 *       {@link NoCredentialException} (which means "no previously
 *       authorized account is available" — it does <b>not</b> mean
 *       the user has no Google accounts), retry with
 *       {@code filterByAuthorizedAccounts=false}. This shows the full
 *       Google account picker so the user can choose any account on
 *       the device. This is the standard pattern and the reason the
 *       button never appears to "do nothing" on a fresh install.</li>
 * </ol>
 *
 * <p><b>Security notes:</b></p>
 * <ul>
 *   <li>The ID token is exchanged with Firebase via
 *       {@link GoogleAuthProvider#getCredential(String, String)}. The
 *       Firebase UID — never the email, display name, or the Google
 *       account ID — becomes the app's internal identity.</li>
 *   <li>No tokens are logged. The only log statements are high-level
 *       outcome markers (e.g., "signInWithCredential:success") with no
 *       sensitive payload.</li>
 *   <li>This class does not write to Firestore yet. Profile bootstrap
 *       (creating {@code users/{uid}} on first sign-in) belongs to
 *       {@code FirestoreHelper.ensureUserProfile()} and is called from
 *       {@link MainActivity} in Phase 4. Keeping authentication and
 *       profile persistence in separate phases means a Firestore failure
 *       cannot lock a user out of a valid authenticated session.</li>
 * </ul>
 */
public class AuthActivity extends AppCompatActivity {

    private static final String TAG = "AuthActivity";

    private FirebaseAuth mAuth;
    private CredentialManager credentialManager;

    private MaterialButton btnGoogle;
    private CircularProgressIndicator progressAuth;
    private ImageView ivLogo;
    private TextView tvTerms;

    /**
     * Single-thread executor for the Credential Manager callback.
     * Credential Manager delivers its result on this executor, so
     * keeping it off the main thread prevents the callback's Firestore
     * or Firebase work from blocking the UI. Tasks that must touch
     * views are posted back to the main thread inside the callback.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        // Session restoration: Firebase Auth persists the signed-in user
        // across app restarts. If a user is present, skip the auth UI
        // entirely and go straight to the app.
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_auth);

        credentialManager = CredentialManager.create(this);

        btnGoogle = findViewById(R.id.btn_google);
        progressAuth = findViewById(R.id.progress_auth);
        ivLogo = findViewById(R.id.iv_logo);
        tvTerms = findViewById(R.id.tv_terms);

        setupTermsLinks();
        setupGoogleButton();
    }

    /**
     * Wires the "Continue with Google" button to the two-phase
     * Credential Manager flow. The button is disabled for the duration
     * of the flow to prevent a second tap from launching a duplicate
     * account picker — a common source of user confusion when network
     * is slow.
     */
    private void setupGoogleButton() {
        btnGoogle.setOnClickListener(v -> launchSilentSignIn());
    }

    /**
     * <b>Phase 1 of the two-phase flow:</b> request a Google ID token
     * from <i>already-authorized</i> accounts, with silent auto-select
     * enabled. This is the fast path for returning users.
     *
     * <p>If no authorized account exists, Credential Manager throws
     * {@link NoCredentialException}. That is not an error — it is the
     * signal to fall through to {@link #launchExplicitSignIn()}.</p>
     */
    private void launchSilentSignIn() {
        setLoading(true);

        GetGoogleIdOption silentOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(getString(R.string.default_web_client_id))
                .setAutoSelectEnabled(true)
                .build();

        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(silentOption)
                .build();

        credentialManager.getCredentialAsync(
                this,
                request,
                null,                       // no cancellation signal needed
                executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleCredentialResponse(result);
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (e instanceof NoCredentialException) {
                            // No previously-authorized Google account.
                            // This is expected on first install and after
                            // the user revokes authorization. Fall through
                            // to the full account picker.
                            runOnUiThread(() -> launchExplicitSignIn());
                        } else {
                            runOnUiThread(() -> handleCredentialError(e));
                        }
                    }
                });
    }

    /**
     * <b>Phase 2 of the two-phase flow:</b> request a Google ID token
     * without the {@code filterByAuthorizedAccounts} restriction. This
     * shows the full Google account picker so the user can choose any
     * account on the device.
     *
     * <p>This is only reached from {@link #launchSilentSignIn()}'s
     * {@link NoCredentialException} handler. It is deliberately not
     * reachable from the button's click handler — the silent attempt
     * must always run first so returning users are never shown an
     * unnecessary account picker.</p>
     */
    private void launchExplicitSignIn() {
    GetSignInWithGoogleOption explicitOption = new GetSignInWithGoogleOption.Builder(
            getString(R.string.default_web_client_id))
            .build();

    GetCredentialRequest request = new GetCredentialRequest.Builder()
            .addCredentialOption(explicitOption)
            .build();

    credentialManager.getCredentialAsync(
            this,
            request,
            null,
            executor,
            new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                @Override
                public void onResult(GetCredentialResponse result) {
                    handleCredentialResponse(result);
                }

                @Override
                public void onError(@NonNull GetCredentialException e) {
                    runOnUiThread(() -> handleCredentialError(e));
                }
            });
}

    /**
     * Validates the Credential Manager response shape, extracts the ID
     * token, and hands it to Firebase.
     *
     * <p>Credential Manager can return several credential types
     * (password, passkey, Google ID token). We only accept a
     * {@link GoogleIdTokenCredential} wrapped in a
     * {@link CustomCredential}. Any other type means the user's device
     * or account is configured in a way we do not support, and we show
     * a generic failure rather than silently doing nothing.</p>
     */
    private void handleCredentialResponse(@NonNull GetCredentialResponse response) {
        Credential credential = response.getCredential();

        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(credential.getType())) {
            Log.w(TAG, "Unexpected credential type: " + credential.getType());
            runOnUiThread(() -> {
                setLoading(false);
                showError(R.string.auth_error_credential);
            });
            return;
        }

        GoogleIdTokenCredential googleCredential;
        try {
            googleCredential = GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
        } catch (Exception parseError) {
            // Malformed payload from Credential Manager — treat as a
            // generic failure. Log only the exception type, never the
            // token or its parsed contents.
            Log.w(TAG, "Malformed Google ID token: " + parseError.getClass().getSimpleName());
            runOnUiThread(() -> {
                setLoading(false);
                showError(R.string.auth_error_credential);
            });
            return;
        }

        String idToken = googleCredential.getIdToken();
        if (idToken == null || idToken.isEmpty()) {
            Log.w(TAG, "Google ID token is empty");
            runOnUiThread(() -> {
                setLoading(false);
                showError(R.string.auth_error_credential);
            });
            return;
        }

        firebaseAuthWithGoogle(idToken);
    }

    /**
     * Exchanges the Google ID token for a Firebase session via
     * {@link GoogleAuthProvider#getCredential(String, String)}.
     *
     * <p>On success, the user is routed to {@link MainActivity}. On
     * failure, the loading spinner is dismissed and a user-friendly
     * error is shown. Firebase's failure codes are not surfaced to the
     * user — they are logged at WARN with the exception type only.</p>
     */
    private void firebaseAuthWithGoogle(String idToken) {
        AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);

        Task<?> signInTask = mAuth.signInWithCredential(credential);

        signInTask.addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                Log.d(TAG, "signInWithCredential:success");
                navigateToMain();
            } else {
                Log.w(TAG, "signInWithCredential:failure: "
                        + (task.getException() != null
                            ? task.getException().getClass().getSimpleName()
                            : "unknown"));
                setLoading(false);
                showError(R.string.auth_error_generic);
            }
        });
    }

    /**
     * Maps a {@link GetCredentialException} subclass to the correct
     * user-facing message. The key cases:
     *
     * <ul>
     *   <li>{@link GetCredentialCancellationException} — user dismissed
     *       the account picker. Not an error; show a brief "cancelled"
     *       message and return to the idle button state.</li>
     *   <li>{@link NoCredentialException} — no Google account exists on
     *       the device at all (distinct from the "no authorized account"
     *       case that triggers the fallback). Tell the user to add one
     *       in system Settings.</li>
     *   <li>{@link GetCredentialProviderConfigurationException} —
     *       {@code serverClientId} is wrong or Google Sign-In is not
     *       enabled in the Firebase project. This is a developer
     *       configuration error, not a user error, but the user still
     *       needs a non-cryptic message.</li>
     *   <li>{@link GetCredentialUnsupportedException} — Play Services is
     *       missing or too old. Direct the user to update Play
     *       Services.</li>
     *   <li>Any other {@link GetCredentialException} — generic network
     *       or unknown failure.</li>
     * </ul>
     */
    private void handleCredentialError(@NonNull GetCredentialException e) {
        setLoading(false);

        if (e instanceof GetCredentialCancellationException) {
            showError(R.string.auth_error_cancelled);
        } else if (e instanceof NoCredentialException) {
            // Reached only if phase 2 also threw NoCredentialException,
            // which means there is no Google account on the device.
            showError(R.string.auth_error_no_google_account);
        } else if (e instanceof GetCredentialProviderConfigurationException) {
            Log.e(TAG, "Credential provider misconfigured — check serverClientId and Firebase Google provider");
            showError(R.string.auth_error_credential);
        } else if (e instanceof GetCredentialUnsupportedException) {
            showError(R.string.auth_error_play_services);
        } else {
            Log.w(TAG, "Credential error: " + e.getClass().getSimpleName());
            showError(R.string.auth_error_generic);
        }
    }

    /**
     * Toggles between the button and the progress spinner. Exactly one
     * of the two is visible at any time — a tappable button during an
     * in-flight Credential Manager request would allow duplicate account
     * pickers and duplicate Firebase sign-in attempts.
     */
    private void setLoading(boolean loading) {
        btnGoogle.setEnabled(!loading);
        btnGoogle.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        progressAuth.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    /**
     * Shows a {@link Snackbar} over the auth screen. Anchored to the
     * button's position so it does not cover the Google sign-in button
     * itself — the user can immediately retry without dismissing the
     * message.
     */
    private void showError(int stringRes) {
        Snackbar.make(btnGoogle, getString(stringRes), Snackbar.LENGTH_LONG).show();
    }

    /**
     * Routes to {@link MainActivity} and finishes this activity so the
     * back button cannot return to the auth screen after a successful
     * sign-in. The {@code CLEAR_TASK} flag also ensures the auth
     * activity is removed from any preserved task stack if the user
     * later re-opens the app.
     */
    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    /**
     * Builds the "By continuing, you agree to our Terms of Service and
     * Privacy Policy" line with two independent clickable spans.
     *
     * <p>The links currently show a placeholder {@link Snackbar}. A real
     * Terms/Privacy URL will be added when the legal documents exist —
     * shipping the app with a dead link to a nonexistent page is worse
     * than a clear "coming soon" message, both for users and for
     * app-store review.</p>
     *
     * <p>The {@link ClickableSpan} subclass overrides
     * {@link ClickableSpan#updateDrawState(TextPaint)} to remove the
     * default underline and apply {@code colorPrimary} — underlined
     * links inside 12sp body text look noisy on a minimal welcome
     * screen.</p>
     */
    private void setupTermsLinks() {
        String prefix = getString(R.string.auth_terms_prefix);
        String terms = getString(R.string.auth_terms);
        String and = getString(R.string.auth_and);
        String privacy = getString(R.string.auth_privacy);

        String fullText = prefix + " " + terms + " " + and + " " + privacy + ".";
        SpannableString spannable = new SpannableString(fullText);

        int termsStart = fullText.indexOf(terms);
        int termsEnd = termsStart + terms.length();
        int privacyStart = fullText.indexOf(privacy);
        int privacyEnd = privacyStart + privacy.length();

        if (termsStart >= 0) {
            ClickableSpan termsSpan = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    Snackbar.make(tvTerms, R.string.settings_about_app, Snackbar.LENGTH_SHORT).show();
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(getColor(R.color.pulse_accent));
                    ds.setUnderlineText(false);
                }
            };
            spannable.setSpan(termsSpan, termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (privacyStart >= 0) {
            ClickableSpan privacySpan = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    Snackbar.make(tvTerms, R.string.settings_about_app, Snackbar.LENGTH_SHORT).show();
                }

                @Override
                public void updateDrawState(@NonNull TextPaint ds) {
                    super.updateDrawState(ds);
                    ds.setColor(getColor(R.color.pulse_accent));
                    ds.setUnderlineText(false);
                }
            };
            spannable.setSpan(privacySpan, privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        tvTerms.setText(spannable);
        tvTerms.setMovementMethod(LinkMovementMethod.getInstance());
        tvTerms.setHighlightColor(getColor(R.color.transparent));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The executor is a single non-daemon thread with no pending
        // tasks after the activity is torn down. Shutting it down
        // explicitly prevents a lingering thread from holding a
        // reference to this activity (and its views) after onDestroy.
        executor.shutdown();
    }
}
