package com.kapeks.turnofftimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private TextView tvCountdown;
    private Switch switchDim;
    private Switch switchIdleDim;
    private EditText etCustomMinutes;
    private Button btnCycleDim;
    
    private int currentDimMinutes = 0; // 0 means dim only at the end

    private BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long millisUntilFinished = intent.getLongExtra("millisUntilFinished", 0);
            updateCountdownText(millisUntilFinished);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvCountdown = findViewById(R.id.tvCountdown);
        switchDim = findViewById(R.id.switchDim);
        switchIdleDim = findViewById(R.id.switchIdleDim);
        etCustomMinutes = findViewById(R.id.etCustomMinutes);
        btnCycleDim = findViewById(R.id.btnCycleDim);

        findViewById(R.id.btn15m).setOnClickListener(v -> startTimer(15 * 60 * 1000));
        findViewById(R.id.btn30m).setOnClickListener(v -> startTimer(30 * 60 * 1000));
        findViewById(R.id.btn1h).setOnClickListener(v -> startTimer(60 * 60 * 1000));
        findViewById(R.id.btn3h).setOnClickListener(v -> startTimer(3 * 60 * 60 * 1000));

        // Özel Süre Butonları (+ / -)
        findViewById(R.id.btnMinus).setOnClickListener(v -> {
            try {
                int val = Integer.parseInt(etCustomMinutes.getText().toString());
                if (val > 1) {
                    etCustomMinutes.setText(String.valueOf(val - 1));
                }
            } catch (Exception e) {}
        });

        findViewById(R.id.btnPlus).setOnClickListener(v -> {
            try {
                int val = Integer.parseInt(etCustomMinutes.getText().toString());
                etCustomMinutes.setText(String.valueOf(val + 1));
            } catch (Exception e) {}
        });

        findViewById(R.id.btnStartCustom).setOnClickListener(v -> {
            try {
                int val = Integer.parseInt(etCustomMinutes.getText().toString());
                startTimer((long) val * 60 * 1000);
            } catch (Exception e) {}
        });

        // Kararma Süresi Butonu (Döngü)
        btnCycleDim.setOnClickListener(v -> {
            if (currentDimMinutes == 0) currentDimMinutes = 5;
            else if (currentDimMinutes == 5) currentDimMinutes = 10;
            else if (currentDimMinutes == 10) currentDimMinutes = 15;
            else if (currentDimMinutes == 15) currentDimMinutes = 30;
            else currentDimMinutes = 0;

            if (currentDimMinutes == 0) {
                btnCycleDim.setText("Kararma: Sadece Sonda");
            } else {
                btnCycleDim.setText("Kararma: Son " + currentDimMinutes + " Dk");
            }
        });

        findViewById(R.id.btnStop).setOnClickListener(v -> stopTimer());
        findViewById(R.id.btnPerms).setOnClickListener(v -> checkPermissions());

        findViewById(R.id.btnLaunchBrowser).setOnClickListener(v -> {
            Intent intent = new Intent(this, BrowserActivity.class);
            intent.putExtra("url", "https://www.youtube.com/tv"); // YouTube TV interface
            intent.putExtra("IDLE_DIM", switchIdleDim.isChecked());
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction("TIMER_TICK");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timerReceiver, filter);
        }

        // Sync state from background service
        android.content.SharedPreferences prefs = getSharedPreferences(TimerService.PREFS_NAME, MODE_PRIVATE);
        long endTime = prefs.getLong(TimerService.PREF_END_TIME, 0);
        if (endTime > 0) {
            long remaining = endTime - System.currentTimeMillis();
            if (remaining > 0) {
                updateCountdownText(remaining);
                boolean dim = prefs.getBoolean(TimerService.PREF_DIM_ENABLED, false);
                switchDim.setChecked(dim);
            } else {
                tvCountdown.setText("Sayaç: Bitti");
                prefs.edit().remove(TimerService.PREF_END_TIME).apply();
            }
        } else {
            if (!tvCountdown.getText().toString().equals("Sayaç: Bitti")) {
                tvCountdown.setText("Sayaç: 00:00:00");
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(timerReceiver);
        } catch (Exception e) {}
    }

    private void startTimer(long millis) {
        long dimMillis = 0;
        if (switchDim.isChecked()) {
            dimMillis = (long) currentDimMinutes * 60 * 1000;
        }

        if (switchDim.isChecked() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Lütfen 'Diğer uygulamaların üzerinde göster' iznini verin", Toast.LENGTH_LONG).show();
                checkPermissions();
                return;
            }
        }

        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(TimerService.ACTION_START);
        intent.putExtra(TimerService.EXTRA_MILLIS, millis);
        intent.putExtra(TimerService.EXTRA_DIM, switchDim.isChecked());
        intent.putExtra("DIM_START_MILLIS", dimMillis); // Pass the remaining time at which to start dimming
        
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "Zamanlayıcı başlatıldı", Toast.LENGTH_SHORT).show();
    }

    private void stopTimer() {
        Intent intent = new Intent(this, TimerService.class);
        intent.setAction(TimerService.ACTION_STOP);
        startService(intent);
        tvCountdown.setText("Sayaç: Durduruldu");
    }

    private void updateCountdownText(long millis) {
        int seconds = (int) (millis / 1000) % 60 ;
        int minutes = (int) ((millis / (1000*60)) % 60);
        int hours   = (int) ((millis / (1000*60*60)) % 24);
        tvCountdown.setText(String.format("Sayaç: %02d:%02d:%02d", hours, minutes, seconds));
    }

    private void checkPermissions() {
        // Sadece Overlay (Üzerinde Gösterme) izni isteyeceğiz.
        // Çin ROM'larında Device Admin ve Write Settings sayfaları silindiği için siyah ekran veriyor.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Gerekli ekran izni zaten verilmiş.", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
