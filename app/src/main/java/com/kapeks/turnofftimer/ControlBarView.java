package com.kapeks.turnofftimer;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Ekranın altında veya üstünde duran ince kontrol barı:
 * kalan süre, hızlı süreler, +15 dk, iptal, karartma / bitiş davranışı / bar konumu ve sayfaya özel butonlar.
 */
public class ControlBarView extends LinearLayout implements TimerService.Listener {
    private static final int[] PRESETS = {15, 30, 45, 60, 90, 120};

    private final TextView tvTime;
    private final LinearLayout row;
    private final LinearLayout extras;
    private final TextView firstPreset;
    private final TextView btnExtend;
    private final TextView btnDim;
    private final TextView btnEnd;
    private final TextView btnPosition;

    private Runnable onInteraction;
    private Runnable onPositionChanged;

    public ControlBarView(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundResource(R.drawable.bg_bar);
        setPadding(Ui.dp(c, 16), Ui.dp(c, 5), Ui.dp(c, 6), Ui.dp(c, 5));

        tvTime = new TextView(c);
        tvTime.setTextColor(0xFF03DAC5);
        tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvTime.setTypeface(Typeface.DEFAULT_BOLD);
        tvTime.setSingleLine(true);
        tvTime.setPadding(0, 0, Ui.dp(c, 10), 0);
        addView(tvTime);

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row);
        addView(scroll, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        TextView first = null;
        for (int minutes : PRESETS) {
            final long millis = minutes * 60_000L;
            final String label = minutes + " dk";
            TextView b = chip(row, label, () -> {
                TimerService.start(getContext(), millis);
                Ui.toast(getContext(), "Zamanlayıcı: " + label);
            });
            if (first == null) first = b;
        }
        firstPreset = first;
        btnExtend = chip(row, "+15 dk", () -> {
            TimerService.extend(getContext(), 15 * 60_000L);
            Ui.toast(getContext(), "+15 dk eklendi");
        });
        chip(row, "İptal", () -> {
            TimerService.stop(getContext());
            Ui.toast(getContext(), "Zamanlayıcı iptal edildi");
        });

        separator(row);
        btnDim = chip(row, "", () -> { Prefs.cycleDim(getContext()); refresh(); });
        btnEnd = chip(row, "", () -> { Prefs.cycleEndAction(getContext()); refresh(); });
        btnPosition = chip(row, "", () -> {
            Prefs.toggleBarTop(getContext());
            refresh();
            if (onPositionChanged != null) onPositionChanged.run();
        });

        separator(row);
        extras = new LinearLayout(c);
        extras.setOrientation(HORIZONTAL);
        extras.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(extras);

        refresh();
        onTimerUpdate();
    }

    /** Sayfaya özel buton ekler (Geri, Yenile, Çık...). */
    public void addExtra(String label, Runnable action) {
        chip(extras, label, action);
    }

    /** Barda bir tuşa basıldığında / odak değiştiğinde (otomatik gizleme süresini sıfırlamak için). */
    public void setOnInteraction(Runnable r) {
        onInteraction = r;
    }

    public void setOnPositionChanged(Runnable r) {
        onPositionChanged = r;
    }

    public void focusDefault() {
        (TimerService.isRunning() ? btnExtend : firstPreset).requestFocus();
    }

    public void refresh() {
        Context c = getContext();
        btnDim.setText("Karartma: " + Prefs.dimLabel(Prefs.dimMode(c)));
        btnEnd.setText("Bitince: " + Prefs.endActionShort(Prefs.endAction(c)));
        btnPosition.setText(Prefs.barTop(c) ? "Bar: Üst" : "Bar: Alt");
    }

    @Override
    public void onTimerUpdate() {
        switch (TimerService.getState()) {
            case RUNNING:
                tvTime.setText("Kalan " + Ui.format(TimerService.getRemaining()));
                break;
            case FINISHING:
            case SLEEPING:
                tvTime.setText("Süre doldu");
                break;
            default:
                tvTime.setText("Zamanlayıcı kapalı");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        TimerService.addListener(this);
        refresh();
        onTimerUpdate();
    }

    @Override
    protected void onDetachedFromWindow() {
        TimerService.removeListener(this);
        super.onDetachedFromWindow();
    }

    private TextView chip(LinearLayout parent, String label, Runnable action) {
        TextView b = Ui.chip(getContext(), label, R.drawable.btn_bar);
        b.setOnClickListener(v -> {
            action.run();
            interact();
        });
        b.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) interact();
        });
        parent.addView(b);
        return b;
    }

    private void separator(LinearLayout parent) {
        View v = new View(getContext());
        v.setBackgroundColor(0xFF2E3640);
        LayoutParams lp = new LayoutParams(Ui.dp(getContext(), 1), Ui.dp(getContext(), 22));
        lp.setMargins(Ui.dp(getContext(), 6), 0, Ui.dp(getContext(), 6), 0);
        parent.addView(v, lp);
    }

    private void interact() {
        if (onInteraction != null) onInteraction.run();
    }
}
