package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Screen saver (Settings > Screen saver, and started by the launcher after 2 minutes idle). Any key returns Home. */
public class ScreensaverActivity extends Activity {

    private SaverController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.dream_clock);
        controller = new SaverController(this, findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        controller.start();
    }

    @Override
    protected void onPause() {
        controller.stop();
        super.onPause();
    }

    private boolean leaving;

    /** Fades and drifts out instead of cutting back to the home screen. */
    private void leave() {
        if (leaving) return;
        leaving = true;
        findViewById(android.R.id.content).animate().alpha(0f).scaleX(1.05f).scaleY(1.05f).setDuration(320)
                .withEndAction(this::finish).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) leave();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) leave();
        return true;
    }
}
