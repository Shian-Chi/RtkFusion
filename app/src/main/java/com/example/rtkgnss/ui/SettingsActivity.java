package com.example.rtkgnss.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rtkgnss.R;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "ntrip_prefs";
    public static final String KEY_HOST = "host";
    public static final String KEY_PORT = "port";
    public static final String KEY_MOUNT = "mountpoint";
    public static final String KEY_USER = "username";
    public static final String KEY_PASS = "password";

    private EditText etHost, etPort, etMount, etUser, etPass;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("NTRIP Settings");
        }

        etHost  = findViewById(R.id.et_host);
        etPort  = findViewById(R.id.et_port);
        etMount = findViewById(R.id.et_mountpoint);
        etUser  = findViewById(R.id.et_username);
        etPass  = findViewById(R.id.et_password);

        loadPrefs();

        Button btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> savePrefs());
    }

    private void loadPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        etHost.setText(prefs.getString(KEY_HOST, ""));
        etPort.setText(String.valueOf(prefs.getInt(KEY_PORT, 2101)));
        etMount.setText(prefs.getString(KEY_MOUNT, ""));
        etUser.setText(prefs.getString(KEY_USER, ""));
        etPass.setText(prefs.getString(KEY_PASS, ""));
    }

    private void savePrefs() {
        String host = etHost.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();
        String mount = etMount.getText().toString().trim();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(mount)) {
            Toast.makeText(this, "Host and Mountpoint are required", Toast.LENGTH_SHORT).show();
            return;
        }

        int port = 2101;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException ignored) {}

        SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        ed.putString(KEY_HOST, host);
        ed.putInt(KEY_PORT, port);
        ed.putString(KEY_MOUNT, mount);
        ed.putString(KEY_USER, etUser.getText().toString());
        ed.putString(KEY_PASS, etPass.getText().toString());
        ed.apply();

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
