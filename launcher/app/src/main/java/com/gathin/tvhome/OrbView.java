package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** The assistant's animated orb: breathes when idle, swells with your voice, spins while thinking, pulses while speaking. */
final class OrbView extends View {
    static final int IDLE = 0, LISTENING = 1, THINKING = 2, SPEAKING = 3;

    private static final int[] COLORS = {0xFF10A37F, 0xFF7C5CFF, 0xFF4DA3FF};

    private int state = IDLE;
    private float level;            // microphone level 0..1 (smoothed)
    private float levelTarget;
    private float amp = 0.05f, ampTarget = 0.05f;
    private float speed = 0.6f, speedTarget = 0.6f;
    private float ring;             // 0..1 visibility of the "thinking" ring
    private float phase;            // animation clock (radians)
    private long last;
    private ValueAnimator clock;

    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    OrbView(Context c) {
        super(c);
        blob.setStyle(Paint.Style.FILL);
        blob.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFFFFFFFF);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setState(int s) {
        state = s;
        switch (s) {
            case LISTENING:
                ampTarget = 0.10f;
                speedTarget = 1.6f;
                break;
            case THINKING:
                ampTarget = 0.07f;
                speedTarget = 3.2f;
                break;
            case SPEAKING:
                ampTarget = 0.16f;
                speedTarget = 2.2f;
                break;
            default:
                ampTarget = 0.05f;
                speedTarget = 0.6f;
        }
    }

    void setLevel(float l) {
        levelTarget = Math.max(0f, Math.min(1f, l));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        last = System.nanoTime();
        clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(1000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new LinearInterpolator());
        clock.addUpdateListener(a -> {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - last) / 1e9f);
            last = now;
            phase += dt * speed;
            level += (levelTarget - level) * 0.25f;
            levelTarget *= 0.92f;
            amp += (ampTarget - amp) * 0.08f;
            speed += (speedTarget - speed) * 0.06f;
            ring += ((state == THINKING ? 1f : 0f) - ring) * 0.08f;
            invalidate();
        });
        clock.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (clock != null) clock.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) / 2f * 0.50f;
        float voice = state == LISTENING ? level : state == SPEAKING ? 0.5f + 0.5f * (float) Math.sin(phase * 3f) : 0f;
        float r = base * (1f + 0.22f * voice);

        // soft glow
        float gr = base * (2.0f + 0.5f * voice);
        glow.setShader(new RadialGradient(cx, cy, gr, new int[]{0x6610A37F, 0x227C5CFF, 0x00000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, gr, glow);

        // three wobbling blobs blended with "screen"
        for (int i = 0; i < 3; i++) {
            float a = amp + 0.30f * voice * (i == 1 ? 1f : 0.7f);
            float ph = phase * (1f + i * 0.35f) + i * 2.1f;
            int k = 3 + i;
            path.reset();
            final int n = 72;
            for (int j = 0; j <= n; j++) {
                double th = j * Math.PI * 2 / n;
                float rr = r * (1f + a * (float) Math.sin(k * th + ph) + a * 0.5f * (float) Math.sin((k + 2) * th - ph * 1.3f));
                float x = cx + rr * (float) Math.cos(th), y = cy + rr * (float) Math.sin(th);
                if (j == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            blob.setColor(COLORS[i]);
            blob.setAlpha(150);
            c.drawPath(path, blob);
        }

        // bright core
        core.setShader(new RadialGradient(cx, cy, r * 0.8f, new int[]{0xAAFFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r * 0.8f, core);

        // spinning arcs while thinking
        if (ring > 0.02f) {
            float rr = r * 1.45f;
            arc.setStrokeWidth(Math.max(2f, base * 0.06f));
            oval.set(cx - rr, cy - rr, cx + rr, cy + rr);
            float rot = (float) Math.toDegrees(phase * 1.4f);
            arc.setAlpha((int) (200 * ring));
            c.drawArc(oval, rot, 70f, false, arc);
            arc.setAlpha((int) (110 * ring));
            c.drawArc(oval, rot + 180f, 40f, false, arc);
        }
    }
}
