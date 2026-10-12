package com.kapeks.turnofftimer;

import android.content.Context;
import android.content.SharedPreferences;

/** Kalıcı ayarlar ve zamanlayıcı durumu. Tüm ekranlar (ana panel, tarayıcı barı, katmanlar) aynı ayarları paylaşır. */
public final class Prefs {
    public static final String NAME = "TimerPrefs";

    // Zamanlayıcı durumu (servis yeniden başlatılırsa geri yüklemek için)
    static final String END_TIME = "EndTime";          // System.currentTimeMillis() cinsinden bitiş
    static final String TOTAL = "TotalMillis";
    static final String SAVED_VOLUME = "SavedVolume";  // Kısılmadan önceki medya ses seviyesi
    static final String LAST_METHOD = "LastPowerMethod"; // Son denenen kapatma yöntemi (tanı için)

    // Ayarlar
    private static final String DIM_MODE = "DimMode";
    private static final String END_ACTION = "EndAction";
    private static final String INDICATOR = "Indicator";
    private static final String BAR_TOP = "BarTop";
    private static final String FADE_VOLUME = "FadeVolume";
    private static final String WARN = "WarnBeforeEnd";
    private static final String IDLE_DIM = "IdleDim";
    private static final String CUSTOM_MINUTES = "CustomMinutes";
    private static final String HOME_URL = "HomeUrl";
    private static final String LAST_APP = "LastApp";
    private static final String SCHEDULE_ENABLED = "ScheduleEnabled";
    private static final String SCHEDULE_MINUTE = "ScheduleMinuteOfDay";
    private static final String SHORTCUT_KEY = "ShortcutKey";

    static final String ACTION_SLEEP = "sleep";        // Ekranı kapat (bekleme)
    static final String ACTION_SHUTDOWN = "shutdown";  // Cihazı tamamen kapat
    static final String ACTION_BLACKOUT = "blackout";  // Sadece siyah ekran + sessiz

    static final int INDICATOR_OFF = 0;
    static final int INDICATOR_LAST5 = 1;
    static final int INDICATOR_ALWAYS = 2;

    static final String DEFAULT_URL = "https://www.youtube.com/tv";

    // -1: kapalı, 0: tüm süre boyunca kademeli, N: son N dakika
    private static final int[] DIM_CYCLE = {-1, 5, 10, 15, 30, 0};

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // --- Karartma ---
    static int dimMode(Context c) {
        return get(c).getInt(DIM_MODE, -1);
    }

    static void cycleDim(Context c) {
        int current = dimMode(c);
        int index = 0;
        for (int i = 0; i < DIM_CYCLE.length; i++) {
            if (DIM_CYCLE[i] == current) index = i;
        }
        get(c).edit().putInt(DIM_MODE, DIM_CYCLE[(index + 1) % DIM_CYCLE.length]).apply();
    }

    static String dimLabel(int mode) {
        if (mode < 0) return "Kapalı";
        if (mode == 0) return "Tüm süre";
        return "Son " + mode + " dk";
    }

    // --- Süre bitince ---
    static String endAction(Context c) {
        return get(c).getString(END_ACTION, ACTION_SHUTDOWN);
    }

    static void cycleEndAction(Context c) {
        String current = endAction(c);
        String next = ACTION_SLEEP.equals(current) ? ACTION_SHUTDOWN
                : ACTION_SHUTDOWN.equals(current) ? ACTION_BLACKOUT : ACTION_SLEEP;
        get(c).edit().putString(END_ACTION, next).apply();
    }

    static String endActionLabel(String action) {
        if (ACTION_SHUTDOWN.equals(action)) return "Tamamen kapat";
        if (ACTION_BLACKOUT.equals(action)) return "Karart + sessiz";
        return "Uyku (ekranı kapat)";
    }

    static String endActionShort(String action) {
        if (ACTION_SHUTDOWN.equals(action)) return "Kapat";
        if (ACTION_BLACKOUT.equals(action)) return "Karart";
        return "Uyku";
    }

    // --- Kalan süre göstergesi ---
    static int indicator(Context c) {
        return get(c).getInt(INDICATOR, INDICATOR_LAST5);
    }

    static void cycleIndicator(Context c) {
        int next = (indicator(c) + 1) % 3;
        get(c).edit().putInt(INDICATOR, next).apply();
    }

    static String indicatorLabel(int mode) {
        if (mode == INDICATOR_ALWAYS) return "Her zaman";
        if (mode == INDICATOR_LAST5) return "Son 5 dk";
        return "Kapalı";
    }

    // --- Aç/kapa ayarlar ---
    static boolean barTop(Context c) { return get(c).getBoolean(BAR_TOP, false); }
    static void toggleBarTop(Context c) { get(c).edit().putBoolean(BAR_TOP, !barTop(c)).apply(); }

    static boolean fadeVolume(Context c) { return get(c).getBoolean(FADE_VOLUME, true); }
    static void toggleFadeVolume(Context c) { get(c).edit().putBoolean(FADE_VOLUME, !fadeVolume(c)).apply(); }

    static boolean warn(Context c) { return get(c).getBoolean(WARN, true); }
    static void toggleWarn(Context c) { get(c).edit().putBoolean(WARN, !warn(c)).apply(); }

    static boolean idleDim(Context c) { return get(c).getBoolean(IDLE_DIM, false); }
    static void toggleIdleDim(Context c) { get(c).edit().putBoolean(IDLE_DIM, !idleDim(c)).apply(); }

    // --- Diğer ---
    static int customMinutes(Context c) { return get(c).getInt(CUSTOM_MINUTES, 45); }
    static void setCustomMinutes(Context c, int minutes) { get(c).edit().putInt(CUSTOM_MINUTES, minutes).apply(); }

    static String homeUrl(Context c) { return get(c).getString(HOME_URL, DEFAULT_URL); }
    static void setHomeUrl(Context c, String url) { get(c).edit().putString(HOME_URL, url).apply(); }

    static String lastApp(Context c) { return get(c).getString(LAST_APP, null); }
    static void setLastApp(Context c, String pkg) { get(c).edit().putString(LAST_APP, pkg).apply(); }

    // --- Her gün belirli saatte kapanma ---
    static boolean scheduleEnabled(Context c) { return get(c).getBoolean(SCHEDULE_ENABLED, false); }
    static void setScheduleEnabled(Context c, boolean on) { get(c).edit().putBoolean(SCHEDULE_ENABLED, on).apply(); }

    /** Günün dakikası (0-1439). Varsayılan 01:00. */
    static int scheduleMinute(Context c) { return get(c).getInt(SCHEDULE_MINUTE, 60); }
    static void setScheduleMinute(Context c, int minuteOfDay) {
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        get(c).edit().putInt(SCHEDULE_MINUTE, m).apply();
    }

    // --- Her uygulamada barı açan kumanda tuşu (uzun veya çift basış) ---
    static int shortcutKey(Context c) { return get(c).getInt(SHORTCUT_KEY, android.view.KeyEvent.KEYCODE_MENU); }
    static void setShortcutKey(Context c, int keyCode) { get(c).edit().putInt(SHORTCUT_KEY, keyCode).apply(); }

    static String keyLabel(int keyCode) {
        String s = android.view.KeyEvent.keyCodeToString(keyCode);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }
}
