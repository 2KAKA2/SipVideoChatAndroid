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
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.sipvideochat.R;
import com.sipvideochat.config.ClientConfig;
import com.sipvideochat.sip.SipEventListener;
import com.sipvideochat.sip.SipService;
import com.sipvideochat.ui.main.MainActivity;

public class LoginActivity extends AppCompatActivity {

    private TextInputEditText etServerHost, etServerPort, etUsername, etPassword, etLocalSipPort, etRealm;
    private TextInputLayout tilRealm;
    private SwitchMaterial swImsMode;
    private MaterialButton btnLogin;
    private ProgressBar progressBar;
    private TextView tvStatus;

    private SipService sipService;
    private boolean serviceBound = false;
    private boolean launchedMain = false;

    private final SipEventListener loginSipListener = new SipEventListener() {
        @Override
        public void onRegistered() {
            if (launchedMain || isFinishing() || isDestroyed()) {
                return;
            }
            launchedMain = true;
            tvStatus.setText("Registered.");

            if (sipService != null) {
                sipService.removeEventListener(this);
            }

            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        }

        @Override
        public void onRegisterFailed(String reason) {
            if (launchedMain || isFinishing() || isDestroyed()) {
                return;
            }
            tvStatus.setText("Registration failed: " + reason);
            btnLogin.setEnabled(true);
            progressBar.setVisibility(View.GONE);
            Snackbar.make(btnLogin, "Registration failed: " + reason, Snackbar.LENGTH_LONG).show();
        }
    };

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
        etRealm = findViewById(R.id.etRealm);
        tilRealm = findViewById(R.id.tilRealm);
        swImsMode = findViewById(R.id.swImsMode);
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
        if (!config.getRealm().isEmpty()) {
            etRealm.setText(config.getRealm());
        }
        swImsMode.setChecked(config.isImsMode());
        tilRealm.setVisibility(config.isImsMode() ? View.VISIBLE : View.GONE);

        swImsMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            tilRealm.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });

        btnLogin.setOnClickListener(v -> onLoginClicked());

        // 自动登录：有效配置时延迟触发
        if (config.isSipConfigured()) {
            new android.os.Handler().postDelayed(this::onLoginClicked, 600);
        }
    }

    private void onLoginClicked() {
        android.util.Log.e("AUTO_LOGIN", "onLoginClicked CALLED!");
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

        boolean imsMode = swImsMode.isChecked();
        String realm = getText(etRealm);

        ClientConfig config = new ClientConfig();
        config.setSipServerHost(serverHost);
        config.setSipServerPort(serverPort);
        config.setAdminServerHost(serverHost);
        config.setUsername(username);
        config.setPassword(password);
        config.setLocalSipPort(localSipPort);
        config.setLocalIp(ClientConfig.detectLocalIp(this));
        config.setImsMode(imsMode);
        if (imsMode) {
            config.setRealm(realm.isEmpty() ? "ims.mnc001.mcc001.3gppnetwork.org" : realm);
            config.setImpu("sip:" + username + "@" + config.getRealm());
            config.setImpi(username + "@" + config.getRealm());
        }
        config.save(this);

        btnLogin.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText("Connecting to SIP server...");

        // 直接启动 SIP 注册（跳过 SipService）
        doLogin();
    }

    // 全局共享 SipClient，MainActivity 也能用
    public static volatile com.sipvideochat.sip.SipClient sSipClient;

    private void doLogin() {
        android.util.Log.e("AUTO_LOGIN", "doLogin CALLED!");
        ClientConfig config = new ClientConfig();
        config.load(this);
        android.util.Log.e("AUTO_LOGIN", "config loaded: user=" + config.getUsername() + ", host=" + config.getSipServerHost() + ", ims=" + config.isImsMode());
        config.save(this);

        // 绕过 SipService，直接启动 SipClient
        new Thread(() -> {
            try {
                android.util.Log.e("AUTO_LOGIN", "Starting direct SipClient...");
                sSipClient = new com.sipvideochat.sip.SipClient(config);
                sSipClient.addListener(new com.sipvideochat.sip.SipEventListener() {
                    @Override public void onRegistered() {
                        android.util.Log.e("AUTO_LOGIN", "REGISTERED OK!");
                        runOnUiThread(() -> {
                            if (launchedMain || isFinishing() || isDestroyed()) return;
                            launchedMain = true;
                            tvStatus.setText("已注册！");
                            progressBar.setVisibility(View.GONE);
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                startActivity(intent);
                                finish();
                            }, 500);
                        });
                    }
                    @Override public void onRegisterFailed(String reason) {
                        android.util.Log.e("AUTO_LOGIN", "REGISTER FAILED: " + reason);
                        runOnUiThread(() -> {
                            tvStatus.setText("Registration failed: " + reason);
                            btnLogin.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                });
                sSipClient.init();
                android.util.Log.e("AUTO_LOGIN", "SipClient.init() done");
            } catch (Exception e) {
                android.util.Log.e("AUTO_LOGIN", "SipClient ERROR: " + e.getMessage(), e);
            }
        }, "direct-sip").start();
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() != null ? editText.getText().toString().trim() : "";
    }

    @Override
    protected void onDestroy() {
        if (sipService != null) {
            sipService.removeEventListener(loginSipListener);
        }
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }
}
