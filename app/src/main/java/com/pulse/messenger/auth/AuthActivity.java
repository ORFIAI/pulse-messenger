package com.pulse.messenger.auth;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.pulse.messenger.R;
import com.pulse.messenger.ui.MainActivity;

public class AuthActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private boolean isLoginMode = true; // Toggle between Login and Signup

    private TextInputEditText etEmail, etPassword;
    private MaterialButton btnAuthAction;
    private CircularProgressIndicator progressAuth;
    private TextView tvToggleMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAuth = FirebaseAuth.getInstance();

        // If already logged in, skip straight to MainActivity
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            navigateToMain();
            return;
        }

        setContentView(R.layout.activity_auth);

        etEmail = findViewById(R.id.et_email);
        etPassword = findViewById(R.id.et_password);
        btnAuthAction = findViewById(R.id.btn_auth_action);
        progressAuth = findViewById(R.id.progress_auth);
        tvToggleMode = findViewById(R.id.tv_toggle_mode);

        btnAuthAction.setOnClickListener(v -> attemptAuth());
        tvToggleMode.setOnClickListener(v -> toggleMode());
    }

    private void toggleMode() {
        isLoginMode = !isLoginMode;
        if (isLoginMode) {
            btnAuthAction.setText(R.string.auth_login);
            tvToggleMode.setText(R.string.auth_toggle_to_signup);
        } else {
            btnAuthAction.setText(R.string.auth_signup);
            tvToggleMode.setText(R.string.auth_toggle_to_login);
        }
    }

    private void attemptAuth() {
        String email = etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
        String password = etPassword.getText() != null ? etPassword.getText().toString().trim() : "";

        if (TextUtils.isEmpty(email) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Snackbar.make(btnAuthAction, "Please enter a valid email address", Snackbar.LENGTH_SHORT).show();
            return;
        }
        if (password.length() < 6) {
            Snackbar.make(btnAuthAction, "Password must be at least 6 characters", Snackbar.LENGTH_SHORT).show();
            return;
        }

        setLoading(true);

        if (isLoginMode) {
            // Log In
            mAuth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        setLoading(false);
                        if (task.isSuccessful()) {
                            navigateToMain();
                        } else {
                            String error = task.getException() != null ? task.getException().getMessage() : "Login failed";
                            Snackbar.make(btnAuthAction, error, Snackbar.LENGTH_LONG).show();
                        }
                    });
        } else {
            // Sign Up
            mAuth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        setLoading(false);
                        if (task.isSuccessful()) {
                            navigateToMain();
                        } else {
                            String error = task.getException() != null ? task.getException().getMessage() : "Sign up failed";
                            Snackbar.make(btnAuthAction, error, Snackbar.LENGTH_LONG).show();
                        }
                    });
        }
    }

    private void setLoading(boolean loading) {
        btnAuthAction.setEnabled(!loading);
        btnAuthAction.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        progressAuth.setVisibility(loading ? View.VISIBLE : View.GONE);
        tvToggleMode.setEnabled(!loading);
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
