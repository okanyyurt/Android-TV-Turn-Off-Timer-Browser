package com.kapeks.turnofftimer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class TimerService extends Service {
    private static final String CHANNEL_ID = "TimerServiceChannel";
    public static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "com.kapeks.turnofftimer.START";
    public static final String ACTION_STOP = "com.kapeks.turnofftimer.STOP";
    public static final String EXTRA_MILLIS = "MILLIS";
    public static final String EXTRA_DIM = "DIM";

    public static final String PREFS_NAME = "TimerPrefs";
    public static final String PREF_END_TIME = "EndTime";
    public static final String PREF_DIM_ENABLED = "DimEnabled";

    private CountDownTimer timer;
    private WindowManager windowManager;
    private View dimOverlayView;
    private boolean isDimmingEnabled = false;
    private long totalMillis;
    private long dimStartMillis = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            long millis = intent.getLongExtra(EXTRA_MILLIS, 0);
            isDimmingEnabled = intent.getBooleanExtra(EXTRA_DIM, false);
            dimStartMillis = intent.getLongExtra("DIM_START_MILLIS", 0);
            startTimer(millis);
        } else if (ACTION_STOP.equals(action)) {
            stopTimerAndClearPrefs();
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startTimer(long millis) {
        if (timer != null) {
            timer.cancel();
        }
        removeDimOverlay();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
             .putLong(PREF_END_TIME, System.currentTimeMillis() + millis)
             .putBoolean(PREF_DIM_ENABLED, isDimmingEnabled)
             .apply();

        totalMillis = millis;

        if (isDimmingEnabled) {
            addDimOverlay();
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TV Kapanma Zamanlayıcısı")
                .setContentText("Sayaç çalışıyor...")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(1, notification);

        timer = new CountDownTimer(millis, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                Intent updateIntent = new Intent("TIMER_TICK");
                updateIntent.putExtra("millisUntilFinished", millisUntilFinished);

                int alpha = 0;
                if (isDimmingEnabled) {
                    if (dimStartMillis > 0) {
                        if (millisUntilFinished <= dimStartMillis) {
                            float progress = 1.0f - ((float) millisUntilFinished / dimStartMillis);
                            alpha = (int) (progress * 220);
                        }
                    } else {
                        // Original behavior: dim over the entire duration
                        float progress = 1.0f - ((float) millisUntilFinished / totalMillis);
                        alpha = (int) (progress * 220);
                    }
                    
                    if (dimOverlayView != null) {
                        dimOverlayView.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
                    }
                }
                
                updateIntent.putExtra("dimAlpha", alpha);
                sendBroadcast(updateIntent);
            }

            @Override
            public void onFinish() {
                sendBroadcast(new Intent("TIMER_FINISH"));
                executeShutdown();
                stopSelf();
            }
        };
        timer.start();
    }

    private void addDimOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Log.e("TimerService", "No permission to draw overlays");
            return;
        }

        dimOverlayView = new View(this);
        dimOverlayView.setBackgroundColor(Color.TRANSPARENT);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(dimOverlayView, params);
        } catch (Exception e) {
            Log.e("TimerService", "Failed to add overlay", e);
        }
    }

    private void removeDimOverlay() {
        if (dimOverlayView != null && windowManager != null) {
            try {
                windowManager.removeView(dimOverlayView);
            } catch (Exception e) {
                // Ignore
            }
            dimOverlayView = null;
        }
    }

    private void executeShutdown() {
        Log.d("TimerService", "Timer finished. Triggering shutdown fallback.");
        
        // Tier 1: Try Root Full Shutdown
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "reboot -p"});
            process.waitFor();
            Log.d("TimerService", "Root shutdown triggered");
        } catch (Exception e) {}

        // Tier 1.5: Try Plain Shutdown (if su is not needed)
        try {
            Process process = Runtime.getRuntime().exec("reboot -p");
            process.waitFor();
        } catch (Exception e) {}

        // Tier 2: Try Root Power Button Press (Sleep)
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", "input keyevent 26"});
            process.waitFor();
            Log.d("TimerService", "Root power button triggered");
        } catch (Exception e) {}

        // Tier 2.5: Try Plain Power Button Press
        try {
            Process process = Runtime.getRuntime().exec("input keyevent 26");
            process.waitFor();
        } catch (Exception e) {}

        // Tier 3: Try System Shutdown Intent (Works on some Custom ROMs)
        try {
            Intent shutdownIntent = new Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN");
            shutdownIntent.putExtra("android.intent.extra.KEY_CONFIRM", false);
            shutdownIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(shutdownIntent);
        } catch (Exception e) {}

        // Ultimate Fallback: Fake Sleep (Mute + Pitch Black Overlay)
        try {
            android.media.AudioManager audio = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0);
            }
        } catch (Exception e) {}

        if (dimOverlayView != null) {
            dimOverlayView.setBackgroundColor(Color.BLACK);
        } else {
            // Force add overlay if it wasn't added
            isDimmingEnabled = true;
            addDimOverlay();
            if (dimOverlayView != null) {
                dimOverlayView.setBackgroundColor(Color.BLACK);
            }
        }
    }

    private void stopTimerAndClearPrefs() {
        if (timer != null) timer.cancel();
        removeDimOverlay();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().remove(PREF_END_TIME).remove(PREF_DIM_ENABLED).apply();
    }

    @Override
    public void onDestroy() {
        stopTimerAndClearPrefs();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
