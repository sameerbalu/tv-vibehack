package com.gathin.tvcontrol;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Recently used apps (what may still be running) with Stop / App info. */
final class AppsPage extends Page {
    private LinearLayout list;
    private TextView hint, modeBtn;
    private boolean showAll;
    private int loadId;

    AppsPage(MainActivity a) {
        super(a);
    }

    @Override
    View build() {
        LinearLayout col = Ui.page(act, sv, "Apps",
                "Apps used in the last 24 hours. Stopping an app frees its memory; Android restarts it when you open it again.");
        col.addView(permCard(), Ui.margins(Ui.lp(-1, -2), 0, 18, 0, 0));

        LinearLayout bar = Ui.hbox(act);
        bar.addView(Ui.button(act, "Stop all background apps", v -> stopAll()), Ui.lp(-2, Ui.dp(46)));
        modeBtn = Ui.button(act, "Showing: recent", v -> {
            showAll = !showAll;
            modeBtn.setText(showAll ? "Showing: all apps" : "Showing: recent");
            load();
        });
        bar.addView(modeBtn, Ui.margins(Ui.lp(-2, Ui.dp(46)), 12, 0, 0, 0));
        bar.addView(Ui.button(act, "Refresh", v -> load()), Ui.margins(Ui.lp(-2, Ui.dp(46)), 12, 0, 0, 0));
        col.addView(bar, Ui.margins(Ui.lp(-1, -2), 0, 20, 0, 0));

        hint = Ui.text(act, "", 14, Ui.GOOD, 400);
        col.addView(hint, Ui.margins(Ui.lp(-1, -2), 4, 14, 0, 0));
        list = Ui.vbox(act);
        col.addView(list, Ui.margins(Ui.lp(-1, -2), 0, 14, 0, 0));
        return sv[0];
    }

    @Override
    void onShow() {
        super.onShow();
        load();
    }

    private void load() {
        final int id = ++loadId;
        final boolean all = showAll || !Sys.hasUsageAccess(act);
        new Thread(() -> {
            Map<String, Sys.App> apps = Sys.loadApps(act);
            Sys.fillUsage(act, apps);
            Set<String> skip = Sys.homePackages(act.getPackageManager());
            skip.add(Sys.PKG);
            List<Sys.App> l = new ArrayList<>();
            for (Sys.App a : apps.values()) {
                if (!a.launchable || skip.contains(a.pkg)) continue;
                if (!all && a.lastUsed == 0) continue;
                l.add(a);
            }
            if (all) Collections.sort(l, (x, y) -> x.label.compareToIgnoreCase(y.label));
            else Collections.sort(l, (x, y) -> Long.compare(y.lastUsed, x.lastUsed));
            act.ui(() -> {
                if (id != loadId) return;
                list.removeAllViews();
                if (l.isEmpty()) {
                    list.addView(Ui.text(act, "No recently used apps found.", 14, Ui.DIM, 400));
                    return;
                }
                for (Sys.App a : l) {
                    String sub = a.lastUsed > 0
                            ? "Last used " + Ui.ago(a.lastUsed) + (a.fgToday > 60_000 ? " · " + Ui.duration(a.fgToday) + " today" : "")
                            : "Not used in the last 24 hours";
                    Ui.AppRow r = new Ui.AppRow(act).bind(a, sub, "", -1f, 0);
                    r.setOnClickListener(v -> options(a));
                    list.addView(r, Ui.margins(Ui.lp(-1, -2), 0, 0, 0, 8));
                }
            });
        }).start();
    }

    private void options(Sys.App a) {
        final String[] items = {"Stop app", "App info (force stop, clear cache, permissions)"};
        new AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Sys.kill(act, a.pkg);
                        hint.setText("Asked " + a.label + " to stop.");
                    } else {
                        Sys.appInfo(act, a.pkg);
                    }
                }).show();
    }

    private void stopAll() {
        Set<String> t = new HashSet<>(Sys.boostTargets(act));
        for (String p : t) Sys.kill(act, p);
        hint.setText("Asked " + t.size() + " apps to stop. Free RAM now: " + Ui.bytes(Sys.memory(act)[1])
                + " (may rise over the next few seconds).");
    }
}
