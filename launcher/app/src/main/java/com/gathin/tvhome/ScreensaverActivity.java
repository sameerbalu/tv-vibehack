package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/** Screen saver preview (Settings > Screen saver): the same aurora and clock as the real screensaver. Any key returns Home. */
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) finish();
        return true;
    }
}
