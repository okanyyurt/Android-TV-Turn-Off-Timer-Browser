package com.kapeks.turnofftimer;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Dişli simgesinden açılan ayarlar: genel ayarlar, kısayol tuşu, kapatma izinleri, test ve ADB yardımı. */
public class SettingsActivity extends AppCompatActivity {
    private static final int COLOR_OK = 0xFF4CD787;
    private static final int COLOR_MISSING = 0xFFFFB454;

    private TextView tvOverlayStatus;
    private TextView tvAccessStatus;
    private TextView tvAdminStatus;
    private TextView tvRootStatus;
    private TextView tvChain;
    private TextView tvAdb;
    private TextView tvShortcutInfo;
    private Button btnDim;
    private Button btnEndAction;
    private Button btnIndicator;
    private Button btnBarPos;
    private Button btnFade;
    private Button btnWarn;
    private Button btnIdleDim;
    private Button btnShortcut;
    private boolean learningShortcut;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.hideSystemBars(this);

        tvOverlayStatus = findViewById(R.id.tvOverlayStatus);
        tvAccessStatus = findViewById(R.id.tvAccessStatus);
        tvAdminStatus = findViewById(R.id.tvAdminStatus);
        tvRootStatus = findViewById(R.id.tvRootStatus);
        tvChain = findViewById(R.id.tvChain);
        tvAdb = findViewById(R.id.tvAdb);
        tvShortcutInfo = findViewById(R.id.tvShortcutInfo);
        btnDim = findViewById(R.id.btnDim);
        btnEndAction = findViewById(R.id.btnEndAction);
        btnIndicator = findViewById(R.id.btnIndicator);
        btnBarPos = findViewById(R.id.btnBarPos);
        btnFade = findViewById(R.id.btnFade);
        btnWarn = findViewById(R.id.btnWarn);
        btnIdleDim = findViewById(R.id.btnIdleDim);
        btnShortcut = findViewById(R.id.btnShortcut);

        btnDim.setOnClickListener(v -> { Prefs.cycleDim(this); refresh(); });
        btnEndAction.setOnClickListener(v -> { Prefs.cycleEndAction(this); refresh(); });
        btnIndicator.setOnClickListener(v -> { Prefs.cycleIndicator(this); refresh(); });
        btnBarPos.setOnClickListener(v -> { Prefs.toggleBarTop(this); refresh(); });
        btnFade.setOnClickListener(v -> { Prefs.toggleFadeVolume(this); refresh(); });
        btnWarn.setOnClickListener(v -> { Prefs.toggleWarn(this); refresh(); });
        btnIdleDim.setOnClickListener(v -> { Prefs.toggleIdleDim(this); refresh(); });
        btnShortcut.setOnClickListener(v -> {
            learningShortcut = true;
            btnShortcut.setText("Şimdi kısayol tuşuna basın…");
        });

        findViewById(R.id.btnOverlay).setOnClickListener(v -> openOverlaySettings());
        findViewById(R.id.btnAccess).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btnAdmin).setOnClickListener(v -> openAdminSettings());
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            TimerService.start(this, 10_000L);
            Ui.toast(this, "Test: 10 saniye sonra kapatılacak");
        });

        btnDim.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.hideSystemBars(this);
        refresh();
    }

    /** Kısayol öğrenme modunda basılan ilk uygun tuşu kaydeder. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (learningShortcut) {
            int code = event.getKeyCode();
            boolean navigation = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
            if (code == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    learningShortcut = false;
                    refresh();
                }
                return true;
            }
            if (navigation) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    Ui.toast(this, "Yön ve OK tuşları kullanılamaz, başka bir tuş seçin");
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP) {
                learningShortcut = false;
                Prefs.setShortcutKey(this, code);
                Ui.toast(this, "Kısayol tuşu: " + Prefs.keyLabel(code));
                refresh();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void refresh() {
        String action = Prefs.endAction(this);
        btnDim.setText("Karartma: " + Prefs.dimLabel(Prefs.dimMode(this)));
        btnEndAction.setText("Bitince: " + Prefs.endActionLabel(action));
        btnIndicator.setText("Kalan süre: " + Prefs.indicatorLabel(Prefs.indicator(this)));
        btnBarPos.setText("Bar konumu: " + (Prefs.barTop(this) ? "Üst" : "Alt"));
        btnFade.setText("Sesi yavaşça kıs: " + onOff(Prefs.fadeVolume(this)));
        btnWarn.setText("1 dk önce uyar: " + onOff(Prefs.warn(this)));
        btnIdleDim.setText("Hareketsizlikte karart: " + onOff(Prefs.idleDim(this)));
        if (!learningShortcut) btnShortcut.setText("Kısayol tuşu: " + Prefs.keyLabel(Prefs.shortcutKey(this)));
        tvShortcutInfo.setText("Kısayol: başka bir uygulamadayken bu tuşa uzun veya iki kez basınca zamanlayıcı barı açılır"
                + (PowerAccessibilityService.getInstance() == null ? " (erişilebilirlik servisi açık olmalı)." : "."));

        boolean overlay = OverlayController.canDraw(this);
        boolean access = PowerAccessibilityService.getInstance() != null;
        boolean admin = PowerController.isAdminActive(this);
        boolean root = PowerController.hasRoot();
        setStatus(tvOverlayStatus, overlay, "Diğer uygulamaların üstünde göster",
                "karartma, kalan süre ve 1 dk uyarısı her uygulamada görünür");
        setStatus(tvAccessStatus, access, "Erişilebilirlik servisi",
                "kısayol tuşu, ekranı kapatma ve güç menüsünden Kapat");
        setStatus(tvAdminStatus, admin, "Cihaz yöneticisi", "süre dolunca ekranı kapatır (bekleme)");
        setStatus(tvRootStatus, root, "Root", root ? "bulundu" : "bulunamadı (gerekli değil)");
        tvChain.setText("Süre dolunca: " + PowerController.describeChain(this, action));
        tvAdb.setText(adbHelp());
    }

    private static String onOff(boolean value) {
        return value ? "Açık" : "Kapalı";
    }

    private static void setStatus(TextView tv, boolean ok, String title, String detail) {
        tv.setText((ok ? "✓  " : "✗  ") + title + "  —  " + detail);
        tv.setTextColor(ok ? COLOR_OK : COLOR_MISSING);
    }

    private String adbHelp() {
        String pkg = getPackageName();
        String ip = Ui.localIp();
        return "Ayar sayfası açılmıyor veya siyah ekran veriyorsa (bazı Çin ROM'larında bu sayfalar yoktur), "
                + "aynı ağdaki bir bilgisayardan ADB ile izin verebilirsiniz "
                + "(projektörde Geliştirici seçenekleri > USB/ağ hata ayıklama açık olmalı):\n\n"
                + "adb connect " + (ip != null ? ip : "PROJEKTOR_IP") + ":5555\n"
                + "adb shell appops set " + pkg + " SYSTEM_ALERT_WINDOW allow\n"
                + "adb shell dpm set-active-admin " + pkg + "/.MyDeviceAdminReceiver\n"
                + "adb shell settings put secure enabled_accessibility_services " + pkg + "/.PowerAccessibilityService\n"
                + "adb shell settings put secure accessibility_enabled 1\n\n"
                + "Not: Son iki komut, açık olan başka erişilebilirlik servislerinin yerine geçer.";
    }

    private void openOverlaySettings() {
        if (OverlayController.canDraw(this)) {
            Ui.toast(this, "Bu izin zaten verilmiş");
            return;
        }
        if (!tryStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))
                && !tryStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))) {
            showAdbHint();
        }
    }

    private void openAccessibilitySettings() {
        if (PowerAccessibilityService.getInstance() != null) {
            Ui.toast(this, "Erişilebilirlik servisi zaten açık");
            return;
        }
        if (!tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) showAdbHint();
    }

    private void openAdminSettings() {
        if (PowerController.isAdminActive(this)) {
            Ui.toast(this, "Cihaz yöneticisi zaten etkin");
            return;
        }
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, MyDeviceAdminReceiver.class))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Süre dolunca ekranı kapatabilmek için gereklidir.");
        if (!tryStart(i)) showAdbHint();
    }

    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showAdbHint() {
        Ui.toast(this, "Bu cihazda ayar sayfası yok. Sayfanın altındaki ADB komutlarını kullanın.");
        tvAdb.requestFocus();
    }
}
