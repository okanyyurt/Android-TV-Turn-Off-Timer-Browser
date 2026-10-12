package com.kapeks.turnofftimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tarayıcıdan bağımsız, sistem genelinde çalışan zamanlayıcı.
 * Hangi uygulama açık olursa olsun karartmayı, kalan süre göstergesini ve 1 dk uyarısını
 * {@link OverlayController} ile gösterir; süre dolunca {@link PowerController} ile ekranı/cihazı kapatmayı dener,
 * olmazsa "sahte uyku" (siyah ekran + sessiz) moduna geçer.
 */
public class TimerService extends Service {
    private static final String TAG = "TimerService";
    private static final String CHANNEL_ID = "TimerServiceChannel";
    public static final int NOTIFICATION_ID = 1;

    public static final String ACTION_START = "com.kapeks.turnofftimer.START";
    public static final String ACTION_STOP = "com.kapeks.turnofftimer.STOP";
    public static final String ACTION_EXTEND = "com.kapeks.turnofftimer.EXTEND";
    public static final String ACTION_FINISH_NOW = "com.kapeks.turnofftimer.FINISH_NOW";
    public static final String ACTION_WAKE = "com.kapeks.turnofftimer.WAKE";
    public static final String EXTRA_MILLIS = "MILLIS";

    static final long WARNING_MS = 60_000L;
    static final long FADE_MS = 30_000L;
    private static final long FLASH_MS = 5_000L;
    private static final long INDICATOR_LAST_MS = 5 * 60_000L;
    private static final long LATE_FINISH_LIMIT_MS = 10 * 60_000L;
    private static final int MAX_DIM_ALPHA = 220;

    public enum State { IDLE, RUNNING, FINISHING, SLEEPING }

    /** Ana iş parçacığında, her saniye ve her durum değişiminde çağrılır. */
    public interface Listener {
        void onTimerUpdate();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile State state = State.IDLE;
    private static volatile long endElapsed;
    private static volatile long totalMillis;
    private static volatile long flashUntil;
    private static volatile boolean warningDismissed;
    private static volatile boolean inAppUi;
    private static TimerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private OverlayController overlay;
    private int lastNotifiedMinute = -1;

    // ------------------------------------------------------------------ Durum (UI için)

    public static State getState() {
        return state;
    }

    public static boolean isRunning() {
        return state == State.RUNNING;
    }

    public static long getRemaining() {
        if (state != State.RUNNING) return 0;
        return Math.max(0, endElapsed - SystemClock.elapsedRealtime());
    }

    public static boolean isWarningActive(Context c) {
        return state == State.RUNNING && !warningDismissed && Prefs.warn(c) && getRemaining() <= WARNING_MS;
    }

    public static boolean isIndicatorVisible(Context c) {
        if (state != State.RUNNING) return false;
        if (SystemClock.elapsedRealtime() < flashUntil) return true;
        int mode = Prefs.indicator(c);
        return mode == Prefs.INDICATOR_ALWAYS
                || (mode == Prefs.INDICATOR_LAST5 && getRemaining() <= INDICATOR_LAST_MS);
    }

    /** Ekranın ne kadar karartılacağı (0-255). Süre dolduğunda tamamen siyah. */
    public static int getDimAlpha(Context c) {
        if (state == State.FINISHING || state == State.SLEEPING) return 255;
        if (state != State.RUNNING) return 0;
        int mode = Prefs.dimMode(c);
        if (mode < 0) return 0;
        long remaining = getRemaining();
        long window = mode == 0 ? totalMillis : mode * 60_000L;
        if (window <= 0 || remaining >= window) return 0;
        float progress = 1f - (float) remaining / window;
        return (int) (progress * MAX_DIM_ALPHA);
    }

    public static void addListener(Listener l) {
        listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Dahili tarayıcı ön plandayken kendi bar/karartma/uyarısını çizer; küresel katmanlar gizlenir. */
    public static boolean isInAppUi() {
        return inAppUi;
    }

    public static void setInAppUi(boolean active) {
        inAppUi = active;
        if (instance != null) instance.refreshOverlays();
    }

    // ------------------------------------------------------------------ Komutlar (UI için)

    public static void start(Context c, long millis) {
        Intent i = new Intent(c, TimerService.class).setAction(ACTION_START).putExtra(EXTRA_MILLIS, millis);
        ContextCompat.startForegroundService(c, i);
    }

    public static void extend(Context c, long millis) {
        if (state != State.RUNNING) {
            start(c, millis);
        } else {
            send(c, ACTION_EXTEND, millis);
        }
    }

    public static void stop(Context c) {
        if (instance == null) {
            PowerController.restoreAudio(c);
            return;
        }
        send(c, ACTION_STOP, 0);
    }

    public static void finishNow(Context c) {
        send(c, ACTION_FINISH_NOW, 0);
    }

    public static void wake(Context c) {
        if (instance != null) send(c, ACTION_WAKE, 0);
    }

    public static void dismissWarning() {
        warningDismissed = true;
        if (instance != null) instance.refreshOverlays();
        notifyListeners();
    }

    private static void send(Context c, String action, long millis) {
        Intent i = new Intent(c, TimerService.class).setAction(action).putExtra(EXTRA_MILLIS, millis);
        try {
            c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "Servise komut gönderilemedi: " + action, e);
        }
    }

    private static void notifyListeners() {
        for (Listener l : listeners) l.onTimerUpdate();
    }

    // ------------------------------------------------------------------ Servis yaşam döngüsü

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        overlay = new OverlayController(this, overlayCallbacks);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        long millis = intent != null ? intent.getLongExtra(EXTRA_MILLIS, 0) : 0;

        if (action == null) {
            // Sistem servisi öldürüp yeniden başlattı: kayıtlı bitiş zamanından devam et
            restoreAfterRestart();
        } else if (ACTION_START.equals(action)) {
            startTimer(Math.max(1000, millis), Math.max(1000, millis));
        } else if (ACTION_EXTEND.equals(action)) {
            extendTimer(millis);
        } else if (ACTION_FINISH_NOW.equals(action)) {
            if (state == State.RUNNING) finishTimer();
        } else if (ACTION_WAKE.equals(action)) {
            wakeUp();
        } else if (ACTION_STOP.equals(action)) {
            cancelTimer();
        }

        if (state == State.IDLE) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        overlay.removeAll();
        if (instance == this) instance = null;
        if (state != State.IDLE) {
            state = State.IDLE;
            notifyListeners();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ Zamanlayıcı

    private void startTimer(long millis, long total) {
        handler.removeCallbacks(tick);
        PowerController.restoreAudio(this);

        state = State.RUNNING;
        totalMillis = Math.max(total, millis);
        endElapsed = SystemClock.elapsedRealtime() + millis;
        warningDismissed = false;
        flashUntil = SystemClock.elapsedRealtime() + FLASH_MS;
        lastNotifiedMinute = -1;
        persistEndTime();

        startForeground(NOTIFICATION_ID, buildNotification(statusText(millis)));
        handler.post(tick);
    }

    private void extendTimer(long millis) {
        if (state != State.RUNNING) {
            startTimer(Math.max(1000, millis), Math.max(1000, millis));
            return;
        }
        endElapsed += millis;
        totalMillis += millis;
        warningDismissed = false;
        flashUntil = SystemClock.elapsedRealtime() + FLASH_MS;
        PowerController.restoreAudio(this); // ses kısılmaya başladıysa geri aç
        persistEndTime();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void persistEndTime() {
        Prefs.get(this).edit()
                .putLong(Prefs.END_TIME, System.currentTimeMillis() + getRemaining())
                .putLong(Prefs.TOTAL, totalMillis)
                .apply();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (state != State.RUNNING) return;
            long remaining = getRemaining();
            if (remaining <= 0) {
                finishTimer();
                return;
            }
            if (Prefs.fadeVolume(TimerService.this) && remaining <= FADE_MS) {
                PowerController.fadeVolume(TimerService.this, (float) remaining / FADE_MS);
            }
            refreshOverlays();
            updateNotificationIfNeeded(remaining);
            notifyListeners();

            // Bir sonraki tam saniye sınırında tekrar çalış
            long delay = remaining % 1000;
            if (delay < 20) delay += 1000;
            handler.postDelayed(this, delay);
        }
    };

    private void finishTimer() {
        handler.removeCallbacks(tick);
        state = State.FINISHING;
        Prefs.get(this).edit().remove(Prefs.END_TIME).remove(Prefs.TOTAL).apply();
        refreshOverlays();
        notifyListeners();
        updateNotification("Süre doldu, kapatılıyor...");

        PowerController.run(this, Prefs.endAction(this), (screenOff, method) -> {
            if (state != State.FINISHING) return; // bu sırada iptal edildi / yeniden başlatıldı
            if (screenOff) {
                Log.d(TAG, "Ekran kapatıldı: " + method);
                // Medya duraklatıldı; ses seviyesini bir sonraki açılış için geri getir
                PowerController.restoreAudio(this);
                goIdle();
            } else {
                enterSleep();
            }
        });
    }

    /** Ekran kapatılamadı: siyah ekran + sessiz, bir tuşa basılana kadar. */
    private void enterSleep() {
        state = State.SLEEPING;
        refreshOverlays();
        notifyListeners();
        updateNotification("Uyku modu (siyah ekran). Uyandırmak için bir tuşa basın.");
        if (!inAppUi && !OverlayController.canDraw(this)) {
            try {
                startActivity(new Intent(this, SleepActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.w(TAG, "Uyku ekranı açılamadı", e);
            }
        }
    }

    private void wakeUp() {
        if (state != State.SLEEPING) return;
        PowerController.restoreAudio(this);
        goIdle();
    }

    private void cancelTimer() {
        handler.removeCallbacks(tick);
        state = State.IDLE;
        Prefs.get(this).edit().remove(Prefs.END_TIME).remove(Prefs.TOTAL).apply();
        PowerController.restoreAudio(this);
        goIdle();
    }

    private void goIdle() {
        handler.removeCallbacks(tick);
        state = State.IDLE;
        overlay.removeAll();
        notifyListeners();
        stopForeground(true);
        stopSelf();
    }

    private void restoreAfterRestart() {
        SharedPreferences p = Prefs.get(this);
        long end = p.getLong(Prefs.END_TIME, 0);
        long now = System.currentTimeMillis();
        if (end > now) {
            startTimer(end - now, p.getLong(Prefs.TOTAL, end - now));
        } else if (end > 0 && now - end < LATE_FINISH_LIMIT_MS) {
            startForeground(NOTIFICATION_ID, buildNotification("Süre doldu, kapatılıyor..."));
            finishTimer();
        } else {
            p.edit().remove(Prefs.END_TIME).remove(Prefs.TOTAL).apply();
            PowerController.restoreAudio(this);
        }
    }

    /** Küresel katmanları duruma göre günceller (tarayıcı ön plandaysa gizler). */
    private void refreshOverlays() {
        boolean global = !inAppUi;
        long remaining = getRemaining();
        overlay.setDim(global ? getDimAlpha(this) : 0);
        overlay.setIndicator(global && isIndicatorVisible(this), Ui.format(remaining));
        overlay.setWarning(global && isWarningActive(this), remaining);
        overlay.setBlackout(global && state == State.SLEEPING);
    }

    private final OverlayController.Callbacks overlayCallbacks = new OverlayController.Callbacks() {
        @Override public void onExtend(long millis) { extendTimer(millis); }
        @Override public void onFinishNow() { if (state == State.RUNNING) finishTimer(); }
        @Override public void onCancel() { cancelTimer(); }
        @Override public void onDismissWarning() { dismissWarning(); }
        @Override public void onWake() { wakeUp(); }
    };

    // ------------------------------------------------------------------ Bildirim

    private String statusText(long remaining) {
        long minutes = (remaining + 59_999) / 60_000;
        return "Kalan: " + minutes + " dk  •  Bitince: " + Prefs.endActionLabel(Prefs.endAction(this));
    }

    private void updateNotificationIfNeeded(long remaining) {
        int minute = (int) ((remaining + 59_999) / 60_000);
        if (minute != lastNotifiedMinute) {
            lastNotifiedMinute = minute;
            updateNotification(statusText(remaining));
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent extend = PendingIntent.getService(this, 1,
                new Intent(this, TimerService.class).setAction(ACTION_EXTEND).putExtra(EXTRA_MILLIS, 15 * 60_000L), flags);
        PendingIntent cancel = PendingIntent.getService(this, 2,
                new Intent(this, TimerService.class).setAction(ACTION_STOP), flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TV Kapanma Zamanlayıcısı")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "+15 dk", extend)
                .addAction(0, "İptal", cancel)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Timer Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
