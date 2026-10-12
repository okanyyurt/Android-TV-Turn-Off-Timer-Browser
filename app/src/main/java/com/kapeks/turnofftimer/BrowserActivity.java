package com.kapeks.turnofftimer;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Tam ekran tarayıcı (varsayılan: YouTube TV).
 * Ayarlar ekranın altında/üstünde ince bir barda durur; Menü tuşu, Geri'ye uzun basma
 * veya sayfa geçmişinin başında Geri tuşu barı açar. Geri tuşu artık uygulamayı kapatmaz.
 */
public class BrowserActivity extends AppCompatActivity implements TimerService.Listener {
    public static final String EXTRA_URL = "url";

    private static final long BAR_AUTO_HIDE_MS = 8000;
    private static final long IDLE_DIM_MS = 60_000;
    private static final long LONG_PRESS_MS = 600;
    private static final long DOUBLE_BACK_MS = 700;
    private static final int IDLE_DIM_ALPHA = 200; // ~%80 siyah

    private FrameLayout root;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View dimLayer;
    private TextView tvIndicator;
    private View sleepLayer;
    private TextView tvSleepHint;
    private ControlBarView bar;
    private WarningView warningView;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean idleDimmed;
    private boolean slept;          // Süre doldu: sayfa durduruldu, kullanıcı bir tuşa basana kadar siyah
    private boolean exitHintShown;
    private long backDownTime;
    private boolean backLongHandled;
    private long lastBackTime;

    private final Runnable hideBarRunnable = this::hideBar;
    private final Runnable idleRunnable = () -> {
        if (Prefs.idleDim(this) && !slept) {
            idleDimmed = true;
            applyDim();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_browser);
        Ui.hideSystemBars(this);

        root = findViewById(R.id.rootLayout);
        webView = findViewById(R.id.webView);
        fullscreenContainer = findViewById(R.id.fullscreenContainer);
        dimLayer = findViewById(R.id.dimLayer);
        tvIndicator = findViewById(R.id.tvBrowserCountdown);
        sleepLayer = findViewById(R.id.fakeSleepLayer);
        tvSleepHint = findViewById(R.id.tvSleepHint);
        sleepLayer.setOnClickListener(v -> wakeFromSleep());

        setupWebView();
        setupBar();

        root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            // Odak bardan çıkınca (ör. yukarı ok ile sayfaya dönünce) barı gizle
            if (bar.getVisibility() == View.VISIBLE && newFocus != null && !isInside(newFocus, bar)) hideBar();
        });

        String url = getIntent().getStringExtra(EXTRA_URL);
        webView.loadUrl(TextUtils.isEmpty(url) ? Prefs.homeUrl(this) : url);
        Toast.makeText(this, "Zamanlayıcı menüsü: Geri'ye iki kez veya uzun basın", Toast.LENGTH_LONG).show();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // Otomatik oynatma
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            // Sayfadaki "tam ekran" düğmesi (HTML5 video) için
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                hideBar();
                Ui.hideSystemBars(BrowserActivity.this);
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }
        });
    }

    private void setupBar() {
        bar = new ControlBarView(this);
        bar.addExtra("Geri", () -> {
            if (webView.canGoBack()) webView.goBack();
        });
        bar.addExtra("Ana sayfa", () -> {
            webView.loadUrl(Prefs.homeUrl(this));
            hideBar();
        });
        bar.addExtra("Yenile", () -> webView.reload());
        bar.addExtra("Adres", () -> Ui.askUrl(this, currentUrl(), url -> {
            webView.loadUrl(url);
            hideBar();
        }));
        bar.addExtra("Ana menü", () -> startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        bar.addExtra("Çık", this::finish);
        bar.setOnInteraction(this::scheduleBarHide);
        bar.setOnPositionChanged(this::applyPositions);
        bar.setVisibility(View.GONE);
        root.addView(bar, root.indexOfChild(sleepLayer), barParams());
    }

    // ------------------------------------------------------------------ Yaşam döngüsü

    @Override
    protected void onResume() {
        super.onResume();
        TimerService.setInAppUi(true);
        TimerService.addListener(this);
        if (!slept) webView.onResume();
        Ui.hideSystemBars(this);
        bar.refresh();
        onTimerUpdate();
        onUserActivity();
    }

    @Override
    protected void onPause() {
        TimerService.setInAppUi(false);
        TimerService.removeListener(this);
        handler.removeCallbacks(idleRunnable);
        // Arka plana geçince video çalmaya devam etmesin
        webView.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideSystemBars(this);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            ViewParent parent = webView.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(webView);
            webView.destroy();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Tuşlar

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        onUserActivity();
        int code = event.getKeyCode();
        boolean up = event.getAction() == KeyEvent.ACTION_UP;

        if (slept) {
            if (up) wakeFromSleep();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            handleBackKey(event);
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_INFO) {
            if (up) toggleBar();
            return true;
        }
        if (bar.getVisibility() == View.VISIBLE) scheduleBarHide();
        return super.dispatchKeyEvent(event);
    }

    /** Kısa basış: geri git / barı aç-kapat. Uzun basış: barı aç. */
    private void handleBackKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                backDownTime = event.getEventTime();
                backLongHandled = false;
            } else if (!backLongHandled && event.getEventTime() - backDownTime >= LONG_PRESS_MS) {
                backLongHandled = true;
                showBar();
            }
            return;
        }
        if (event.getAction() == KeyEvent.ACTION_UP && !backLongHandled && !event.isCanceled()) {
            onBackShort();
        }
    }

    private void onBackShort() {
        if (warningView != null) {
            TimerService.dismissWarning();
        } else if (customView != null) {
            exitCustomView();
        } else if (bar.getVisibility() == View.VISIBLE) {
            hideBar();
        } else if (now() - lastBackTime < DOUBLE_BACK_MS || !webView.canGoBack()) {
            // İki kez hızlı Geri ya da geçmişin başı: menüyü aç
            showBarWithHint();
        } else {
            lastBackTime = now();
            String before = webView.getUrl();
            webView.goBack();
            // YouTube TV gibi sayfalar geri gidince kendini tekrar yükleyebilir; adres değişmediyse menüyü aç
            handler.postDelayed(() -> {
                if (!slept && bar.getVisibility() != View.VISIBLE && TextUtils.equals(before, webView.getUrl())) {
                    showBarWithHint();
                }
            }, 1200);
        }
    }

    private void showBarWithHint() {
        showBar();
        if (!exitHintShown) {
            exitHintShown = true;
            Ui.toast(this, "Çıkmak için bardaki “Çık” düğmesini kullanın");
        }
    }

    private static long now() {
        return android.os.SystemClock.elapsedRealtime();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        onUserActivity();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        onUserActivity();
        return super.dispatchGenericMotionEvent(ev);
    }

    // ------------------------------------------------------------------ Bar

    private void showBar() {
        if (slept) return;
        bar.refresh();
        bar.setVisibility(View.VISIBLE);
        bar.focusDefault();
        updateIndicator();
        scheduleBarHide();
    }

    private void hideBar() {
        handler.removeCallbacks(hideBarRunnable);
        if (bar.getVisibility() != View.VISIBLE) return;
        bar.setVisibility(View.GONE);
        (customView != null ? customView : webView).requestFocus();
        updateIndicator();
    }

    private void toggleBar() {
        if (bar.getVisibility() == View.VISIBLE) hideBar();
        else showBar();
    }

    private void scheduleBarHide() {
        handler.removeCallbacks(hideBarRunnable);
        handler.postDelayed(hideBarRunnable, BAR_AUTO_HIDE_MS);
    }

    private FrameLayout.LayoutParams barParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Ui.dp(this, 16);
        lp.setMargins(m * 2, m, m * 2, m);
        lp.gravity = (Prefs.barTop(this) ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private FrameLayout.LayoutParams warningParams() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Ui.dp(this, 40);
        lp.setMargins(m, m, m, m);
        lp.gravity = (Prefs.barTop(this) ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    private void applyPositions() {
        bar.setLayoutParams(barParams());
        if (warningView != null) warningView.setLayoutParams(warningParams());
    }

    // ------------------------------------------------------------------ Zamanlayıcı

    @Override
    public void onTimerUpdate() {
        TimerService.State state = TimerService.getState();
        if ((state == TimerService.State.FINISHING || state == TimerService.State.SLEEPING) && !slept) {
            enterSleep();
        }
        applyDim();
        updateIndicator();
        updateWarning();
    }

    private void applyDim() {
        int alpha = slept ? 0 : TimerService.getDimAlpha(this);
        if (idleDimmed && !slept) alpha = Math.max(alpha, IDLE_DIM_ALPHA);
        if (alpha > 0) {
            dimLayer.setBackgroundColor(Color.argb(Math.min(alpha, 255), 0, 0, 0));
            dimLayer.setVisibility(View.VISIBLE);
        } else {
            dimLayer.setVisibility(View.GONE);
        }
    }

    private void updateIndicator() {
        boolean visible = !slept && bar.getVisibility() != View.VISIBLE && TimerService.isIndicatorVisible(this);
        if (visible) tvIndicator.setText(Ui.format(TimerService.getRemaining()));
        tvIndicator.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updateWarning() {
        boolean active = !slept && TimerService.isWarningActive(this);
        if (active && warningView == null) {
            warningView = new WarningView(this, new WarningView.Listener() {
                @Override public void onExtend(long millis) { TimerService.extend(BrowserActivity.this, millis); }
                @Override public void onFinishNow() { TimerService.finishNow(BrowserActivity.this); }
                @Override public void onCancel() { TimerService.stop(BrowserActivity.this); }
                @Override public void onDismiss() { TimerService.dismissWarning(); }
            });
            hideBar();
            root.addView(warningView, root.indexOfChild(sleepLayer), warningParams());
            warningView.post(warningView::focusDefault);
        } else if (!active && warningView != null) {
            root.removeView(warningView);
            warningView = null;
            webView.requestFocus();
        }
        if (warningView != null) warningView.setRemaining(TimerService.getRemaining());
    }

    // ------------------------------------------------------------------ Süre doldu / uyandırma

    private void enterSleep() {
        slept = true;
        hideBar();
        exitCustomView();
        if (warningView != null) {
            root.removeView(warningView);
            warningView = null;
        }
        webView.loadUrl("about:blank"); // Oynatmayı kesin bitirir
        webView.onPause();

        sleepLayer.setVisibility(View.VISIBLE);
        tvSleepHint.setVisibility(View.VISIBLE);
        handler.postDelayed(() -> tvSleepHint.setVisibility(View.GONE), 4000);

        // Ekranın kendiliğinden kapanmasına izin ver ve parlaklığı en aza indir
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);
    }

    private void wakeFromSleep() {
        TimerService.State state = TimerService.getState();
        if (state == TimerService.State.FINISHING) return; // kapatma denemeleri sürüyor
        if (state == TimerService.State.SLEEPING) TimerService.wake(this);

        slept = false;
        sleepLayer.setVisibility(View.GONE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);

        // Gece kalan videonun kendiliğinden devam etmemesi için ana sayfadan başla
        webView.onResume();
        webView.loadUrl(Prefs.homeUrl(this));
        webView.requestFocus();
        onTimerUpdate();
    }

    private void exitCustomView() {
        if (customView == null) return;
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        webView.requestFocus();
    }

    // ------------------------------------------------------------------ Yardımcılar

    private void onUserActivity() {
        if (idleDimmed) {
            idleDimmed = false;
            applyDim();
        }
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, IDLE_DIM_MS);
    }

    private String currentUrl() {
        String url = webView.getUrl();
        return TextUtils.isEmpty(url) || "about:blank".equals(url) ? Prefs.homeUrl(this) : url;
    }

    private static boolean isInside(View v, View container) {
        for (View cur = v; cur != null; ) {
            if (cur == container) return true;
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return false;
    }
}
