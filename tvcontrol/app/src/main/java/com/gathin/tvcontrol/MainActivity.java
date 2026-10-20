package com.gathin.tvcontrol;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private FrameLayout content;
    private Page[] pages;
    private TextView[] tabs;
    private int current = -1;
    private boolean resumedOnce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout rail = Ui.vbox(this);
        rail.setPadding(Ui.dp(28), Ui.dp(34), Ui.dp(12), Ui.dp(24));
        root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(270), ViewGroup.LayoutParams.MATCH_PARENT));

        TextView brand = Ui.text(this, "TV Control", 26, 0xFFFFFFFF, 300);
        rail.addView(brand, Ui.margins(Ui.lp(-2, -2), 14, 0, 0, 2));
        TextView tag = Ui.text(this, "Manage your TV", 12, Ui.FAINT, 500);
        tag.setLetterSpacing(0.12f);
        rail.addView(tag, Ui.margins(Ui.lp(-2, -2), 14, 0, 0, 26));

        pages = new Page[]{
                new OverviewPage(this), new AppsPage(this), new NetworkPage(this),
                new StoragePage(this), new ToolsPage(this)};
        String[] names = {"Overview", "Apps", "Network", "Storage", "Tools"};
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Ui.text(this, names[i], 18, 0xFFFFFFFF, 500);
            t.setTextColor(Ui.states(0xFF111111, 0xFFFFFFFF, 0x99FFFFFF));
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(Ui.dp(20), 0, Ui.dp(16), 0);
            t.setBackground(Ui.pillBg(14));
            t.setFocusable(true);
            t.setClickable(true);
            t.setOnClickListener(v -> content.requestFocus());
            t.setOnFocusChangeListener((v, f) -> {
                if (f) select(idx);
            });
            tabs[i] = t;
            rail.addView(t, Ui.margins(Ui.lp(-1, Ui.dp(50)), 0, 0, 0, 8));
        }

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        setContentView(root);
        select(0);
        tabs[0].requestFocus();
    }

    void select(int i) {
        if (i == current) return;
        if (current >= 0) pages[current].onHide();
        current = i;
        content.removeAllViews();
        content.addView(pages[i].view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int k = 0; k < tabs.length; k++) tabs[k].setSelected(k == i);
        pages[i].onShow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!resumedOnce) {
            resumedOnce = true;
            return;
        }
        if (current >= 0) pages[current].onShow();      // e.g. back from the usage-access screen
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current >= 0) pages[current].onHide();
    }

    private boolean inContent(View v) {
        while (v != null) {
            if (v == content) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            View f = getCurrentFocus();
            if (f != null && inContent(f)) {
                View n = f.focusSearch(View.FOCUS_LEFT);
                if (n == null || !inContent(n)) {       // left edge of the page: go back to the tab rail
                    tabs[current].requestFocus();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        View f = getCurrentFocus();
        if (f != null && inContent(f)) tabs[current].requestFocus();
        else super.onBackPressed();
    }

    /** Runs on the UI thread unless the screen is gone. */
    void ui(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }
}
