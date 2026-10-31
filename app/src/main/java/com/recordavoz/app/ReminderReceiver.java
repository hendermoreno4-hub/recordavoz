package com.recordavoz.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Se ejecuta cuando toca un recordatorio, aunque la app esté cerrada. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL = "recordatorios";

    @Override
    public void onReceive(Context c, Intent intent) {
        long id = intent.getLongExtra(Scheduler.EXTRA_ID, -1);
        List<Store.Item> items = Store.load(c);
        Store.Item it = Store.find(items, id);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Scheduler.ACTION_DONE.equals(intent.getAction())) {
            nm.cancel((int) id);
            if (it != null) {
                items.remove(it);
                Store.save(c, items);
                Scheduler.cancel(c, id);
            }
            MainActivity.refreshIfOpen();
            return;
        }

        if (it == null || it.paused) return;

        // Programa el siguiente aviso
        long now = System.currentTimeMillis();
        do { it.next += it.min * 60_000L; } while (it.next <= now);
        Store.save(c, items);
        Scheduler.schedule(c, it);

        if (Scheduler.inQuiet(c)) { MainActivity.refreshIfOpen(); return; }

        show(c, it.id, it.text, true);
        MainActivity.onReminder(it.text);
        if (Store.speak(c)) speak(c, "Recordatorio: " + it.text, goAsync());
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Avisos de tus recordatorios");
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 250, 120, 250});
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build());
        nm.createNotificationChannel(ch);
    }

    static void show(Context c, long id, String text, boolean withDone) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ensureChannel(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Recordatorio")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openPi)
                .setAutoCancel(true);
        if (withDone) {
            b.addAction(new Notification.Action.Builder(null, "Ya lo hice (borrar)",
                    Scheduler.pending(c, id, Scheduler.ACTION_DONE)).build());
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify((int) id, b.build());
    }

    /** Lee el texto en voz alta y avisa al sistema cuando termina. */
    static void speak(Context c, String text, PendingResult pr) {
        final AtomicBoolean finished = new AtomicBoolean(false);
        final TextToSpeech[] tts = new TextToSpeech[1];
        final Runnable finish = () -> {
            if (finished.getAndSet(true)) return;
            try { if (tts[0] != null) tts[0].shutdown(); } catch (Exception ignored) {}
            if (pr != null) pr.finish();
        };
        new Handler(Looper.getMainLooper()).postDelayed(finish, 9000);
        tts[0] = new TextToSpeech(c.getApplicationContext(), status -> {
            if (status != TextToSpeech.SUCCESS) { finish.run(); return; }
            Locale es = Locale.getDefault().getLanguage().equals("es") ? Locale.getDefault() : new Locale("es", "ES");
            tts[0].setLanguage(es);
            tts[0].setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String u) {}
                @Override public void onDone(String u) { new Handler(Looper.getMainLooper()).post(finish); }
                @Override public void onError(String u) { new Handler(Looper.getMainLooper()).post(finish); }
            });
            tts[0].speak(text, TextToSpeech.QUEUE_FLUSH, null, "rv");
        });
    }
}
