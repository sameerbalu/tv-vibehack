package com.gathin.tvcontrol;

import android.net.TrafficStats;
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

/** Live speed plus Wi-Fi data used per app (today / 7 days / 30 days). */
final class NetworkPage extends Page {
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean showing;
    private TextView speed, link, boot, total, note;
    private LinearLayout list;
    private final TextView[] periodBtns = new TextView[3];
    private int period = 0;
    private int loadId;
    private long lastRx, lastTx, lastT;

    NetworkPage(MainActivity a) {
        super(a);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing) return;
            sample();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    View build() {
        LinearLayout col = Ui.page(act, sv, "Network", "Live speed and how much data each app used over Wi-Fi.");
        col.addView(permCard(), Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout live = Ui.card(act);
        live.addView(Ui.sectionLabel(act, "Live"));
        speed = Ui.text(act, "–", 34, 0xFFFFFFFF, 300);
        live.addView(speed, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 8));
        link = Ui.text(act, "", 14, Ui.DIM, 400);
        live.addView(link);
        boot = Ui.text(act, "", 13, Ui.FAINT, 400);
        live.addView(boot, Ui.margins(Ui.lp(-2, -2), 0, 6, 0, 0));
        col.addView(live, Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout pr = Ui.hbox(act);
        String[] names = {"Today", "7 days", "30 days"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView b = Ui.button(act, names[i], v -> {
                period = idx;
                markPeriod();
                load();
            });
            periodBtns[i] = b;
            pr.addView(b, Ui.margins(Ui.lp(-2, Ui.dp(44)), i == 0 ? 0 : 10, 0, 0, 0));
        }
        col.addView(pr, Ui.margins(Ui.lp(-1, -2), 0, 24, 0, 0));
        markPeriod();

        total = Ui.text(act, "", 15, 0xFFFFFFFF, 500);
        col.addView(total, Ui.margins(Ui.lp(-1, -2), 4, 18, 0, 0));
        note = Ui.text(act, "", 13, Ui.WARN, 400);
        col.addView(note, Ui.margins(Ui.lp(-1, -2), 4, 6, 0, 0));
        list = Ui.vbox(act);
        col.addView(list, Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));
        return sv[0];
    }

    private void markPeriod() {
        for (int i = 0; i < 3; i++) periodBtns[i].setSelected(i == period);
    }

    @Override
    void onShow() {
        super.onShow();
        showing = true;
        lastT = 0;
        h.removeCallbacks(tick);
        tick.run();
        load();
    }

    @Override
    void onHide() {
        showing = false;
        h.removeCallbacks(tick);
    }

    private void sample() {
        long now = SystemClock.elapsedRealtime();
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        if (lastT > 0 && now > lastT) {
            double dt = (now - lastT) / 1000.0;
            speed.setText("↓ " + Ui.mbps(Math.max(0, rx - lastRx) / dt) + "    ↑ " + Ui.mbps(Math.max(0, tx - lastTx) / dt));
        }
        lastRx = rx;
        lastTx = tx;
        lastT = now;
        String type = Sys.linkType(act);
        String w = type.equals("Wi-Fi") ? Sys.wifiDetail(act) : "";
        String ip = Sys.ipAddress();
        link.setText(type + (w.isEmpty() ? "" : " · " + w) + (ip.isEmpty() ? "" : " · IP " + ip));
        boot.setText("Since last restart, all networks: ↓ " + Ui.bytes(rx) + "  ↑ " + Ui.bytes(tx));
    }

    private void load() {
        final int id = ++loadId;
        if (!Sys.hasUsageAccess(act)) {
            list.removeAllViews();
            total.setText("");
            return;
        }
        total.setText("Loading…");
        final int p = period;
        new Thread(() -> {
            long end = System.currentTimeMillis();
            long start = p == 0 ? Sys.midnight() : end - (p == 1 ? 7L : 30L) * 24 * 3600_000L;
            Map<Integer, long[]> stats;
            try {
                stats = Sys.wifiByUid(act, start, end);
            } catch (Exception e) {
                act.ui(() -> {
                    if (id == loadId) total.setText("Android refused the data query (" + e.getClass().getSimpleName() + ").");
                });
                return;
            }
            Map<String, Sys.App> apps = Sys.loadApps(act);
            Map<Integer, Sys.App> byUid = new java.util.HashMap<>();
            for (Sys.App a : apps.values()) if (!byUid.containsKey(a.uid)) byUid.put(a.uid, a);
            List<Object[]> rows = new ArrayList<>();       // {App, rx, tx}
            long rxAll = 0, txAll = 0;
            for (Map.Entry<Integer, long[]> en : stats.entrySet()) {
                long rx = en.getValue()[0], tx = en.getValue()[1];
                if (rx + tx < 10 * 1024) continue;
                rxAll += rx;
                txAll += tx;
                Sys.App a = byUid.get(en.getKey());
                if (a == null) {
                    a = new Sys.App();
                    int uid = en.getKey();
                    a.label = uid == 1000 ? "Android system" : uid == -4 ? "Removed apps" : uid == -5 ? "Hotspot / tethering"
                            : uid == 0 ? "Root" : "System service (uid " + uid + ")";
                    a.pkg = "";
                }
                rows.add(new Object[]{a, rx, tx});
            }
            Collections.sort(rows, (x, y) -> Long.compare((Long) y[1] + (Long) y[2], (Long) x[1] + (Long) x[2]));
            final long frx = rxAll, ftx = txAll;
            final boolean ethernet = Sys.linkType(act).equals("Ethernet");
            act.ui(() -> {
                if (id != loadId) return;
                String[] label = {"today", "last 7 days", "last 30 days"};
                total.setText("Wi-Fi " + label[p] + ":  ↓ " + Ui.bytes(frx) + "   ↑ " + Ui.bytes(ftx) + "   (" + Ui.bytes(frx + ftx) + ")");
                note.setText(ethernet ? "You're on Ethernet. Android doesn't report per-app Ethernet data, so this only lists Wi-Fi use."
                        : "");
                list.removeAllViews();
                if (rows.isEmpty()) {
                    list.addView(Ui.text(act, "No Wi-Fi data recorded for this period.", 14, Ui.DIM, 400));
                    return;
                }
                long max = (Long) rows.get(0)[1] + (Long) rows.get(0)[2];
                int n = 0;
                for (Object[] o : rows) {
                    if (n++ >= 60) break;
                    Sys.App a = (Sys.App) o[0];
                    long rx = (Long) o[1], tx = (Long) o[2];
                    Ui.AppRow r = new Ui.AppRow(act).bind(a, "↓ " + Ui.bytes(rx) + "   ↑ " + Ui.bytes(tx),
                            Ui.bytes(rx + tx), max > 0 ? (float) (rx + tx) / max : 0f, Ui.ACCENT);
                    final String pkg = a.pkg;
                    r.setOnClickListener(v -> {
                        if (!pkg.isEmpty()) Sys.appInfo(act, pkg);
                    });
                    list.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 8));
                }
            });
        }).start();
    }
}
