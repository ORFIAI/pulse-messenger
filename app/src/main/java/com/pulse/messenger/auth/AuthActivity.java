package com.pulse.messenger.auth;

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
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
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

public class AuthActivity extends AppCompatActivity {

    private static final String TAG = "AuthActivity";

    private FirebaseAuth mAuth;
    private CredentialManager credentialManager;

    private MaterialButton btnGoogle;
    private CircularProgressIndicator progressAuth;
    private ImageView ivLogo;
    private TextView tvTerms;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

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

    private void setupGoogleButton() {
        btnGoogle.setOnClickListener(v -> launchSilentSignIn());
    }

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
                null,
                executor,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse result) {
                        handleCredentialResponse(result);
                    }

                    @Override
                    public void onError(@NonNull GetCredentialException e) {
                        if (e instanceof NoCredentialException) {
                            runOnUiThread(() -> launchExplicitSignIn());
                        } else {
                            runOnUiThread(() -> handleCredentialError(e));
                        }
                    }
                });
    }

    /**
     * Uses GetSignInWithGoogleOption instead of GetGoogleIdOption.
     * This forces the classic Google Account Picker UI to appear,
     * which bypasses the silent-fetch bugs on some devices.
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
            // Fix: Added .getData() to correctly extract the Bundle
            googleCredential = GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
        } catch (Exception parseError) {
            Log.w(TAG, "Malformed Google ID token: " + parseError.getClass().getSimpleName());
            runOnUiThread(() -> {
                setLoading(false);
                showError(R.string.auth_error_credential);
            });
            return;
        }

        String idToken = googleCredential.getIdToken();
        if (idToken == null || idToken.isEmpty()) {
            runOnUiThread(() -> {
                setLoading(false);
                showError(R.string.auth_error_credential);
            });
            return;
        }

        firebaseAuthWithGoogle(idToken);
    }

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
                            ? task.getException().getClass().getSimpleName() : "unknown"));
                setLoading(false);
                showError(R.string.auth_error_generic);
            }
        });
    }

    private void handleCredentialError(@NonNull GetCredentialException e) {
        setLoading(false);

        if (e instanceof GetCredentialCancellationException) {
            showError(R.string.auth_error_cancelled);
        } else if (e instanceof NoCredentialException) {
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

    private void setLoading(boolean loading) {
        btnGoogle.setEnabled(!loading);
        btnGoogle.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        progressAuth.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showError(int stringRes) {
        Snackbar.make(btnGoogle, getString(stringRes), Snackbar.LENGTH_LONG).show();
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

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
        executor.shutdown();
    }
}
