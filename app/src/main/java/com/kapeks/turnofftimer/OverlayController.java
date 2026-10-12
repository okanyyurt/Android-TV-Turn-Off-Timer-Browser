package com.kapeks.turnofftimer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Diğer uygulamaların üzerinde (YouTube uygulaması, Netflix, HDMI vb.) karartma, kalan süre göstergesi,
 * 1 dk uyarısı ve uyku ekranı gösterir. "Diğer uygulamaların üstünde göster" izni yoksa sessizce hiçbir şey yapmaz.
 */
final class OverlayController {
    private static final String TAG = "OverlayController";

    interface Callbacks {
        void onExtend(long millis);
        void onFinishNow();
        void onCancel();
        void onDismissWarning();
        void onWake();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Callbacks callbacks;

    private View dimView;
    private TextView indicatorView;
    private WarningView warningView;
    private View blackoutView;

    OverlayController(Context context, Callbacks callbacks) {
        this.ctx = new ContextThemeWrapper(context, R.style.Theme_TurnOffTimer);
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.callbacks = callbacks;
    }

    static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(c);
    }

    // ------------------------------------------------------------------ Karartma

    void setDim(int alpha) {
        if (alpha <= 0) {
            dimView = remove(dimView);
            return;
        }
        if (dimView == null) {
            View v = new View(ctx);
            v.setBackgroundColor(Color.TRANSPARENT);
            if (!add(v, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP | Gravity.START, false))) return;
            dimView = v;
            // Gösterge, uyarı ve uyku ekranı karartmanın üstünde kalsın
            raise(indicatorView);
            raise(warningView);
            raise(blackoutView);
        }
        dimView.setBackgroundColor(Color.argb(Math.min(alpha, 255), 0, 0, 0));
    }

    // ------------------------------------------------------------------ Kalan süre göstergesi

    void setIndicator(boolean visible, String text) {
        if (!visible) {
            indicatorView = remove(indicatorView);
            return;
        }
        if (indicatorView == null) {
            TextView t = new TextView(ctx);
            t.setBackgroundResource(R.drawable.bg_pill);
            t.setTextColor(0xFF03DAC5);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 5), Ui.dp(ctx, 14), Ui.dp(ctx, 5));
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END, false);
            lp.x = Ui.dp(ctx, 24);
            lp.y = Ui.dp(ctx, 20);
            if (!add(t, lp)) return;
            indicatorView = t;
        }
        indicatorView.setText(text);
    }

    // ------------------------------------------------------------------ 1 dk uyarısı

    void setWarning(boolean visible, long remaining) {
        if (!visible) {
            warningView = remove(warningView);
            return;
        }
        if (warningView == null) {
            WarningView v = new WarningView(ctx, new WarningView.Listener() {
                @Override public void onExtend(long millis) { callbacks.onExtend(millis); }
                @Override public void onFinishNow() { callbacks.onFinishNow(); }
                @Override public void onCancel() { callbacks.onCancel(); }
                @Override public void onDismiss() { callbacks.onDismissWarning(); }
            });
            int vertical = Prefs.barTop(ctx) ? Gravity.TOP : Gravity.BOTTOM;
            WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, vertical | Gravity.CENTER_HORIZONTAL, true);
            lp.y = Ui.dp(ctx, 40);
            if (!add(v, lp)) return;
            warningView = v;
            v.post(v::focusDefault);
        }
        warningView.setRemaining(remaining);
    }

    // ------------------------------------------------------------------ Uyku ekranı

    void setBlackout(boolean visible) {
        if (!visible) {
            blackoutView = remove(blackoutView);
            return;
        }
        if (blackoutView != null) return;

        FrameLayout v = new FrameLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                // Bırakılan tuşta uyan: basma olayı alttaki uygulamaya yarım tıklama olarak gitmesin
                if (event.getAction() == KeyEvent.ACTION_UP) callbacks.onWake();
                return true;
            }
        };
        v.setBackgroundColor(Color.BLACK);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnClickListener(x -> callbacks.onWake());

        TextView hint = new TextView(ctx);
        hint.setText("Uyku modu  •  Uyandırmak için kumandada bir tuşa basın");
        hint.setTextColor(0xFF6B7380);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        v.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        hint.postDelayed(() -> hint.setVisibility(View.GONE), 4000);

        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START, true);
        lp.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
        lp.screenBrightness = 0f; // projektörde LED parlaklığını da en aza indirir
        if (!add(v, lp)) return;
        blackoutView = v;
        v.requestFocus();
    }

    void removeAll() {
        dimView = remove(dimView);
        indicatorView = remove(indicatorView);
        warningView = remove(warningView);
        blackoutView = remove(blackoutView);
    }

    // ------------------------------------------------------------------ Pencere yardımcıları

    private static WindowManager.LayoutParams params(int width, int height, int gravity, boolean focusable) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (focusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        return lp;
    }

    private boolean add(View v, WindowManager.LayoutParams lp) {
        if (!canDraw(ctx)) return false;
        try {
            wm.addView(v, lp);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Katman eklenemedi", e);
            return false;
        }
    }

    private <T extends View> T remove(T v) {
        if (v != null) {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Pencereyi kaldırıp yeniden ekleyerek en üste taşır. */
    private void raise(View v) {
        if (v == null) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            wm.removeViewImmediate(v);
            wm.addView(v, lp);
            if (v instanceof WarningView) v.post(((WarningView) v)::focusDefault);
            else if (v == blackoutView) v.requestFocus();
        } catch (Exception e) {
            Log.w(TAG, "Katman öne alınamadı", e);
        }
    }
}
