package com.sipvideochat.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;
import com.sipvideochat.ui.main.MainActivity;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerHost, etServerPort, etUsername, etPassword, etLocalSipPort;
    private MaterialButton btnLogin;
    private ProgressBar progressBar;
    private TextView tvStatus;

    private SipService sipService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            SipService.SipBinder binder = (SipService.SipBinder) service;
            sipService = binder.getService();
            serviceBound = true;
            doLogin();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sipService = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etServerHost = findViewById(R.id.etServerHost);
        etServerPort = findViewById(R.id.etServerPort);
        etUsername = findViewById(R.id.etUsername);
        etPassword = findViewById(R.id.etPassword);
        etLocalSipPort = findViewById(R.id.etLocalSipPort);
        btnLogin = findViewById(R.id.btnLogin);
        progressBar = findViewById(R.id.progressBar);
        tvStatus = findViewById(R.id.tvStatus);

        ClientConfig config = new ClientConfig();
        config.load(this);
        if (!config.getUsername().isEmpty()) {
            etUsername.setText(config.getUsername());
        }
        if (!config.getPassword().isEmpty()) {
            etPassword.setText(config.getPassword());
        }
        if (!config.getSipServerHost().isEmpty()) {
            etServerHost.setText(config.getSipServerHost());
        }
        etServerPort.setText(String.valueOf(config.getSipServerPort()));
        etLocalSipPort.setText(String.valueOf(config.getLocalSipPort()));

        btnLogin.setOnClickListener(v -> onLoginClicked());
    }

    private void onLoginClicked() {
        String serverHost = getText(etServerHost);
        String serverPortStr = getText(etServerPort);
        String username = getText(etUsername);
        String password = getText(etPassword);
        String localSipPortStr = getText(etLocalSipPort);

        if (serverHost.isEmpty() || username.isEmpty()) {
            Snackbar.make(btnLogin, "Please enter the server host and username.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        int serverPort;
        int localSipPort;
        try {
            serverPort = Integer.parseInt(serverPortStr);
            localSipPort = Integer.parseInt(localSipPortStr);
        } catch (NumberFormatException e) {
            Snackbar.make(btnLogin, "Port must be a number.", Snackbar.LENGTH_SHORT).show();
            return;
        }

        ClientConfig config = new ClientConfig();
        config.setSipServerHost(serverHost);
        config.setSipServerPort(serverPort);
        config.setAdminServerHost(serverHost);
        config.setUsername(username);
        config.setPassword(password);
        config.setLocalSipPort(localSipPort);
        config.setLocalIp(ClientConfig.detectLocalIp(this));
        config.save(this);

        btnLogin.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("Connecting to SIP server...");

        Intent serviceIntent = new Intent(this, SipService.class);
        startForegroundService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void doLogin() {
        ClientConfig config = new ClientConfig();
        config.load(this);

        sipService.initSip(config, new SipEventListener() {
            @Override
            public void onRegistered() {
                tvStatus.setText("Registered.");
                config.save(LoginActivity.this);

                Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                startActivity(intent);
                finish();
            }

            @Override
            public void onRegisterFailed(String reason) {
                tvStatus.setText("Registration failed: " + reason);
                btnLogin.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                Snackbar.make(btnLogin, "Registration failed: " + reason, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    @Override
    protected void onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
