package com.kapeks.turnofftimer;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

/**
 * İsteğe bağlı erişilebilirlik servisi. Süre dolunca:
 * - Uyku modunda ekranı kapatır (Android 9+ "ekranı kilitle" eylemi),
 * - "Tamamen kapat" modunda güç menüsünü açıp "Kapat" düğmesine basar.
 * Yalnızca zamanlayıcı bu eylemleri istediğinde bir şey yapar.
 */
public class PowerAccessibilityService extends AccessibilityService {
    private static final String TAG = "PowerAccessService";
    private static final long TRIGGER_TIMEOUT_MS = 10_000;
    private static final String[] POWER_OFF_WORDS = {
            "kapat", "power off", "poweroff", "shut down", "shutdown", "turn off", "关机", "關機"
    };
    private static final String[] EXCLUDED_WORDS = {"yeniden", "restart", "reboot", "重启", "重啟"};

    private static volatile PowerAccessibilityService instance;
    private volatile long shutdownTriggeredAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Accessibility Service Connected");

        // Force foreground to prevent Chinese OS from killing the accessibility service
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        "AccessChannel", "Erişilebilirlik", NotificationManager.IMPORTANCE_MIN);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) manager.createNotificationChannel(channel);
            }

            Intent notificationIntent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, "AccessChannel")
                    .setContentTitle("TV Kapanma Sistemi Aktif")
                    .setContentText("Zamanlayıcı bitince ekranı kapatmaya hazır")
                    .setSmallIcon(android.R.drawable.ic_lock_power_off)
                    .setContentIntent(pendingIntent)
                    .build();

            startForeground(2, notification);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground for accessibility", e);
        }
    }

    public static PowerAccessibilityService getInstance() {
        return instance;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long triggeredAt = shutdownTriggeredAt;
        if (triggeredAt == 0) return;
        if (SystemClock.elapsedRealtime() - triggeredAt > TRIGGER_TIMEOUT_MS) {
            shutdownTriggeredAt = 0; // güç menüsü açılmadı, beklemeyi bırak
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && findAndClickPowerOff(root)) {
                shutdownTriggeredAt = 0;
            }
        }
    }

    private boolean findAndClickPowerOff(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (matchesPowerOff(node.getText()) || matchesPowerOff(node.getContentDescription())) {
            Log.d(TAG, "Found power off button: " + node.getText());
            if (click(node)) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClickPowerOff(node.getChild(i))) return true;
        }
        return false;
    }

    private static boolean matchesPowerOff(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase(Locale.ROOT).trim();
        for (String ex : EXCLUDED_WORDS) {
            if (s.contains(ex)) return false;
        }
        if (s.equals("off")) return true;
        for (String w : POWER_OFF_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /** TV'de kumanda odaklı menülerde ACTION_CLICK daha güvenilir; olmazsa ekrana dokunma hareketi dener. */
    private boolean click(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path path = new Path();
            path.moveTo(bounds.centerX(), bounds.centerY());
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                    .build();
            boolean dispatched = dispatchGesture(gesture, null, null);
            Log.d(TAG, "Gesture tapped at " + bounds.centerX() + ", " + bounds.centerY() + " - Dispatched: " + dispatched);
            return dispatched;
        }
        return false;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hideOverlayBar();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ Kısayol tuşu ile her uygulamada bar

    private static final long SHORTCUT_LONG_PRESS_MS = 700;
    private static final long SHORTCUT_DOUBLE_PRESS_MS = 450;
    private static final long OVERLAY_BAR_HIDE_MS = 10_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideBarRunnable = this::hideOverlayBar;
    private long shortcutDownTime;
    private long shortcutLastUpTime;
    private boolean shortcutConsumed;
    private View overlayBar;

    /**
     * Yalnızca ayarlarda seçilen tek tuşa bakar: uzun basılırsa veya iki kez hızlıca basılırsa barı açar.
     * Diğer tüm tuşlar dokunulmadan uygulamaya geçer; hiçbir tuş kaydedilmez.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != Prefs.shortcutKey(this) || TimerService.isInAppUi()) return false;

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                shortcutDownTime = event.getEventTime();
                shortcutConsumed = event.getEventTime() - shortcutLastUpTime < SHORTCUT_DOUBLE_PRESS_MS;
                if (shortcutConsumed) mainHandler.post(this::toggleOverlayBar);
                return shortcutConsumed;
            }
            if (!shortcutConsumed && event.getEventTime() - shortcutDownTime >= SHORTCUT_LONG_PRESS_MS) {
                shortcutConsumed = true;
                mainHandler.post(this::toggleOverlayBar);
            }
            return shortcutConsumed;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            shortcutLastUpTime = shortcutConsumed ? 0 : event.getEventTime();
            boolean consumed = shortcutConsumed;
            shortcutConsumed = false;
            return consumed;
        }
        return false;
    }

    private void toggleOverlayBar() {
        if (overlayBar != null) hideOverlayBar();
        else showOverlayBar();
    }

    private void showOverlayBar() {
        Context ctx = new ContextThemeWrapper(this, R.style.Theme_TurnOffTimer);
        ControlBarView bar = new ControlBarView(ctx);
        bar.addExtra("Ana menü", () -> {
            hideOverlayBar();
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        bar.addExtra("Kapat", this::hideOverlayBar);
        bar.setOnInteraction(this::scheduleOverlayBarHide);
        bar.setOnPositionChanged(() -> {
            hideOverlayBar();
            showOverlayBar();
        });

        FrameLayout root = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hideOverlayBar();
                    return true;
                }
                scheduleOverlayBarHide();
                return super.dispatchKeyEvent(e);
            }
        };
        int side = Ui.dp(ctx, 32);
        int edge = Ui.dp(ctx, 16);
        root.setPadding(side, edge, side, edge);
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Prefs.barTop(this) ? Gravity.TOP : Gravity.BOTTOM;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(root, lp);
        } catch (Exception e) {
            Log.e(TAG, "Bar gösterilemedi", e);
            return;
        }
        overlayBar = root;
        bar.post(bar::focusDefault);
        scheduleOverlayBarHide();
    }

    private void hideOverlayBar() {
        mainHandler.removeCallbacks(hideBarRunnable);
        if (overlayBar == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlayBar);
        } catch (Exception ignored) {
        }
        overlayBar = null;
    }

    private void scheduleOverlayBarHide() {
        mainHandler.removeCallbacks(hideBarRunnable);
        mainHandler.postDelayed(hideBarRunnable, OVERLAY_BAR_HIDE_MS);
    }

    /** Güç menüsünü açar; açılan menüdeki "Kapat" düğmesine otomatik basılır. */
    public void triggerPowerDialog() {
        Log.d(TAG, "Triggering Power Dialog");
        shutdownTriggeredAt = SystemClock.elapsedRealtime();
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
    }

    /** Ekranı kapatır (Android 9+). */
    public boolean lockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }
}
