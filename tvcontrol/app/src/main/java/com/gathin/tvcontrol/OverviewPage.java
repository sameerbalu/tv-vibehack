package com.gathin.tvcontrol;

import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Live RAM / storage / network, quick actions (stop background apps, clear cache), top apps today. */
final class OverviewPage extends Page {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean showing;

    private TextView ramBig, ramDetail, stoBig, stoDetail, netBig, netDetail, netLine, devBig, devDetail, result;
    private Ui.Bar ramBar, stoBar;
    private LinearLayout topApps;
    private long lastRx, lastTx, lastT;

    OverviewPage(MainActivity a) {
        super(a);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing) return;
            refresh();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    View build() {
        LinearLayout col = Ui.page(act, sv, "Overview", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);
        col.addView(permCard(), Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout r1 = Ui.hbox(act);
        r1.setBaselineAligned(false);
        LinearLayout ram = Ui.card(act);
        ram.addView(Ui.sectionLabel(act, "Memory"));
        ramBig = Ui.text(act, "–", 36, 0xFFFFFFFF, 300);
        ram.addView(ramBig, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 8));
        ramBar = new Ui.Bar(act);
        ram.addView(ramBar, Ui.lp(-1, Ui.dp(6)));
        ramDetail = Ui.text(act, "", 13, Ui.DIM, 400);
        ram.addView(ramDetail, Ui.margins(Ui.lp(-2, -2), 0, 10, 0, 0));
        r1.addView(ram, Ui.margins(Ui.lpWeight(1f), 0, 0, 14, 0));

        LinearLayout sto = Ui.card(act);
        sto.addView(Ui.sectionLabel(act, "Storage"));
        stoBig = Ui.text(act, "–", 36, 0xFFFFFFFF, 300);
        sto.addView(stoBig, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 8));
        stoBar = new Ui.Bar(act);
        sto.addView(stoBar, Ui.lp(-1, Ui.dp(6)));
        stoDetail = Ui.text(act, "", 13, Ui.DIM, 400);
        sto.addView(stoDetail, Ui.margins(Ui.lp(-2, -2), 0, 10, 0, 0));
        r1.addView(sto, Ui.lpWeight(1f));
        col.addView(r1, Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout r2 = Ui.hbox(act);
        r2.setBaselineAligned(false);
        LinearLayout net = Ui.card(act);
        net.addView(Ui.sectionLabel(act, "Network now"));
        netBig = Ui.text(act, "–", 30, 0xFFFFFFFF, 300);
        net.addView(netBig, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 6));
        netDetail = Ui.text(act, "", 13, Ui.DIM, 400);
        net.addView(netDetail);
        netLine = Ui.text(act, "", 13, Ui.DIM, 400);
        net.addView(netLine, Ui.margins(Ui.lp(-2, -2), 0, 4, 0, 0));
        r2.addView(net, Ui.margins(Ui.lpWeight(1f), 0, 0, 14, 0));

        LinearLayout dev = Ui.card(act);
        dev.addView(Ui.sectionLabel(act, "Device"));
        devBig = Ui.text(act, "–", 30, 0xFFFFFFFF, 300);
        dev.addView(devBig, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 6));
        devDetail = Ui.text(act, "", 13, Ui.DIM, 400);
        dev.addView(devDetail);
        r2.addView(dev, Ui.lpWeight(1f));
        col.addView(r2, Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));

        LinearLayout actions = Ui.hbox(act);
        actions.addView(Ui.button(act, "Boost: stop background apps", v -> boost()), Ui.lp(-2, Ui.dp(48)));
        actions.addView(Ui.button(act, "Clear cache", v -> clearCache()), Ui.margins(Ui.lp(-2, Ui.dp(48)), 12, 0, 0, 0));
        col.addView(actions, Ui.margins(Ui.lp(-1, -2), 0, 22, 0, 0));
        result = Ui.text(act, "", 14, Ui.GOOD, 400);
        col.addView(result, Ui.margins(Ui.lp(-1, -2), 4, 12, 0, 0));

        col.addView(Ui.sectionLabel(act, "Most used today"), Ui.margins(Ui.lp(-2, -2), 4, 26, 0, 10));
        topApps = Ui.vbox(act);
        col.addView(topApps);
        return sv[0];
    }

    @Override
    void onShow() {
        super.onShow();
        showing = true;
        lastT = 0;
        h.removeCallbacks(tick);
        tick.run();
        loadTop();
    }

    @Override
    void onHide() {
        showing = false;
        h.removeCallbacks(tick);
    }

    private void refresh() {
        long[] m = Sys.memory(act);
        long used = m[0] - m[1];
        float f = m[0] > 0 ? (float) used / m[0] : 0f;
        ramBig.setText(Math.round(f * 100) + "% used");
        ramBar.set(f, Ui.heat(f));
        ramDetail.setText(Ui.bytes(m[1]) + " free of " + Ui.bytes(m[0]));

        long[] s = Sys.storage();
        float sf = s[0] > 0 ? (float) (s[0] - s[1]) / s[0] : 0f;
        stoBig.setText(Ui.bytes(s[1]) + " free");
        stoBar.set(sf, Ui.heat(sf));
        stoDetail.setText(Ui.bytes(s[0] - s[1]) + " used of " + Ui.bytes(s[0]));

        long now = SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        if (lastT > 0 && now > lastT) {
            double dt = (now - lastT) / 1000.0;
            netBig.setText("↓ " + Ui.mbps(Math.max(0, rx - lastRx) / dt));
            netDetail.setText("↑ " + Ui.mbps(Math.max(0, tx - lastTx) / dt) + " · " + Sys.linkType(act));
        } else {
            netBig.setText("↓ …");
        }
        lastRx = rx;
        lastTx = tx;
        lastT = now;
        String w = Sys.linkType(act).equals("Wi-Fi") ? Sys.wifiDetail(act) : "";
        String ip = Sys.ipAddress();
        netLine.setText((w.isEmpty() ? "" : w + "\n") + (ip.isEmpty() ? "" : "IP " + ip));

        devBig.setText("Up " + Ui.duration(SystemClock.elapsedRealtime()));
        devDetail.setText("Since last restart\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
    }

    private void loadTop() {
        if (!Sys.hasUsageAccess(act)) {
            topApps.removeAllViews();
            return;
        }
        new Thread(() -> {
            Map<String, Sys.App> apps = Sys.loadApps(act);
            Sys.fillUsage(act, apps);
            List<Sys.App> l = new ArrayList<>();
            for (Sys.App a : apps.values()) if (a.launchable && a.fgToday > 60_000 && !a.pkg.equals(Sys.PKG)) l.add(a);
            Collections.sort(l, (x, y) -> Long.compare(y.fgToday, x.fgToday));
            final List<Sys.App> top = l.size() > 5 ? l.subList(0, 5) : l;
            final long max = top.isEmpty() ? 1 : top.get(0).fgToday;
            act.ui(() -> {
                topApps.removeAllViews();
                if (top.isEmpty()) {
                    topApps.addView(Ui.text(act, "Nothing yet today.", 14, Ui.DIM, 400));
                    return;
                }
                for (Sys.App a : top) {
                    Ui.AppRow r = new Ui.AppRow(act).bind(a, "Last used " + Ui.ago(a.lastUsed), Ui.duration(a.fgToday),
                            (float) a.fgToday / max, Ui.ACCENT);
                    r.setOnClickListener(v -> Sys.appInfo(act, a.pkg));
                    topApps.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 8));
                }
            });
        }).start();
    }

    private void boost() {
        result.setTextColor(Ui.DIM);
        result.setText("Stopping background apps…");
        new Thread(() -> {
            long before = Sys.memory(act)[1];
            Set<String> t = Sys.boostTargets(act);
            for (String p : t) Sys.kill(act, p);
            try {
                Thread.sleep(1800);
            } catch (InterruptedException ignored) {
            }
            long after = Sys.memory(act)[1];
            act.ui(() -> {
                result.setTextColor(Ui.GOOD);
                result.setText("Asked " + t.size() + " apps to stop · free RAM " + Ui.bytes(before) + " → " + Ui.bytes(after));
            });
        }).start();
    }

    private void clearCache() {
        result.setTextColor(Ui.DIM);
        result.setText("Clearing cache…");
        new Thread(() -> {
            long before = Sys.storage()[1];
            String err = null;
            try {
                Sys.clearAllCache(act);
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            long after = Sys.storage()[1];
            final String fe = err;
            act.ui(() -> {
                if (fe != null) {
                    result.setTextColor(Ui.WARN);
                    result.setText("Android refused: " + fe + ". Use the Storage tab for per-app clearing.");
                } else {
                    result.setTextColor(Ui.GOOD);
                    result.setText("Free space " + Ui.bytes(before) + " → " + Ui.bytes(after)
                            + (after - before > 0 ? " (" + Ui.bytes(after - before) + " freed)" : " (little cache above Android's limits)"));
                }
            });
        }).start();
    }
}
