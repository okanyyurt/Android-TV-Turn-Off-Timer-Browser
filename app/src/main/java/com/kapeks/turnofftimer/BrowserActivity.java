package com.kapeks.turnofftimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class BrowserActivity extends AppCompatActivity {

    private WebView webView;
    private TextView tvBrowserCountdown;
    private LinearLayout fakeSleepLayer;

    private BroadcastReceiver timerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                if (intent.getAction().equals("TIMER_TICK")) {
                    long millis = intent.getLongExtra("millisUntilFinished", 0);
                    updateCountdownText(millis);
                    
                    int dimAlpha = intent.getIntExtra("dimAlpha", 0);
                    if (dimAlpha > 0) {
                        fakeSleepLayer.setVisibility(View.VISIBLE);
                        fakeSleepLayer.setBackgroundColor(android.graphics.Color.argb(dimAlpha, 0, 0, 0));
                    } else {
                        fakeSleepLayer.setVisibility(View.GONE);
                    }
                } else if (intent.getAction().equals("TIMER_FINISH")) {
                    executeFakeSleep();
                }
            }
        }
    };

    private boolean idleDimEnabled = false;
    private android.os.Handler idleHandler = new android.os.Handler();
    private Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            // Idle for 1 minute
            if (idleDimEnabled && fakeSleepLayer.getVisibility() != View.VISIBLE) {
                fakeSleepLayer.setVisibility(View.VISIBLE);
                fakeSleepLayer.setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0)); // ~80% black dim
            }
        }
    };

    private void resetIdleTimer() {
        if (idleDimEnabled) {
            // If it was idle dimmed, wake it up
            if (fakeSleepLayer.getVisibility() == View.VISIBLE && tvBrowserCountdown.getVisibility() == View.VISIBLE) {
                // Not in fake sleep (countdown is visible), so it's just idle dim.
                // But wait, the standard dim from TimerService might be active too!
                // To avoid conflict, we only hide if we set it. The easiest way is to let the next TIMER_TICK update it,
                // but we can just hide it and let TIMER_TICK re-apply standard dim if needed.
                fakeSleepLayer.setVisibility(View.GONE);
            }
            idleHandler.removeCallbacks(idleRunnable);
            idleHandler.postDelayed(idleRunnable, 60000); // 1 minute
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        resetIdleTimer();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        resetIdleTimer();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(android.view.MotionEvent ev) {
        resetIdleTimer();
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Tam ekran modu
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_browser);

        webView = findViewById(R.id.webView);
        tvBrowserCountdown = findViewById(R.id.tvBrowserCountdown);
        fakeSleepLayer = findViewById(R.id.fakeSleepLayer);

        idleDimEnabled = getIntent().getBooleanExtra("IDLE_DIM", false);
        resetIdleTimer();

        // WebView Ayarları (YouTube vb. videoların çalışması için)
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false); // Otomatik oynatma
        
        // Masaüstü veya mobil site isteği (TV için YouTube TV arayüzü idealdir)
        String url = getIntent().getStringExtra("url");
        if (url == null || url.isEmpty()) {
            url = "https://www.youtube.com";
        }
        
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(url);

        IntentFilter filter = new IntentFilter();
        filter.addAction("TIMER_TICK");
        filter.addAction("TIMER_FINISH");
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(timerReceiver, filter);
        }
    }

    private void updateCountdownText(long millis) {
        int seconds = (int) (millis / 1000) % 60 ;
        int minutes = (int) ((millis / (1000*60)) % 60);
        int hours   = (int) ((millis / (1000*60*60)) % 24);
        tvBrowserCountdown.setText(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        tvBrowserCountdown.setVisibility(View.VISIBLE);
    }

    private void executeFakeSleep() {
        idleHandler.removeCallbacks(idleRunnable);
        // 1. Ekranı tamamen siyah yap ve etkileşimi kapat
        fakeSleepLayer.setVisibility(View.VISIBLE);
        fakeSleepLayer.setBackgroundColor(android.graphics.Color.BLACK);
        tvBrowserCountdown.setVisibility(View.GONE);
        
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        
        // 2. Sesi tamamen kapat
        try {
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }
        } catch (Exception e) {}

        // 3. Videoyu durdur / Tarayıcıyı dondur
        webView.onPause();
        webView.loadUrl("about:blank"); // Oynatmayı kesin bitirir
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        idleHandler.removeCallbacks(idleRunnable);
        unregisterReceiver(timerReceiver);
        if (webView != null) {
            webView.destroy();
        }
    }
}
