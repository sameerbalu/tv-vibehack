package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Discover" row above the app list: what to watch (trending films, opens Nuvio) and trending songs
 * (opens Spotify). Data is fetched in the background, cached on the TV and shown instantly next time.
 * If nothing could be loaded the row stays hidden and the app list looks like before.
 */
public class DiscoverStrip extends FrameLayout {

    interface Listener {
        void onUp();
        void onDown();
        void onOpen(Item item);
    }

    static final class Item {
        final boolean song;
        final String title, sub, imageUrl;

        Item(boolean song, String title, String sub, String imageUrl) {
            this.song = song;
            this.title = title;
            this.sub = sub;
            this.imageUrl = imageUrl;
        }
    }

    static final String TAG_CARD = "discover_card";
    private static final String PREFS = "launcher_preferences";
    private static final String KEY_JSON = "discover_json_v8";
    private static final String KEY_TS = "discover_ts_v8";
    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final int MOVIE_W_DP = 170, SONG_W_DP = 96, CARD_H_DP = 96;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final float d;
    private final HorizontalScrollView scroll;
    private final LinearLayout row;
    private final List<View> cards = new ArrayList<>();
    private Listener listener;
    private Runnable onContentChanged;
    private Typeface font;
    private String shownJson = "";
    private int generation;
    private boolean fetching;
    private View lastFocusedCard;
    private String tmdbKey = "";

    public DiscoverStrip(Context c, AttributeSet a) {
        super(c, a);
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        try {
            font = getResources().getFont(R.font.outfit);
        } catch (Exception e) {
            font = Typeface.DEFAULT;
        }
        try {
            tmdbKey = getResources().getString(R.string.tmdb_api_key).trim();
        } catch (Exception ignored) {
        }
        setClipChildren(false);
        scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean hasItems() {
        return !cards.isEmpty();
    }

    static boolean isCard(View v) {
        return v != null && TAG_CARD.equals(v.getTag());
    }

    /** Focus the card that had focus last (or the first one). */
    boolean focusRemembered() {
        if (cards.isEmpty()) return false;
        View target = lastFocusedCard != null && cards.contains(lastFocusedCard) ? lastFocusedCard : cards.get(0);
        return target.requestFocus();
    }

    // ---- loading -----------------------------------------------------------------------------------

    /** Shows the cached lists at once, and refreshes them from the internet when they are older than 30 minutes. */
    void load(boolean force) {
        String cached = prefs.getString(KEY_JSON, null);
        if (cached != null && !cached.equals(shownJson)) apply(cached);
        long age = System.currentTimeMillis() - prefs.getLong(KEY_TS, 0L);
        if (!fetching && (force || cached == null || age > TTL_MS || age < 0)) {
            fetching = true;
            final Context ctx = getContext();
            POOL.execute(() -> {
                String json = null;
                try {
                    json = fetchAll(ctx);
                } catch (Exception ignored) {
                }
                final String result = json;
                main.post(() -> {
                    fetching = false;
                    if (result == null) return;
                    prefs.edit().putString(KEY_JSON, result).putLong(KEY_TS, System.currentTimeMillis()).apply();
                    if (!result.equals(shownJson)) apply(result);
                });
            });
        }
    }

    private String fetchAll(Context ctx) throws Exception {
        JSONObject out = new JSONObject();
        JSONArray movies = new JSONArray();
        try {
            if (!tmdbKey.isEmpty()) {
                JSONObject j = new JSONObject(httpText("https://api.themoviedb.org/3/trending/all/day?api_key=" + tmdbKey));
                JSONArray res = j.getJSONArray("results");
                for (int i = 0; i < res.length() && movies.length() < 12; i++) {
                    JSONObject m = res.getJSONObject(i);
                    String title = m.optString("title", m.optString("name", ""));
                    String img = m.optString("backdrop_path", "");
                    if (img.isEmpty() || title.isEmpty()) continue;
                    String date = m.optString("release_date", m.optString("first_air_date", ""));
                    String year = date.length() >= 4 ? date.substring(0, 4) : "";
                    double rating = m.optDouble("vote_average", 0);
                    String sub = ("tv".equals(m.optString("media_type")) ? "Series" : "Film")
                            + (year.isEmpty() ? "" : "  ·  " + year)
                            + (rating > 0 ? String.format(Locale.US, "  ·  %.1f", rating) : "");
                    movies.put(new JSONObject().put("t", title).put("s", sub)
                            .put("i", "https://image.tmdb.org/t/p/w500" + img));
                }
            }
        } catch (Exception ignored) {
        }
        if (movies.length() == 0) {
            try {
                JSONObject j = new JSONObject(httpText("https://cinemeta-catalogs.strem.io/top/catalog/movie/top.json"));
                JSONArray metas = j.getJSONArray("metas");
                for (int i = 0; i < metas.length() && movies.length() < 12; i++) {
                    JSONObject m = metas.getJSONObject(i);
                    String title = m.optString("name", "");
                    String img = m.optString("background", "");
                    if (img.isEmpty()) img = m.optString("poster", "");
                    if (img.isEmpty() || title.isEmpty()) continue;
                    String year = m.optString("releaseInfo", m.optString("year", ""));
                    String rating = m.optString("imdbRating", "");
                    String genre = "";
                    JSONArray g = m.optJSONArray("genres");
                    if (g != null && g.length() > 0) genre = g.optString(0, "");
                    String sub = (genre.isEmpty() ? "Film" : genre)
                            + (year.isEmpty() ? "" : "  ·  " + year)
                            + (rating.isEmpty() ? "" : "  ·  " + rating);
                    movies.put(new JSONObject().put("t", title).put("s", sub).put("i", img));
                }
            } catch (Exception ignored) {
            }
        }
        JSONArray songs = new JSONArray();
        String cc = Locale.getDefault().getCountry().toLowerCase(Locale.US);
        if (cc.length() != 2) cc = "us";
        String[] regions = cc.equals("us") ? new String[]{"us"} : new String[]{cc, "us"};
        for (String region : regions) {
            try {
                JSONObject j = new JSONObject(httpText("https://rss.marketingtools.apple.com/api/v2/" + region
                        + "/music/most-played/12/songs.json"));
                JSONArray res = j.getJSONObject("feed").getJSONArray("results");
                for (int i = 0; i < res.length() && songs.length() < 12; i++) {
                    JSONObject m = res.getJSONObject(i);
                    String name = m.optString("name", "");
                    String img = m.optString("artworkUrl100", "").replace("100x100bb", "300x300bb");
                    if (name.isEmpty() || img.isEmpty()) continue;
                    songs.put(new JSONObject().put("t", name).put("s", m.optString("artistName", "")).put("i", img));
                }
            } catch (Exception ignored) {
            }
            if (songs.length() > 0) break;
        }
        if (movies.length() == 0 && songs.length() == 0) return null;
        out.put("movies", movies);
        out.put("songs", songs);
        return out.toString();
    }

    private static String httpText(String url) throws Exception {
        byte[] b = httpBytes(url, 2 * 1024 * 1024);
        return new String(b, "UTF-8");
    }

    private static byte[] httpBytes(String url, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "TVHome/8");
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > limit) throw new java.io.IOException("too large");
                }
                return bos.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- building the cards -------------------------------------------------------------------------

    private void apply(String json) {
        List<Item> movies = new ArrayList<>();
        List<Item> songs = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray m = o.optJSONArray("movies");
            JSONArray s = o.optJSONArray("songs");
            for (int i = 0; m != null && i < m.length(); i++) {
                JSONObject x = m.getJSONObject(i);
                movies.add(new Item(false, x.getString("t"), x.optString("s"), x.getString("i")));
            }
            for (int i = 0; s != null && i < s.length(); i++) {
                JSONObject x = s.getJSONObject(i);
                songs.add(new Item(true, x.getString("t"), x.optString("s"), x.getString("i")));
            }
        } catch (Exception e) {
            return;
        }
        if (movies.isEmpty() && songs.isEmpty()) return;
        shownJson = json;
        final int gen = ++generation;
        View focused = findFocus();
        int focusIndex = focused == null ? -1 : cards.indexOf(focused);
        row.removeAllViews();
        cards.clear();
        lastFocusedCard = null;
        if (!movies.isEmpty()) {
            row.addView(section("WHAT TO WATCH", "trending  ·  opens Nuvio", 0xFFA78BFA, movies, MOVIE_W_DP, gen), sectionLp(0));
        }
        if (!songs.isEmpty()) {
            row.addView(section("TRENDING SONGS", "play in Spotify", 0xFF1DB954, songs, SONG_W_DP, gen),
                    sectionLp(movies.isEmpty() ? 0 : 22));
        }
        if (focusIndex >= 0 && focusIndex < cards.size()) cards.get(focusIndex).requestFocus();
        if (onContentChanged != null) onContentChanged.run();
    }

    /** Called when the row got new content (the activity then re-checks whether to leave room for it). */
    void setOnContentChanged(Runnable r) {
        onContentChanged = r;
    }

    private LinearLayout.LayoutParams sectionLp(int startMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (startMarginDp * d));
        return lp;
    }

    private LinearLayout section(String title, String hint, int accent, List<Item> items, int widthDp, int gen) {
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        LinearLayout head = new LinearLayout(getContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(getContext());
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor(accent);
        dot.setBackground(dg);
        head.addView(dot, new LinearLayout.LayoutParams((int) (7 * d), (int) (7 * d)));
        TextView t = text(11, accent, true);
        t.setText(title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.setMarginStart((int) (8 * d));
        head.addView(t, tl);
        TextView h = text(11, 0x80FFFFFF, false);
        h.setText(hint);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.setMarginStart((int) (10 * d));
        head.addView(h, hl);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                (int) (16 * d));
        headLp.bottomMargin = (int) (6 * d);
        col.addView(head, headLp);

        LinearLayout cardsRow = new LinearLayout(getContext());
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        cardsRow.setClipChildren(false);
        for (Item item : items) {
            View card = buildCard(item, widthDp, gen);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (widthDp * d), (int) (CARD_H_DP * d));
            lp.setMarginEnd((int) (12 * d));
            cardsRow.addView(card, lp);
            cards.add(card);
        }
        col.addView(cardsRow);
        return col;
    }

    private TextView text(float sp, int color, boolean caps) {
        TextView t = new TextView(getContext());
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        if (caps) {
            t.setAllCaps(true);
            t.setLetterSpacing(0.18f);
        }
        return t;
    }

    private View buildCard(final Item item, int widthDp, final int gen) {
        final FrameLayout card = new FrameLayout(getContext());
        card.setTag(TAG_CARD);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(new ColorDrawable(0xFF1C1C1E));
        final float radius = 12 * d;
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);

        final ImageView img = new ImageView(getContext());
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(getContext());
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xE6000000}));
        LayoutParams sl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (58 * d), Gravity.BOTTOM);
        card.addView(scrim, sl);

        LinearLayout labels = new LinearLayout(getContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding((int) (9 * d), 0, (int) (9 * d), (int) (7 * d));
        TextView title = text(item.song ? 11 : 13, 0xFFFFFFFF, false);
        title.setTypeface(font, Typeface.BOLD);
        title.setText(item.title);
        TextView sub = text(item.song ? 10 : 11, 0xB3FFFFFF, false);
        sub.setText(item.sub);
        labels.addView(title);
        labels.addView(sub);
        card.addView(labels, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        // Focus ring on top of the picture.
        StateListDrawable ring = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x00000000);
        on.setStroke((int) (2.5f * d), 0xFFFFFFFF);
        on.setCornerRadius(radius);
        ring.addState(new int[]{android.R.attr.state_focused}, on);
        ring.addState(new int[]{}, new ColorDrawable(0x00000000));
        card.setForeground(ring);

        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.07f : 1f).scaleY(focused ? 1.07f : 1f).setDuration(180).start();
            if (focused) {
                lastFocusedCard = v;
            }
        });
        card.setOnClickListener(v -> {
            if (listener != null) listener.onOpen(item);
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int idx = cards.indexOf(v);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (listener != null) listener.onUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (listener != null) listener.onDown();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (idx <= 0) return true;
                    cards.get(idx - 1).requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (idx < 0 || idx >= cards.size() - 1) return true;
                    cards.get(idx + 1).requestFocus();
                    return true;
                default:
                    return false;
            }
        });

        loadImage(item.imageUrl, widthDp, gen, img);
        return card;
    }

    // ---- pictures ----------------------------------------------------------------------------------

    private void loadImage(final String url, final int widthDp, final int gen, final ImageView target) {
        final File dir = new File(getContext().getCacheDir(), "discover");
        final int reqW = (int) (widthDp * d * 1.6f);
        POOL.execute(() -> {
            Bitmap bmp = null;
            try {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
                if (!f.exists() || f.length() == 0) {
                    byte[] data = httpBytes(url, 4 * 1024 * 1024);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                }
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= reqW) sample *= 2;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            if (result == null) return;
            main.post(() -> {
                if (gen == generation) target.setImageBitmap(result);
            });
        });
    }
}
