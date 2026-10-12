package com.kapeks.turnofftimer;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;

/** Ekranlar arasında paylaşılan küçük yardımcılar. */
final class Ui {
    interface UrlCallback {
        void onUrl(String url);
    }

    private Ui() {}

    /** Kalan süreyi "1:05:09" veya "42:13" biçiminde yazar (saniye yukarı yuvarlanır). */
    static String format(long millis) {
        long total = Math.max(0, (millis + 999) / 1000);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    static int dp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, c.getResources().getDisplayMetrics()));
    }

    /** Durum ve gezinme çubuklarını gizler (gerçek tam ekran). */
    static void hideSystemBars(Activity a) {
        WindowCompat.setDecorFitsSystemWindows(a.getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    /** Kumandayla gezilebilen, odaklanınca belirginleşen küçük buton. */
    static TextView chip(Context c, String label, int backgroundRes) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(ContextCompat.getColorStateList(c, R.color.btn_tv_text));
        b.setBackgroundResource(backgroundRes);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setFocusable(true);
        b.setClickable(true);
        int h = dp(c, 12);
        int v = dp(c, 7);
        b.setPadding(h, v, h, v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(c, 3), 0, dp(c, 3), 0);
        b.setLayoutParams(lp);
        return b;
    }

    static void toast(Context c, String text) {
        Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
    }

    /** Adres sorar; "Ana sayfa yap" seçilirse adresi tarayıcının ana sayfası olarak kaydeder. */
    static void askUrl(Activity a, String initial, UrlCallback callback) {
        EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.setText(initial);
        et.setSelectAllOnFocus(true);

        FrameLayout box = new FrameLayout(a);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);
        box.addView(et);

        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Web adresi")
                .setView(box)
                .setPositiveButton("Aç", (d, w) -> callback.onUrl(normalizeUrl(et.getText().toString())))
                .setNeutralButton("Ana sayfa yap", (d, w) -> {
                    String url = normalizeUrl(et.getText().toString());
                    Prefs.setHomeUrl(a, url);
                    toast(a, "Ana sayfa kaydedildi");
                    callback.onUrl(url);
                })
                .setNegativeButton("Vazgeç", null)
                .create();
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                dialog.dismiss();
                callback.onUrl(normalizeUrl(et.getText().toString()));
                return true;
            }
            return false;
        });
        dialog.show();
    }

    static String normalizeUrl(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return Prefs.DEFAULT_URL;
        if (s.contains("://")) return s;
        if (!s.contains(".") || s.contains(" ")) return "https://www.google.com/search?q=" + Uri.encode(s);
        return "https://" + s;
    }

    /** Cihazın yerel ağdaki IPv4 adresi (ADB talimatlarında göstermek için). */
    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (!TextUtils.isEmpty(ip)) return ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
