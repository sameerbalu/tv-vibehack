package com.gathin.tvhome;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Voice assistant: speak, it asks an AI service and answers on an animated panel (and out loud).
 * Works with a FREE Google Gemini key (AIza...), a free Groq key (gsk_...) or an OpenAI key (sk-...): the
 * service is picked from the key. Opens from the mic button on the home screen or the remote's assistant button.
 * Set the key once: adb shell am start -n com.gathin.tvhome/.AssistantActivity --es api_key YOUR_KEY
 */
public class AssistantActivity extends Activity implements TextToSpeech.OnInitListener {

    private static final String PREFS = "assistant_preferences";
    /** An AI service that speaks the OpenAI chat format. */
    private static final class Provider {
        final String name, base, model;
        final boolean openai;

        Provider(String name, String base, String model, boolean openai) {
            this.name = name;
            this.base = base;
            this.model = model;
            this.openai = openai;
        }
    }

    private static Provider providerFor(String key) {
        if (key.startsWith("gsk_")) {
            return new Provider("Groq", "https://api.groq.com/openai/v1/", "llama-3.3-70b-versatile", false);
        }
        if (key.startsWith("sk-")) {
            return new Provider("ChatGPT", "https://api.openai.com/v1/", "gpt-5-mini", true);
        }
        // Google keys: "AIza..." (classic) and "AQ...." (newer). Anything unrecognised is tried as Gemini (free).
        return new Provider("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-flash-latest", false);
    }
    private static final int REQUEST_MIC = 81;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float d;

    private View scrim;
    private LinearLayout panel;
    private OrbView orb;
    private TextView status, question, answer;
    private ScrollView answerScroll;
    private View[] buttons;

    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean closing;
    private boolean askingPermission;
    private int requestId;                       // ignores answers to questions that were replaced
    private ValueAnimator reveal;
    private final List<String[]> history = new ArrayList<>();   // {role, text}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;
        saveExtras(getIntent());
        buildUi();
        tts = new TextToSpeech(this, this);
        animateIn();
        handler.postDelayed(this::begin, 420);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        saveExtras(intent);
        startListening();
    }

    private void saveExtras(Intent i) {
        if (i == null) return;
        SharedPreferences.Editor e = prefs.edit();
        if (i.hasExtra("api_key")) e.putString("api_key", String.valueOf(i.getStringExtra("api_key")).trim());
        if (i.hasExtra("model")) e.putString("model", String.valueOf(i.getStringExtra("model")).trim());
        e.apply();
    }

    private String key() {
        return prefs.getString("api_key", "").trim();
    }

    /** The model to use: the one you set, else one found earlier for this service, else the service's default. */
    private String model() {
        String m = prefs.getString("model", "").trim();
        if (!m.isEmpty()) return m;
        String auto = prefs.getString("auto_model_" + providerFor(key()).name, "").trim();
        return auto.isEmpty() ? providerFor(key()).model : auto;
    }

    @Override
    public void onInit(int s) {
        if (s != TextToSpeech.SUCCESS) return;
        try {
            int r = tts.setLanguage(Locale.getDefault());
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) {
                    runOnUiThread(() -> orb.setState(OrbView.SPEAKING));
                }

                @Override
                public void onDone(String id) {
                    runOnUiThread(() -> afterSpeaking());
                }

                @Override
                public void onError(String id) {
                    runOnUiThread(() -> afterSpeaking());
                }
            });
            ttsReady = true;
        } catch (RuntimeException ignored) {
        }
    }

    // ---- UI -------------------------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * d);
    }

    private TextView text(String s, float sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        try {
            t.setTypeface(getResources().getFont(R.font.outfit));
            t.setFontVariationSettings("'wght' " + weight);
        } catch (Exception e) {
            t.setTypeface(Typeface.DEFAULT);
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView pill(String label, View.OnClickListener l) {
        TextView t = text(label, 15, 0xFFFFFFFF, 500);
        t.setTextColor(getResources().getColorStateList(R.drawable.pill_text, getTheme()));
        t.setBackgroundResource(R.drawable.pill_bg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(22), 0, dp(22), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(l);
        t.setOnFocusChangeListener((v, f) -> v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start());
        return t;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0xAA000000);
        scrim.setAlpha(0f);
        scrim.setOnClickListener(v -> closeAnimated());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(36), dp(22), dp(36), dp(28));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEB0F1217);
        bg.setCornerRadius(dp(34));
        bg.setStroke(Math.max(1, dp(1)), 0x33FFFFFF);
        panel.setBackground(bg);
        panel.setElevation(dp(24));
        int w = Math.min(dp(940), (int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = dp(44);
        root.addView(panel, plp);

        orb = new OrbView(this);
        panel.addView(orb, new LinearLayout.LayoutParams(dp(200), dp(150)));

        status = text("", 13, 0x99FFFFFF, 500);
        status.setLetterSpacing(0.16f);
        status.setAllCaps(true);
        panel.addView(status, margin(new LinearLayout.LayoutParams(-2, -2), 0, 2, 0, 0));

        question = text("", 18, 0x99FFFFFF, 400);
        question.setGravity(Gravity.CENTER);
        question.setMaxLines(2);
        panel.addView(question, margin(new LinearLayout.LayoutParams(-1, -2), 0, 12, 0, 0));

        answer = text("", 26, 0xFFFFFFFF, 300);
        answer.setGravity(Gravity.CENTER);
        answer.setLineSpacing(0, 1.12f);
        answerScroll = new ScrollView(this);
        answerScroll.setVerticalScrollBarEnabled(false);
        answerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        answerScroll.addView(answer, new FrameLayout.LayoutParams(-1, -2));
        panel.addView(answerScroll, margin(new LinearLayout.LayoutParams(-1, dp(170)), 0, 10, 0, 0));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView mic = pill("Ask", v -> startListening());
        TextView type = pill("Type", v -> promptType());
        TextView keyBtn = pill("API key", v -> promptKey());
        TextView close = pill("Close", v -> closeAnimated());
        buttons = new View[]{mic, type, keyBtn, close};
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
            lp.setMargins(i == 0 ? 0 : dp(10), 0, 0, 0);
            row.addView(buttons[i], lp);
        }
        panel.addView(row, margin(new LinearLayout.LayoutParams(-2, -2), 0, 14, 0, 0));
        setContentView(root);
        mic.requestFocus();
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    // ---- animations -----------------------------------------------------------------------------

    private void animateIn() {
        scrim.animate().alpha(1f).setDuration(320).start();
        panel.setAlpha(0f);
        panel.setTranslationY(dp(140));
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(520)
                .setInterpolator(new OvershootInterpolator(0.9f)).start();
        orb.setScaleX(0.3f);
        orb.setScaleY(0.3f);
        orb.setAlpha(0f);
        orb.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(160).setDuration(620)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
        for (int i = 0; i < buttons.length; i++) {
            View b = buttons[i];
            b.setAlpha(0f);
            b.setTranslationY(dp(14));
            b.animate().alpha(1f).translationY(0f).setStartDelay(340 + i * 60L).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }
    }

    private void closeAnimated() {
        if (closing) return;
        closing = true;
        handler.removeCallbacksAndMessages(null);
        stopVoice();
        scrim.animate().alpha(0f).setDuration(260).start();
        panel.animate().alpha(0f).translationY(dp(110)).scaleX(0.96f).scaleY(0.96f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        finish();
                        overridePendingTransition(0, 0);
                    }
                }).start();
    }

    /** Types the answer out, a little faster for long answers. */
    private void revealAnswer(final String full) {
        if (reveal != null) reveal.cancel();
        answer.setText("");
        answerScroll.scrollTo(0, 0);
        reveal = ValueAnimator.ofInt(0, full.length());
        reveal.setDuration(Math.max(500, Math.min(5000, full.length() * 30L)));
        reveal.addUpdateListener(a -> {
            int n = (Integer) a.getAnimatedValue();
            answer.setText(full.substring(0, Math.min(n, full.length())));
            answerScroll.post(() -> answerScroll.scrollTo(0, Math.max(0, answer.getHeight() - answerScroll.getHeight())));
        });
        reveal.start();
    }

    // ---- flow -----------------------------------------------------------------------------------

    private void begin() {
        if (closing) return;
        if (key().isEmpty()) showSetup();
        else startListening();
    }

    private void showSetup() {
        orb.setState(OrbView.IDLE);
        status.setText("Set up the assistant");
        question.setText("");
        answer.setTextColor(0xFFFFFFFF);
        revealAnswer("Add a free API key once and I'm ready. Get one at aistudio.google.com/apikey (Google account, no card), then choose \"API key\" below.");
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void stopVoice() {
        requestId++;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (RuntimeException ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void startListening() {
        if (closing) return;
        stopVoice();
        if (key().isEmpty()) {
            showSetup();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            orb.setState(OrbView.IDLE);
            setStatus("Voice input not available");
            promptType();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askingPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(listener);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        question.setText("");
        orb.setState(OrbView.LISTENING);
        setStatus("Listening…");
        try {
            recognizer.startListening(i);
        } catch (RuntimeException e) {
            orb.setState(OrbView.IDLE);
            setStatus("Voice input failed");
            promptType();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        askingPermission = false;
        if (code != REQUEST_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startListening();
        } else {
            setStatus("Microphone not allowed");
            promptType();
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle p) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rms) {
            orb.setLevel((rms + 2f) / 12f);
        }

        @Override
        public void onBufferReceived(byte[] b) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int code) {
            orb.setState(OrbView.IDLE);
            if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                setStatus("Didn't catch that · press Ask");
            } else if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setStatus("Microphone not allowed");
            } else if (code == SpeechRecognizer.ERROR_NETWORK || code == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
                setStatus("Voice needs the internet");
            } else if (code != SpeechRecognizer.ERROR_CLIENT) {
                setStatus("Voice error " + code + " · press Ask or Type");
            }
        }

        @Override
        public void onResults(Bundle r) {
            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty() && !l.get(0).trim().isEmpty()) ask(l.get(0).trim());
            else onError(SpeechRecognizer.ERROR_NO_MATCH);
        }

        @Override
        public void onPartialResults(Bundle r) {
            ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty()) question.setText(l.get(0));
        }

        @Override
        public void onEvent(int t, Bundle b) {
        }
    };

    private void promptType() {
        if (closing) return;
        final EditText input = new EditText(this);
        input.setHint("Ask anything…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Ask the assistant")
                .setView(input)
                .setPositiveButton("Send", (dlg, w) -> {
                    String q = input.getText().toString().trim();
                    if (!q.isEmpty()) ask(q);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptKey() {
        final EditText input = new EditText(this);
        input.setHint("Paste key: Gemini (free) · gsk_… Groq (free) · sk-… OpenAI");
        input.setSingleLine(true);
        input.setText(key());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("API key · using " + providerFor(key()).name + " (" + model() + ")")
                .setView(input)
                .setPositiveButton("Save", (dlg, w) -> {
                    prefs.edit().putString("api_key", input.getText().toString().trim()).apply();
                    startListening();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- asking ChatGPT -------------------------------------------------------------------------

    private void ask(final String q) {
        if (closing) return;
        if (key().isEmpty()) {
            showSetup();
            return;
        }
        final int id = ++requestId;
        question.setText("“" + q + "”");
        answer.setText("");
        answer.setTextColor(0xFFFFFFFF);
        orb.setState(OrbView.THINKING);
        setStatus("Thinking…");
        final String k = key(), m = model();
        final Provider prov = providerFor(k);
        final List<String[]> past = new ArrayList<>(history);
        new Thread(() -> {
            String out;
            boolean ok = true;
            try {
                out = chat(this, prov, k, m, past, q);
            } catch (Exception e) {
                ok = false;
                out = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String text = out;
            final boolean fok = ok;
            runOnUiThread(() -> {
                if (closing || id != requestId) return;
                if (!fok) {
                    orb.setState(OrbView.IDLE);
                    setStatus(prov.name + " error");
                    answer.setTextColor(0xFFFF8A80);
                    revealAnswer(text);
                    return;
                }
                history.add(new String[]{"user", q});
                history.add(new String[]{"assistant", text});
                while (history.size() > 8) history.remove(0);
                setStatus(prov.name);
                revealAnswer(text);
                speak(text);
            });
        }).start();
    }

    private void speak(String text) {
        if (ttsReady) {
            try {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ans");
                orb.setState(OrbView.SPEAKING);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        orb.setState(OrbView.SPEAKING);
        handler.postDelayed(this::afterSpeaking, Math.max(1500, Math.min(5200, text.length() * 32L)));
    }

    private void afterSpeaking() {
        if (closing) return;
        orb.setState(OrbView.IDLE);
        setStatus("Press Ask to continue");
    }

    private static final class Reply {
        int code;
        String raw;
    }

    private static Reply post(String url, String key, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + key);
            if (url.contains("generativelanguage.googleapis.com")) c.setRequestProperty("x-goog-api-key", key);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }
            return read(c);
        } finally {
            c.disconnect();
        }
    }

    private static Reply read(HttpURLConnection c) throws Exception {
        Reply r = new Reply();
        r.code = c.getResponseCode();
        InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        r.raw = bo.toString("UTF-8");
        return r;
    }

    private static String errorText(Reply r) {
        String msg = r.raw;
        try {
            Object e = new JSONObject(r.raw).opt("error");
            if (e instanceof JSONObject) msg = ((JSONObject) e).getString("message");
        } catch (Exception ignored) {
            try {
                msg = new JSONArray(r.raw).getJSONObject(0).getJSONObject("error").getString("message");
            } catch (Exception ignored2) {
            }
        }
        if (msg.length() > 220) msg = msg.substring(0, 220) + "…";
        return "HTTP " + r.code + ": " + msg;
    }

    /** Asks the service which models it has and picks a suitable fast one (used when the default name is gone). */
    private static String pickModel(Provider p, String key) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(p.base + "models").openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            if (p.base.contains("generativelanguage.googleapis.com")) c.setRequestProperty("x-goog-api-key", key);
            Reply r = read(c);
            if (r.code >= 400) throw new Exception(errorText(r));
            JSONArray data = new JSONObject(r.raw).getJSONArray("data");
            String best = null;
            for (int i = 0; i < data.length(); i++) {
                String id = data.getJSONObject(i).getString("id");
                if (id.startsWith("models/")) id = id.substring(7);
                String l = id.toLowerCase(Locale.ROOT);
                if (l.contains("image") || l.contains("tts") || l.contains("live") || l.contains("audio")
                        || l.contains("embed") || l.contains("whisper") || l.contains("guard") || l.contains("vision")
                        || l.contains("robotics") || l.contains("computer") || l.contains("veo")) continue;
                boolean ok = p.name.equals("Gemini") ? l.contains("flash") && !l.contains("lite")
                        : l.contains("llama") || l.contains("kimi");
                if (ok && (best == null || id.compareTo(best) > 0)) best = id;
            }
            if (best == null) throw new Exception("No usable model found for " + p.name);
            return best;
        } finally {
            c.disconnect();
        }
    }

    private String chat(Context ctx, Provider p, String key, String model, List<String[]> past, String q) throws Exception {
        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content",
                "You are a voice assistant on a TV. Answer in one to three short, plain, spoken sentences. "
                        + "No markdown, no lists, no emojis. Reply in the language the user spoke."));
        for (String[] h : past) msgs.put(new JSONObject().put("role", h[0]).put("content", h[1]));
        msgs.put(new JSONObject().put("role", "user").put("content", q));

        Reply r = null;
        String use = model;
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject body = new JSONObject().put("model", use).put("messages", msgs);
            if (p.openai) {
                body.put("max_completion_tokens", 800);
                if (use.startsWith("gpt-5")) body.put("reasoning_effort", "minimal");
            }
            r = post(p.base + "chat/completions", key, body);
            boolean modelGone = r.code == 404 || (r.code == 400 && r.raw.toLowerCase(Locale.ROOT).contains("model"));
            if (r.code < 400 || !modelGone || attempt == 1) break;
            use = pickModel(p, key);                       // the default name no longer exists: find one
            prefs.edit().putString("auto_model_" + p.name, use).apply();
        }
        if (r.code >= 400) throw new Exception(errorText(r));
        String t = new JSONObject(r.raw).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();
        if (t.isEmpty()) throw new Exception("Empty answer. Try again.");
        return t;
    }

    // ---- keys & lifecycle -----------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            closeAnimated();
            return true;
        }
        if (e.getRepeatCount() == 0 && (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH)) {
            startListening();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!closing && !askingPermission) {      // sent to the background (Home pressed): don't linger
            closing = true;
            stopVoice();
            finish();
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (reveal != null) reveal.cancel();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
        }
        super.onDestroy();
    }
}
