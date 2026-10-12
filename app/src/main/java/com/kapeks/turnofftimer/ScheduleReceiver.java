package com.kapeks.turnofftimer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;
import java.util.Locale;

/**
 * Her gün belirli saatte kapanma.
 * Kapanma saatinden {@link #LEAD_MS} önce alarm çalar ve zamanlayıcıyı tam o saate göre başlatır;
 * böylece karartma, ses kısma ve 1 dk uyarısı (+15 dk erteleme) normal zamanlayıcıdaki gibi çalışır.
 * Cihaz kapanıp açılınca (BOOT_COMPLETED) alarm yeniden kurulur.
 */
public class ScheduleReceiver extends BroadcastReceiver {
    private static final String TAG = "ScheduleReceiver";
    static final String ACTION_ALARM = "com.kapeks.turnofftimer.SCHEDULED_SHUTDOWN";
    private static final String EXTRA_TARGET = "target";
    static final long LEAD_MS = 5 * 60_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_ALARM.equals(action)) {
            long target = intent.getLongExtra(EXTRA_TARGET, 0);
            long remaining = target - System.currentTimeMillis();
            if (Prefs.scheduleEnabled(context) && remaining > 0 && remaining <= LEAD_MS + 60_000L) {
                // Daha erken bitecek bir zamanlayıcı zaten çalışıyorsa ona dokunma
                boolean earlierRunning = TimerService.isRunning() && TimerService.getRemaining() <= remaining;
                if (!earlierRunning) {
                    Log.d(TAG, "Planlı kapanma: " + remaining / 1000 + " sn sonra");
                    TimerService.start(context, remaining);
                }
            }
            apply(context, target + 60_000L);
        } else {
            // Açılış, uygulama güncellemesi, saat/saat dilimi değişimi
            apply(context);
        }
    }

    static void apply(Context c) {
        apply(c, System.currentTimeMillis());
    }

    /** Planlı kapanmayı (yeniden) kurar; kapalıysa iptal eder. */
    private static void apply(Context c, long after) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, ScheduleReceiver.class).setAction(ACTION_ALARM);
        PendingIntent cancel = PendingIntent.getBroadcast(c, 10, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(cancel);
        if (!Prefs.scheduleEnabled(c)) return;

        long target = nextOccurrence(Prefs.scheduleMinute(c), after);
        long fireAt = Math.max(target - LEAD_MS, System.currentTimeMillis() + 1000);
        i.putExtra(EXTRA_TARGET, target);
        PendingIntent pi = PendingIntent.getBroadcast(c, 10, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
        }
        Log.d(TAG, "Planlı kapanma kuruldu: " + describe(target));
    }

    /** after'dan sonraki ilk HH:MM anı. */
    static long nextOccurrence(int minuteOfDay, long after) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(after);
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        cal.set(Calendar.MINUTE, minuteOfDay % 60);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= after) cal.add(Calendar.DAY_OF_MONTH, 1);
        return cal.getTimeInMillis();
    }

    static String formatMinute(int minuteOfDay) {
        return String.format(Locale.US, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }

    /** "bugün 23:30" / "yarın 01:00" */
    static String describe(long target) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(target);
        String time = formatMinute(t.get(Calendar.HOUR_OF_DAY) * 60 + t.get(Calendar.MINUTE));
        boolean today = now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
                && now.get(Calendar.YEAR) == t.get(Calendar.YEAR);
        return (today ? "bugün " : "yarın ") + time;
    }
}
