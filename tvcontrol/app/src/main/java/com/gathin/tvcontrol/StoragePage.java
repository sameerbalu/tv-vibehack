package com.gathin.tvcontrol;

import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Free space, cache per app, and cache clearing. */
final class StoragePage extends Page {
    private TextView big, detail, result, cacheTotal;
    private Ui.Bar bar;
    private LinearLayout list;
    private int loadId;

    StoragePage(MainActivity a) {
        super(a);
    }

    @Override
    View build() {
        LinearLayout col = Ui.page(act, sv, "Storage", "What's using space, and how much cache each app is holding.");
        col.addView(permCard(), Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout card = Ui.card(act);
        card.addView(Ui.sectionLabel(act, "Internal storage"));
        big = Ui.text(act, "–", 36, 0xFFFFFFFF, 300);
        card.addView(big, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 8));
        bar = new Ui.Bar(act);
        card.addView(bar, Ui.lp(-1, Ui.dp(6)));
        detail = Ui.text(act, "", 13, Ui.DIM, 400);
        card.addView(detail, Ui.margins(Ui.lp(-2, -2), 0, 10, 0, 0));
        cacheTotal = Ui.text(act, "", 14, 0xFFFFFFFF, 500);
        card.addView(cacheTotal, Ui.margins(Ui.lp(-2, -2), 0, 8, 0, 0));
        col.addView(card, Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout actions = Ui.hbox(act);
        actions.addView(Ui.button(act, "Clear all cache", v -> clearAll()), Ui.lp(-2, Ui.dp(48)));
        actions.addView(Ui.button(act, "Storage settings", v -> Sys.openSettings(act, Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
                Ui.margins(Ui.lp(-2, Ui.dp(48)), 12, 0, 0, 0));
        actions.addView(Ui.button(act, "Refresh", v -> load()), Ui.margins(Ui.lp(-2, Ui.dp(48)), 12, 0, 0, 0));
        col.addView(actions, Ui.margins(Ui.lp(-1, -2), 0, 20, 0, 0));
        result = Ui.text(act, "", 14, Ui.GOOD, 400);
        col.addView(result, Ui.margins(Ui.lp(-1, -2), 4, 12, 0, 0));

        LinearLayout tip = Ui.card(act);
        tip.addView(Ui.text(act, "Want everything cleared at once?", 15, 0xFFFFFFFF, 600));
        tip.addView(Ui.text(act, "\"Clear all cache\" asks Android to free space and it only releases what it can without root. "
                        + "Pick an app below to open its App info (Storage > Clear cache). For a complete wipe, run from a PC / Termux:\n"
                        + "adb shell pm trim-caches 999G", 13, Ui.DIM, 400),
                Ui.margins(Ui.lp(-1, -2), 0, 6, 0, 0));
        col.addView(tip, Ui.margins(Ui.lp(-1, -2), 0, 16, 0, 0));

        col.addView(Ui.sectionLabel(act, "Apps by cache size"), Ui.margins(Ui.lp(-2, -2), 4, 26, 0, 10));
        list = Ui.vbox(act);
        col.addView(list);
        return sv[0];
    }

    @Override
    void onShow() {
        super.onShow();
        load();
    }

    private void showSpace() {
        long[] s = Sys.storage();
        float f = s[0] > 0 ? (float) (s[0] - s[1]) / s[0] : 0f;
        big.setText(Ui.bytes(s[1]) + " free");
        bar.set(f, Ui.heat(f));
        detail.setText(Ui.bytes(s[0] - s[1]) + " used of " + Ui.bytes(s[0]));
    }

    private void load() {
        showSpace();
        final int id = ++loadId;
        if (!Sys.hasUsageAccess(act)) {
            list.removeAllViews();
            cacheTotal.setText("Grant usage access to see cache per app.");
            return;
        }
        cacheTotal.setText("Measuring…");
        new Thread(() -> {
            Map<String, Sys.App> apps = Sys.loadApps(act);
            Sys.fillStorage(act, apps.values());
            List<Sys.App> l = new ArrayList<>();
            long total = 0;
            for (Sys.App a : apps.values()) {
                if (a.cache > 0) {
                    total += a.cache;
                    l.add(a);
                }
            }
            Collections.sort(l, (x, y) -> Long.compare(y.cache, x.cache));
            final long ftotal = total;
            act.ui(() -> {
                if (id != loadId) return;
                cacheTotal.setText("Cache held by apps: " + Ui.bytes(ftotal));
                list.removeAllViews();
                if (l.isEmpty()) {
                    list.addView(Ui.text(act, "No cache found.", 14, Ui.DIM, 400));
                    return;
                }
                long max = l.get(0).cache;
                int n = 0;
                for (Sys.App a : l) {
                    if (n++ >= 50) break;
                    Ui.AppRow r = new Ui.AppRow(act).bind(a,
                            "Data " + Ui.bytes(a.data) + " · App " + Ui.bytes(a.code),
                            Ui.bytes(a.cache), max > 0 ? (float) a.cache / max : 0f, Ui.WARN);
                    r.setOnClickListener(v -> Sys.appInfo(act, a.pkg));
                    list.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 8));
                }
            });
        }).start();
    }

    private void clearAll() {
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
                    result.setText("Android refused: " + fe);
                } else {
                    result.setTextColor(Ui.GOOD);
                    result.setText("Free space " + Ui.bytes(before) + " → " + Ui.bytes(after)
                            + (after - before > 0 ? " (" + Ui.bytes(after - before) + " freed)" : " (little cache above Android's limits)"));
                }
                load();
            });
        }).start();
    }
}
