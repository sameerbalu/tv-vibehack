package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.view.View;

import java.util.Calendar;

/**
 * The Minecraft clock: the time drawn from square "blocks" in a 5x7 pixel font. Hours are white and minutes gold,
 * every block has a light top-left and a dark bottom-right edge and the whole text has the game's dark drop shadow.
 */
public class PixelClockView extends View {

    private static final String[][] GLYPHS = {
            {"01110", "10001", "10011", "10101", "11001", "10001", "01110"},   // 0
            {"00100", "01100", "00100", "00100", "00100", "00100", "01110"},   // 1
            {"01110", "10001", "00001", "00010", "00100", "01000", "11111"},   // 2
            {"11110", "00001", "00001", "01110", "00001", "00001", "11110"},   // 3
            {"00010", "00110", "01010", "10010", "11111", "00010", "00010"},   // 4
            {"11111", "10000", "11110", "00001", "00001", "10001", "01110"},   // 5
            {"00110", "01000", "10000", "11110", "10001", "10001", "01110"},   // 6
            {"11111", "00001", "00010", "00100", "01000", "01000", "01000"},   // 7
            {"01110", "10001", "10001", "01110", "10001", "10001", "01110"},   // 8
            {"01110", "10001", "10001", "01111", "00001", "00010", "01100"},   // 9
    };
    private static final String[] COLON = {"0", "0", "1", "0", "1", "0", "0"};

    private final Paint paint = new Paint();
    private float cell;
    private int hourColor = 0xFFFFFFFF, minuteColor = 0xFFFFAA00;
    private String text = "";
    private boolean tickRegistered;

    public PixelClockView(Context c) {
        this(c, null);
    }

    public PixelClockView(Context c, AttributeSet a) {
        super(c, a);
        cell = 6f * c.getResources().getDisplayMetrics().density;
        update();
    }

    void setCellDp(float dp) {
        cell = dp * getResources().getDisplayMetrics().density;
        requestLayout();
        invalidate();
    }

    void setColors(int hours, int minutes) {
        hourColor = hours;
        minuteColor = minutes;
        invalidate();
    }

    private void update() {
        Calendar cal = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        int h = cal.get(h24 ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!h24 && h == 0) h = 12;
        int m = cal.get(Calendar.MINUTE);
        text = (h24 && h < 10 ? "0" + h : String.valueOf(h)) + ":" + (m < 10 ? "0" : "") + m;
    }

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String before = text;
            update();
            if (!before.equals(text)) {
                requestLayout();
                invalidate();
            }
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!tickRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(tick, f);
            tickRegistered = true;
        }
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (tickRegistered) {
            try {
                getContext().unregisterReceiver(tick);
            } catch (RuntimeException ignored) {
            }
            tickRegistered = false;
        }
        super.onDetachedFromWindow();
    }

    private int cols() {
        int n = 0;
        for (int i = 0; i < text.length(); i++) n += (text.charAt(i) == ':' ? 1 : 5) + 1;
        return Math.max(0, n - 1);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int sh = (int) Math.ceil(cell * 0.6f);
        setMeasuredDimension((int) Math.ceil(cols() * cell) + sh, (int) Math.ceil(7 * cell) + sh);
    }

    @Override
    protected void onDraw(Canvas c) {
        float shift = cell * 0.6f;
        for (int pass = 0; pass < 2; pass++) {
            float x0 = 0f;
            boolean minutes = false;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                String[] g = ch == ':' ? COLON : GLYPHS[ch - '0'];
                int gw = ch == ':' ? 1 : 5;
                if (ch == ':') minutes = true;
                int col = ch == ':' ? hourColor : minutes ? minuteColor : hourColor;
                for (int r = 0; r < 7; r++) for (int q = 0; q < gw; q++) {
                    if (g[r].charAt(q) != '1') continue;
                    float l = x0 + q * cell + (pass == 0 ? shift : 0f), t = r * cell + (pass == 0 ? shift : 0f);
                    if (pass == 0) {
                        paint.setColor(0xFF000000 | ((((col >> 16) & 255) / 4) << 16) | ((((col >> 8) & 255) / 4) << 8) | ((col & 255) / 4));
                        paint.setAlpha(190);
                        c.drawRect(l, t, l + cell, t + cell, paint);
                    } else {
                        paint.setColor(col);
                        c.drawRect(l, t, l + cell, t + cell, paint);
                        float e = cell * 0.16f;
                        paint.setColor(0x55FFFFFF);                                   // lit top and left edge
                        c.drawRect(l, t, l + cell, t + e, paint);
                        c.drawRect(l, t, l + e, t + cell, paint);
                        paint.setColor(0x44000000);                                   // dark bottom and right edge
                        c.drawRect(l, t + cell - e, l + cell, t + cell, paint);
                        c.drawRect(l + cell - e, t, l + cell, t + cell, paint);
                    }
                }
                x0 += (gw + 1) * cell;
            }
        }
    }
}
