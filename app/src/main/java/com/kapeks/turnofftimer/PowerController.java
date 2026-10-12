package com.kapeks.turnofftimer;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Süre dolduğunda ekranı / cihazı kapatma motoru.
 * Her yöntemden sonra ekranın gerçekten kapandığını PowerManager ile doğrular, olmazsa bir sonrakine geçer.
 * Hiçbiri çalışmazsa çağıran taraf "sahte uyku" (siyah ekran + sessiz) moduna geçer.
 */
final class PowerController {
    private static final String TAG = "PowerController";

    interface Callback {
        /** Ana iş parçacığında çağrılır. */
        void onResult(boolean screenOff, String method);
    }

    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su"
    };

    private static final AudioManager.OnAudioFocusChangeListener focusListener = change -> { };
    private static AudioFocusRequest focusRequest;
    private static boolean legacyFocusHeld;

    private PowerController() {}

    static void run(Context context, String action, Callback callback) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());

        // 1) Oynatılan medyayı her uygulamada durdur ve sesi kapat
        pauseMedia(app);
        requestFocus(app);
        fadeVolume(app, 0f);

        if (Prefs.ACTION_BLACKOUT.equals(action)) {
            callback.onResult(false, null);
            return;
        }

        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TurnOffTimer:power");
        wakeLock.acquire(60_000L);

        new Thread(() -> {
            String method = null;
            try {
                method = tryScreenOff(app, action, main);
            } catch (Exception e) {
                Log.e(TAG, "Kapatma denemesi hata verdi", e);
            }
            final String result = method;
            main.post(() -> {
                if (wakeLock.isHeld()) wakeLock.release();
                callback.onResult(result != null, result);
            });
        }, "power-off").start();
    }

    /** Arka planda çalışır. Ekranı kapatan yöntemin adını, hiçbiri çalışmazsa null döner. */
    private static String tryScreenOff(Context c, String action, Handler main) {
        if (Prefs.ACTION_SHUTDOWN.equals(action)) {
            // Cihaz kapanınca süreç ölür; ses seviyesini önceden geri yükle (medya zaten duraklatıldı)
            restoreVolume(c);

            // Önceki sürümün (V1_008) cihazı tamamen kapatan yöntemleri, aynı sırayla ve root kontrolü olmadan
            attempt(c, "su -c reboot -p");
            run(new String[]{"su", "-c", "reboot -p"}, 3000);
            if (waitForScreenOff(c, 2000)) return "su -c reboot -p";

            attempt(c, "reboot -p");
            run(new String[]{"reboot", "-p"}, 3000);
            if (waitForScreenOff(c, 2000)) return "reboot -p";

            attempt(c, "sistem kapatma isteği");
            try {
                Intent i = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
                i.putExtra("android.intent.extra.KEY_CONFIRM", false);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                if (waitForScreenOff(c, 8000)) return "sistem kapatma isteği";
            } catch (Exception e) {
                Log.w(TAG, "Sistem kapatma isteği reddedildi", e);
            }

            PowerAccessibilityService svc = PowerAccessibilityService.getInstance();
            if (svc != null) {
                attempt(c, "güç menüsü");
                main.post(svc::triggerPowerDialog);
                if (waitForScreenOff(c, 10_000)) return "güç menüsü";
            }
            fadeVolume(c, 0f);
        }

        // Uyku (bekleme): ekranı kapat
        if (isAdminActive(c)) {
            try {
                ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow();
            } catch (Exception e) {
                Log.w(TAG, "lockNow başarısız", e);
            }
            if (waitForScreenOff(c, 3000)) return "cihaz yöneticisi";
        }

        PowerAccessibilityService svc = PowerAccessibilityService.getInstance();
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            main.post(svc::lockScreen);
            if (waitForScreenOff(c, 3000)) return "erişilebilirlik";
        }

        // Güç tuşu simülasyonu (önceki sürümdeki gibi önce su ile, sonra doğrudan)
        attempt(c, "su -c input keyevent 26");
        run(new String[]{"su", "-c", "input keyevent 26"}, 3000);
        if (waitForScreenOff(c, 2000)) return "su -c input keyevent 26";

        attempt(c, "input keyevent 26");
        run(new String[]{"input", "keyevent", "26"}, 3000);
        if (waitForScreenOff(c, 2000)) return "input keyevent 26";
        return null;
    }

    // ------------------------------------------------------------------ Durum bilgisi (ayar ekranı için)

    static boolean isAdminActive(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isAdminActive(new ComponentName(c, MyDeviceAdminReceiver.class));
    }

    static boolean hasRoot() {
        for (String path : SU_PATHS) {
            if (new File(path).exists()) return true;
        }
        return false;
    }

    /** Seçilen "bitince" davranışında hangi yöntemlerin sırayla deneneceğini anlatır. */
    static String describeChain(Context c, String action) {
        boolean access = PowerAccessibilityService.getInstance() != null;
        boolean root = hasRoot();
        List<String> steps = new ArrayList<>();
        steps.add("Medyayı duraklat + sesi kıs");
        if (Prefs.ACTION_SHUTDOWN.equals(action)) {
            steps.add("Kapatma komutları (reboot -p" + (root ? ", root" : "") + ")");
            steps.add("Sistem kapatma isteği");
            if (access) steps.add("Güç menüsünden 'Kapat'");
        }
        if (!Prefs.ACTION_BLACKOUT.equals(action)) {
            if (isAdminActive(c)) steps.add("Cihaz yöneticisi ile ekranı kapat");
            if (access && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) steps.add("Erişilebilirlik ile ekranı kapat");
            steps.add("Güç tuşu komutu");
        }
        steps.add(Prefs.ACTION_BLACKOUT.equals(action) ? "Siyah ekran" : "Olmazsa siyah ekran");
        String last = Prefs.get(c).getString(Prefs.LAST_METHOD, null);
        return TextUtils.join("  →  ", steps)
                + (last != null ? "\nSon denenen yöntem: " + last : "");
    }

    // ------------------------------------------------------------------ Ses

    private static AudioManager audio(Context c) {
        return (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Aktif medya oturumuna (YouTube, Netflix, oynatıcılar) "duraklat" tuşu gönderir. */
    static void pauseMedia(Context c) {
        try {
            AudioManager am = audio(c);
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        } catch (Exception e) {
            Log.w(TAG, "Medya duraklatılamadı", e);
        }
    }

    /** Medya sesini, kısılmadan önceki seviyenin verilen oranına (0..1) çeker. İlk çağrıda asıl seviyeyi kaydeder. */
    static void fadeVolume(Context c, float fraction) {
        try {
            AudioManager am = audio(c);
            SharedPreferences p = Prefs.get(c);
            int saved = p.getInt(Prefs.SAVED_VOLUME, -1);
            if (saved < 0) {
                saved = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                p.edit().putInt(Prefs.SAVED_VOLUME, saved).apply();
            }
            int target = Math.round(saved * Math.max(0f, Math.min(1f, fraction)));
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != target) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Ses ayarlanamadı", e);
        }
    }

    private static void restoreVolume(Context c) {
        try {
            SharedPreferences p = Prefs.get(c);
            int saved = p.getInt(Prefs.SAVED_VOLUME, -1);
            if (saved >= 0) {
                audio(c).setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0);
                p.edit().remove(Prefs.SAVED_VOLUME).apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "Ses geri yüklenemedi", e);
        }
    }

    /** Kısılan sesi geri getirir ve ses odağını bırakır. Ana iş parçacığından çağrılmalı. */
    static void restoreAudio(Context c) {
        restoreVolume(c);
        abandonFocus(c);
    }

    /** Kalıcı ses odağı ister: diğer uygulamalar çalmayı bırakır ve kendiliğinden devam etmez. */
    private static void requestFocus(Context c) {
        try {
            AudioManager am = audio(c);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                am.requestAudioFocus(req);
                focusRequest = req;
            } else {
                am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                legacyFocusHeld = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Ses odağı alınamadı", e);
        }
    }

    private static void abandonFocus(Context c) {
        try {
            AudioManager am = audio(c);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
                focusRequest = null;
            } else if (legacyFocusHeld) {
                am.abandonAudioFocus(focusListener);
                legacyFocusHeld = false;
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ Yardımcılar

    private static boolean waitForScreenOff(Context c, long timeoutMs) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < end) {
            if (!pm.isInteractive()) return true;
            SystemClock.sleep(250);
        }
        return !pm.isInteractive();
    }

    /** Komutu çalıştırır ve en fazla waitMs kadar bitmesini bekler. */
    private static void run(String[] command, long waitMs) {
        try {
            Process p = Runtime.getRuntime().exec(command);
            long end = SystemClock.elapsedRealtime() + waitMs;
            while (SystemClock.elapsedRealtime() < end) {
                try {
                    int code = p.exitValue();
                    Log.d(TAG, TextUtils.join(" ", command) + " -> çıkış kodu " + code);
                    return;
                } catch (IllegalThreadStateException stillRunning) {
                    SystemClock.sleep(100);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Komut çalıştırılamadı: " + TextUtils.join(" ", command) + " (" + e.getMessage() + ")");
        }
    }

    /** Denenen yöntemi kaydeder; cihaz kapanırsa bir sonraki açılışta ana ekranda hangisinin işe yaradığı görünür. */
    private static void attempt(Context c, String method) {
        Log.d(TAG, "Deneniyor: " + method);
        Prefs.get(c).edit().putString(Prefs.LAST_METHOD, method).commit();
    }
}
