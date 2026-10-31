package com.recordavoz.app;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_VOICE = 10;
    private static final int REQ_NOTIF = 11;
    private static final int[] PRESETS = {30, 60, 120, 180, 240, 360, 480};

    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private EditText text;
    private TextView live, dueText;
    private LinearLayout chips, list, banners, dueBanner;
    private Button quietFrom, quietTo;
    private int selMin;
    private TextToSpeech tts;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { renderList(); handler.postDelayed(this, 30_000); }
    };

    // ---------- Ciclo de vida ----------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ReminderReceiver.ensureChannel(this);

        text = findViewById(R.id.text);
        live = findViewById(R.id.live);
        chips = findViewById(R.id.chips);
        list = findViewById(R.id.list);
        banners = findViewById(R.id.banners);
        dueBanner = findViewById(R.id.dueBanner);
        dueText = findViewById(R.id.dueText);
        quietFrom = findViewById(R.id.quietFrom);
        quietTo = findViewById(R.id.quietTo);

        selMin = Store.lastMin(this);

        findViewById(R.id.mic).setOnClickListener(v -> startVoice());
        findViewById(R.id.save).setOnClickListener(v -> {
            Parser.Result r = Parser.parse(text.getText().toString());
            addItem(r.text, r.min > 0 ? r.min : selMin, false);
        });
        findViewById(R.id.clear).setOnClickListener(v -> { text.setText(""); live.setText(""); });
        findViewById(R.id.dueOk).setOnClickListener(v -> dueBanner.setVisibility(View.GONE));
        findViewById(R.id.test).setOnClickListener(v -> {
            ReminderReceiver.show(this, 0, "Así se verá tu recordatorio", false);
            onReminderShown("Así se verá tu recordatorio");
            if (Store.speak(this)) say("Recordatorio: así sonará tu recordatorio");
        });

        CheckBox speak = findViewById(R.id.optSpeak);
        speak.setChecked(Store.speak(this));
        speak.setOnCheckedChangeListener((b, on) -> Store.setSpeak(this, on));

        quietFrom.setOnClickListener(v -> pickTime(true));
        quietTo.setOnClickListener(v -> pickTime(false));

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(spanish());
        });

        renderChips();
        renderQuiet();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = new WeakReference<>(this);
        Scheduler.rescheduleAll(this);
        renderBanners();
        handler.removeCallbacks(ticker);
        ticker.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (current.get() == this) current = new WeakReference<>(null);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        renderBanners();
    }

    /** Llamado por el receptor cuando suena un recordatorio con la app abierta. */
    static void onReminder(String t) {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> { a.onReminderShown(t); a.renderList(); });
    }

    static void refreshIfOpen() {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(a::renderList);
    }

    private void onReminderShown(String t) {
        dueText.setText(t);
        dueBanner.setVisibility(View.VISIBLE);
    }

    // ---------- Voz ----------
    private Locale spanish() {
        Locale d = Locale.getDefault();
        return "es".equals(d.getLanguage()) ? d : new Locale("es", "ES");
    }

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, spanish().toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime qué te recuerdo y cada cuánto");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            live.setText("Este teléfono no tiene dictado por voz. Escribe la tarea o usa el micrófono del teclado.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_VOICE) return;
        if (res != RESULT_OK || data == null) {
            live.setText("No te escuché. Toca el micrófono para intentar otra vez.");
            return;
        }
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r == null || r.isEmpty()) return;
        Parser.Result p = Parser.parse(r.get(0));
        if (p.min > 0 && !p.text.isEmpty()) {
            selMin = p.min;
            renderChips();
            addItem(p.text, p.min, true);
        } else {
            text.setText(p.text);
            text.setSelection(text.length());
            if (p.min > 0) { selMin = p.min; renderChips(); }
            live.setText("Entendí: «" + p.text + "». Elige cada cuánto y toca Guardar.");
            say("¿Cada cuánto te lo recuerdo?");
        }
    }

    private void say(String s) {
        if (tts != null && Store.speak(this)) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "ui");
    }

    // ---------- Recordatorios ----------
    private void addItem(String t, int min, boolean fromVoice) {
        t = t == null ? "" : t.trim();
        if (t.isEmpty()) { live.setText("Escribe o di qué quieres que te recuerde."); return; }
        Store.Item it = new Store.Item();
        it.id = Store.nextId(this);
        it.text = t;
        it.min = min;
        it.next = System.currentTimeMillis() + min * 60_000L;
        List<Store.Item> items = Store.load(this);
        items.add(0, it);
        Store.save(this, items);
        Store.setLastMin(this, min);
        Scheduler.schedule(this, it);
        text.setText("");
        live.setText("Guardado: te recordaré «" + t + "» cada " + Parser.fmtMin(min) + ".");
        if (fromVoice) say("Listo. Te recordaré " + t + " cada " + Parser.spokenMin(min));
        renderList();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void renderChips() {
        chips.removeAllViews();
        List<Integer> opts = new ArrayList<>();
        for (int p : PRESETS) opts.add(p);
        if (!opts.contains(selMin)) opts.add(selMin);
        opts.sort(Integer::compare);
        for (int m : opts) {
            Button b = new Button(this);
            b.setText(Parser.fmtMin(m));
            b.setAllCaps(false);
            b.setStateListAnimator(null);
            b.setTextSize(14);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(14), 0, dp(14), 0);
            boolean on = m == selMin;
            b.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip);
            b.setTextColor(getColor(on ? R.color.inkOnInk : R.color.ink));
            b.setTypeface(null, Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMarginEnd(dp(8));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> { selMin = m; renderChips(); });
            chips.addView(b);
        }
    }

    private void renderList() {
        list.removeAllViews();
        List<Store.Item> items = Store.load(this);
        if (items.isEmpty()) {
            TextView e = new TextView(this);
            e.setText("Aún no hay recordatorios.\nToca el micrófono y dime qué recordarte.");
            e.setTextColor(getColor(R.color.muted));
            e.setGravity(Gravity.CENTER);
            e.setPadding(dp(8), dp(24), dp(8), dp(24));
            e.setBackgroundResource(R.drawable.bg_ghost);
            list.addView(e);
            return;
        }
        DateFormat hf = DateFormat.getTimeInstance(DateFormat.SHORT);
        long now = System.currentTimeMillis();
        for (Store.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_card);
            row.setPadding(dp(14), dp(12), dp(10), dp(12));
            row.setAlpha(it.paused ? 0.6f : 1f);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(10);
            row.setLayoutParams(rlp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView t = new TextView(this);
            t.setText(it.text);
            t.setTextColor(getColor(R.color.ink));
            t.setTextSize(16);
            t.setTypeface(null, Typeface.BOLD);

            TextView meta = new TextView(this);
            meta.setTextColor(getColor(R.color.muted));
            meta.setTextSize(13);
            String when;
            if (it.paused) when = "en pausa";
            else {
                long mins = Math.max(0, (it.next - now) / 60_000L);
                String in = mins < 1 ? "ahora" : mins < 60 ? "en " + mins + " min"
                        : "en " + (mins / 60) + " h" + (mins % 60 > 0 ? " " + (mins % 60) + " min" : "");
                when = "próximo a las " + hf.format(new Date(it.next)) + " (" + in + ")";
            }
            meta.setText("Cada " + Parser.fmtMin(it.min) + " · " + when);
            meta.setEllipsize(TextUtils.TruncateAt.END);

            col.addView(t);
            col.addView(meta);
            row.addView(col);

            row.addView(squareButton(it.paused ? "▶" : "❚❚", it.paused ? "Reanudar" : "Pausar", R.color.ink, v -> {
                List<Store.Item> all = Store.load(this);
                Store.Item x = Store.find(all, it.id);
                if (x == null) return;
                x.paused = !x.paused;
                if (!x.paused) x.next = System.currentTimeMillis() + x.min * 60_000L;
                Store.save(this, all);
                Scheduler.schedule(this, x);
                renderList();
            }));
            row.addView(squareButton("✓", "Terminado, borrar", R.color.ok, v -> {
                List<Store.Item> all = Store.load(this);
                Store.Item x = Store.find(all, it.id);
                if (x != null) all.remove(x);
                Store.save(this, all);
                Scheduler.cancel(this, it.id);
                renderList();
            }));
            list.addView(row);
        }
    }

    private Button squareButton(String label, String desc, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setContentDescription(desc);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTextSize(16);
        b.setTextColor(getColor(color));
        b.setBackgroundResource(R.drawable.bg_ghost);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMarginStart(dp(6));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    // ---------- Ajustes ----------
    private static String hhmm(int m) { return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60); }

    private void renderQuiet() {
        quietFrom.setText(hhmm(Store.quietFrom(this)));
        quietTo.setText(hhmm(Store.quietTo(this)));
    }

    private void pickTime(boolean from) {
        int cur = from ? Store.quietFrom(this) : Store.quietTo(this);
        new TimePickerDialog(this, (v, h, m) -> {
            if (from) Store.setQuietFrom(this, h * 60 + m); else Store.setQuietTo(this, h * 60 + m);
            renderQuiet();
        }, cur / 60, cur % 60, true).show();
    }

    // ---------- Permisos ----------
    private void renderBanners() {
        banners.removeAllViews();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            addBanner("Activa las notificaciones", "Sin esto no puedo avisarte.", "Activar", v -> {
                if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                }
            });
        }
        if (!Scheduler.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            addBanner("Permite alarmas exactas", "Así el aviso llega a la hora justa.", "Permitir", v ->
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName()))));
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            addBanner("Déjame funcionar en segundo plano", "Evita que el ahorro de batería apague los avisos.", "Permitir", v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
        }
    }

    private void addBanner(String title, String body, String action, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.bg_card);
        box.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        box.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(title + "\n" + body);
        t.setTextColor(getColor(R.color.ink));
        t.setTextSize(14);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button b = new Button(this);
        b.setText(action);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTextColor(getColor(R.color.inkOnInk));
        b.setBackgroundResource(R.drawable.bg_primary);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        blp.setMarginStart(dp(10));
        b.setLayoutParams(blp);
        b.setOnClickListener(l);

        box.addView(t);
        box.addView(b);
        banners.addView(box);
    }
}
