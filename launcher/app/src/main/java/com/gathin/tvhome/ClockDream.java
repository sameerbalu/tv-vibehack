package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: aurora colours drifting over a night sky, the thin clock, the date, how much of the day is gone
 * and the Spotify song that is playing. The clock block moves every minute so nothing burns in.
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private SaverController controller;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_clock);
        controller = new SaverController(this, findViewById(R.id.saverContent).getRootView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (controller != null) controller.start();
    }

    @Override
    public void onDreamingStopped() {
        if (controller != null) controller.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (controller != null) controller.stop();
        super.onDetachedFromWindow();
    }
}
