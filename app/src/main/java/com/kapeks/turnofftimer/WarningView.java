package com.kapeks.turnofftimer;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Süre bitmeden 1 dk önce çıkan panel: ertele / şimdi kapat / iptal. Hem tarayıcıda hem diğer uygulamaların üstünde kullanılır. */
public class WarningView extends LinearLayout {
    public interface Listener {
        void onExtend(long millis);
        void onFinishNow();
        void onCancel();
        void onDismiss();
    }

    private final Listener listener;
    private final TextView tvTitle;
    private final TextView firstButton;

    public WarningView(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackgroundResource(R.drawable.bg_panel);
        int p = Ui.dp(c, 20);
        setPadding(p, Ui.dp(c, 16), p, Ui.dp(c, 16));

        tvTitle = new TextView(c);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.CENTER);
        addView(tvTitle);

        TextView sub = new TextView(c);
        sub.setText("Bitince: " + Prefs.endActionLabel(Prefs.endAction(c)) + "   •   Geri tuşu bu uyarıyı gizler");
        sub.setTextColor(0xFFAAB4C3);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 12));
        addView(sub);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        addView(row);

        firstButton = button(row, "+15 dk", () -> listener.onExtend(15 * 60_000L));
        button(row, "+30 dk", () -> listener.onExtend(30 * 60_000L));
        button(row, "Şimdi kapat", listener::onFinishNow);
        button(row, "Zamanlayıcıyı iptal et", listener::onCancel);

        setRemaining(TimerService.WARNING_MS);
    }

    private TextView button(LinearLayout row, String label, Runnable action) {
        TextView b = Ui.chip(getContext(), label, R.drawable.btn_tv);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        int h = Ui.dp(getContext(), 18);
        int v = Ui.dp(getContext(), 10);
        b.setPadding(h, v, h, v);
        b.setOnClickListener(x -> action.run());
        row.addView(b);
        return b;
    }

    public void setRemaining(long millis) {
        tvTitle.setText("Süre doluyor: " + Ui.format(millis));
    }

    public void focusDefault() {
        firstButton.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) listener.onDismiss();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
