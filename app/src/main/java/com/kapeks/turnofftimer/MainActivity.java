package com.kapeks.turnofftimer;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ana panel: zamanlayıcıyı kur, her gün belirli saatte kapanma, izlenecek uygulamayı aç. Ayarlar dişli simgesinde. */
public class MainActivity extends AppCompatActivity implements TimerService.Listener {
    private static final int[] PRESETS = {15, 30, 45, 60, 90, 120};
    private static final int[] PRESET_IDS = {R.id.btn15, R.id.btn30, R.id.btn45, R.id.btn60, R.id.btn90, R.id.btn120};
    private static final int MAX_APPS = 40;

    private TextView tvCountdown;
    private TextView tvStatus;
    private TextView tvCustom;
    private TextView tvScheduleTime;
    private TextView tvScheduleInfo;
    private TextView tvShortcutHint;
    private Button btnSchedule;
    private LinearLayout appsRow;
    private int customMinutes;

    private static class AppEntry {
        final String pkg;
        final String label;
        final Drawable icon;

        AppEntry(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.hideSystemBars(this);

        tvCountdown = findViewById(R.id.tvCountdown);
        tvStatus = findViewById(R.id.tvStatus);
        tvCustom = findViewById(R.id.tvCustom);
        tvScheduleTime = findViewById(R.id.tvScheduleTime);
        tvScheduleInfo = findViewById(R.id.tvScheduleInfo);
        tvShortcutHint = findViewById(R.id.tvShortcutHint);
        btnSchedule = findViewById(R.id.btnSchedule);
        appsRow = findViewById(R.id.appsRow);

        // Süre
        for (int i = 0; i < PRESETS.length; i++) {
            final int minutes = PRESETS[i];
            findViewById(PRESET_IDS[i]).setOnClickListener(v -> startTimer(minutes * 60_000L, minutes + " dk"));
        }
        customMinutes = Prefs.customMinutes(this);
        setCustomMinutes(customMinutes);
        findViewById(R.id.btnMinus).setOnClickListener(v -> setCustomMinutes(customMinutes - 5));
        findViewById(R.id.btnPlus).setOnClickListener(v -> setCustomMinutes(customMinutes + 5));
        findViewById(R.id.btnStartCustom).setOnClickListener(v -> startTimer(customMinutes * 60_000L, customMinutes + " dk"));
        findViewById(R.id.btnExtend).setOnClickListener(v -> {
            TimerService.extend(this, 15 * 60_000L);
            Ui.toast(this, "+15 dk eklendi");
        });
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            TimerService.stop(this);
            Ui.toast(this, "Zamanlayıcı iptal edildi");
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        // Her gün belirli saatte
        btnSchedule.setOnClickListener(v -> {
            Prefs.setScheduleEnabled(this, !Prefs.scheduleEnabled(this));
            applySchedule(true);
        });
        findViewById(R.id.btnHourMinus).setOnClickListener(v -> shiftSchedule(-60));
        findViewById(R.id.btnHourPlus).setOnClickListener(v -> shiftSchedule(60));
        findViewById(R.id.btnMinuteMinus).setOnClickListener(v -> shiftSchedule(-5));
        findViewById(R.id.btnMinutePlus).setOnClickListener(v -> shiftSchedule(5));

        loadApps();
        ScheduleReceiver.apply(this);
        findViewById(R.id.btn30).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(this);
        // Önceki bir oturumdan kısık kalmış ses varsa geri getir
        if (TimerService.getState() == TimerService.State.IDLE) PowerController.restoreAudio(this);
        TimerService.addListener(this);
        updateCountdown();
        refreshSchedule();
        String action = Prefs.endAction(this);
        tvStatus.setText("Bitince: " + Prefs.endActionLabel(action)
                + "   •   Karartma: " + Prefs.dimLabel(Prefs.dimMode(this)));
        tvShortcutHint.setText(PowerAccessibilityService.getInstance() != null
                ? "İpucu: Başka bir uygulamadayken " + Prefs.keyLabel(Prefs.shortcutKey(this))
                    + " tuşuna uzun veya iki kez basınca zamanlayıcı barı açılır."
                : "İpucu: Erişilebilirlik servisini açarsanız (Ayarlar ⚙), başka uygulamadayken bir kumanda tuşuyla zamanlayıcı barını açabilirsiniz.");
    }

    @Override
    protected void onPause() {
        TimerService.removeListener(this);
        super.onPause();
    }

    @Override
    public void onTimerUpdate() {
        updateCountdown();
    }

    // ------------------------------------------------------------------ Zamanlayıcı

    private void startTimer(long millis, String label) {
        TimerService.start(this, millis);
        Ui.toast(this, "Zamanlayıcı başlatıldı: " + label);
    }

    private void setCustomMinutes(int minutes) {
        customMinutes = Math.max(5, Math.min(600, minutes));
        Prefs.setCustomMinutes(this, customMinutes);
        tvCustom.setText(customMinutes + " dk");
    }

    private void updateCountdown() {
        switch (TimerService.getState()) {
            case RUNNING:
                tvCountdown.setText(Ui.format(TimerService.getRemaining()));
                break;
            case FINISHING:
                tvCountdown.setText("Kapatılıyor…");
                break;
            case SLEEPING:
                tvCountdown.setText("Uyku modu");
                break;
            default:
                tvCountdown.setText("Kapalı");
        }
    }

    // ------------------------------------------------------------------ Her gün belirli saatte

    private void shiftSchedule(int minutes) {
        Prefs.setScheduleMinute(this, Prefs.scheduleMinute(this) + minutes);
        if (!Prefs.scheduleEnabled(this)) Prefs.setScheduleEnabled(this, true); // saat değiştirmek açar
        applySchedule(false);
    }

    private void applySchedule(boolean announce) {
        ScheduleReceiver.apply(this);
        refreshSchedule();
        if (announce) {
            Ui.toast(this, Prefs.scheduleEnabled(this)
                    ? "Her gün " + ScheduleReceiver.formatMinute(Prefs.scheduleMinute(this)) + " kapanacak"
                    : "Otomatik kapanma kapatıldı");
        }
    }

    private void refreshSchedule() {
        boolean on = Prefs.scheduleEnabled(this);
        int minute = Prefs.scheduleMinute(this);
        btnSchedule.setText(on ? "Açık" : "Kapalı");
        tvScheduleTime.setText(ScheduleReceiver.formatMinute(minute));
        tvScheduleTime.setTextColor(on ? 0xFF03DAC5 : 0xFF6B7380);
        if (on) {
            long next = ScheduleReceiver.nextOccurrence(minute, System.currentTimeMillis());
            tvScheduleInfo.setText("Sonraki: " + ScheduleReceiver.describe(next)
                    + "  (5 dk önce geri sayım başlar)");
        } else {
            tvScheduleInfo.setText("Kapalı — açmak için düğmeye basın");
        }
    }

    // ------------------------------------------------------------------ İzle (uygulama başlatıcı)

    private void loadApps() {
        appsRow.addView(tile("YouTube TV (web)", ContextCompat.getDrawable(this, android.R.drawable.ic_media_play),
                v -> openBrowser(Prefs.homeUrl(this))));
        appsRow.addView(tile("Web adresi", ContextCompat.getDrawable(this, android.R.drawable.ic_menu_search),
                v -> Ui.askUrl(this, Prefs.homeUrl(this), this::openBrowser)));

        new Thread(() -> {
            List<AppEntry> apps = queryApps();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                for (AppEntry app : apps) {
                    appsRow.addView(tile(app.label, app.icon, v -> launchApp(app.pkg)));
                }
            });
        }, "load-apps").start();
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !byPackage.containsKey(pkg)) byPackage.put(pkg, ri);
            }
        }

        List<AppEntry> apps = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : byPackage.entrySet()) {
            ResolveInfo ri = e.getValue();
            apps.add(new AppEntry(e.getKey(), String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        // Son açılan uygulama en başta
        String last = Prefs.lastApp(this);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).pkg.equals(last)) {
                apps.add(0, apps.remove(i));
                break;
            }
        }
        return apps.size() > MAX_APPS ? apps.subList(0, MAX_APPS) : apps;
    }

    private View tile(String label, Drawable icon, View.OnClickListener onClick) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackgroundResource(R.drawable.btn_tv);
        tile.setFocusable(true);
        tile.setClickable(true);
        int p = Ui.dp(this, 14);
        tile.setPadding(p, p, p, Ui.dp(this, 10));
        tile.setOnClickListener(onClick);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setDuplicateParentStateEnabled(true);
        int size = Ui.dp(this, 76);
        tile.addView(iv, new LinearLayout.LayoutParams(size, size));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(ContextCompat.getColorStateList(this, R.color.btn_tv_text));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setDuplicateParentStateEnabled(true);
        tv.setPadding(0, Ui.dp(this, 8), 0, 0);
        tile.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 150), LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = Ui.dp(this, 6);
        lp.setMargins(m, m, m, m);
        tile.setLayoutParams(lp);
        return tile;
    }

    private void openBrowser(String url) {
        startActivity(new Intent(this, BrowserActivity.class).putExtra(BrowserActivity.EXTRA_URL, url));
    }

    private void launchApp(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            Ui.toast(this, "Uygulama açılamadı");
            return;
        }
        Prefs.setLastApp(this, pkg);
        if (!TimerService.isRunning() && !Prefs.scheduleEnabled(this)) {
            Ui.toast(this, "Not: Zamanlayıcı kurulu değil");
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Ui.toast(this, "Uygulama açılamadı");
        }
    }
}
