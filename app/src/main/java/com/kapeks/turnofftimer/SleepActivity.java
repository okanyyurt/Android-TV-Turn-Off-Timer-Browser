package com.kapeks.turnofftimer;

import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * "Diğer uygulamaların üstünde göster" izni yokken kullanılan siyah uyku ekranı.
 * Herhangi bir tuşa basılınca sesi geri getirip kapanır.
 */
public class SleepActivity extends AppCompatActivity implements TimerService.Listener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setOnClickListener(v -> wake());

        TextView hint = new TextView(this);
        hint.setText("Uyku modu  •  Uyandırmak için kumandada bir tuşa basın");
        hint.setTextColor(0xFF6B7380);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        root.addView(hint, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        hint.postDelayed(() -> hint.setVisibility(View.GONE), 4000);
        setContentView(root);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0f;
        getWindow().setAttributes(lp);
        Ui.hideSystemBars(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        TimerService.addListener(this);
        onTimerUpdate();
    }

    @Override
    protected void onStop() {
        TimerService.removeListener(this);
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) wake();
        return true;
    }

    @Override
    public void onTimerUpdate() {
        if (TimerService.getState() != TimerService.State.SLEEPING) finish();
    }

    private void wake() {
        TimerService.wake(this);
        finish();
    }
}
