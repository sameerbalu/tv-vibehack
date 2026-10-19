package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Support for the common icon pack format (ADW / Nova / Go): the pack's appfilter.xml maps an app's
 * component to a drawable inside the pack.
 */
final class IconPack {

    static final class Info {
        final String pkg;
        final String label;
        final Drawable icon;

        Info(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    final String pkg;
    private final Resources res;
    private final Map<String, String> byComponent = new HashMap<>();
    private final Map<String, String> byPackage = new HashMap<>();

    /** Icon packs installed on this device. */
    static List<Info> installed(PackageManager pm) {
        String[] actions = {
                "org.adw.launcher.THEMES",
                "com.gau.go.launcherex.theme",
                "com.novalauncher.THEME",
                "com.anddoes.launcher.THEME"
        };
        Map<String, Info> found = new LinkedHashMap<>();
        for (String action : actions) {
            try {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(action), 0)) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (!found.containsKey(pkg)) {
                        found.put(pkg, new Info(pkg, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm)));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        List<Info> list = new ArrayList<>(found.values());
        Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
    }

    static IconPack load(Context ctx, String pkg) {
        try {
            return new IconPack(ctx.getPackageManager(), pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private IconPack(PackageManager pm, String pkg) throws Exception {
        this.pkg = pkg;
        this.res = pm.getResourcesForApplication(pkg);
        int id = res.getIdentifier("appfilter", "xml", pkg);
        if (id != 0) {
            read(res.getXml(id));
        } else {
            try (InputStream in = res.getAssets().open("appfilter.xml")) {
                XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                parser.setInput(in, "utf-8");
                read(parser);
            }
        }
    }

    private void read(XmlPullParser p) throws Exception {
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "item".equals(p.getName())) {
                String component = p.getAttributeValue(null, "component");
                String drawable = p.getAttributeValue(null, "drawable");
                if (component != null && drawable != null) register(component.trim(), drawable);
            }
            event = p.next();
        }
    }

    private void register(String component, String drawable) {
        if (component.startsWith("ComponentInfo{") && component.endsWith("}")) {
            component = component.substring("ComponentInfo{".length(), component.length() - 1);
        }
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) return;
        if (!byComponent.containsKey(cn.flattenToShortString())) byComponent.put(cn.flattenToShortString(), drawable);
        if (!byPackage.containsKey(cn.getPackageName())) byPackage.put(cn.getPackageName(), drawable);
    }

    /** The pack's icon for this app, or null if the pack does not cover it. */
    Drawable iconFor(ComponentName cn) {
        String name = byComponent.get(cn.flattenToShortString());
        if (name == null) name = byPackage.get(cn.getPackageName());
        if (name == null) return null;
        int id = res.getIdentifier(name, "drawable", pkg);
        if (id == 0) id = res.getIdentifier(name, "mipmap", pkg);
        if (id == 0) return null;
        try {
            return res.getDrawable(id, null);
        } catch (Exception e) {
            return null;
        }
    }
}
