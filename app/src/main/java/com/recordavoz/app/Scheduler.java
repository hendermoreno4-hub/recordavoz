package com.recordavoz.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.List;

/** Programa las alarmas del sistema, que suenan aunque la app esté cerrada. */
public final class Scheduler {
    public static final String ACTION_REMIND = "com.recordavoz.app.REMIND";
    public static final String ACTION_DONE = "com.recordavoz.app.DONE";
    public static final String EXTRA_ID = "id";

    private Scheduler() {}

    static PendingIntent pending(Context c, long id, String action) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(action).putExtra(EXTRA_ID, id);
        int code = (int) (id * 2 + (ACTION_DONE.equals(action) ? 1 : 0));
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static boolean canExact(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    public static void schedule(Context c, Store.Item it) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c, it.id, ACTION_REMIND);
        if (it.paused) { am.cancel(pi); return; }
        setAlarm(c, it.next, pi);
    }

    /**
     * Usa el mismo tipo de alarma que el reloj despertador: el sistema no la retrasa
     * ni la bloquea por ahorro de batería, aunque la app esté cerrada.
     */
    static void setAlarm(Context c, long when, PendingIntent pi) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent show = PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pi);
        } catch (SecurityException e) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            } catch (SecurityException e2) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            }
        }
    }

    public static final String ACTION_TEST = "com.recordavoz.app.TEST";

    /** Programa un aviso de prueba dentro de 1 minuto. */
    public static void scheduleTest(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(ACTION_TEST);
        PendingIntent pi = PendingIntent.getBroadcast(c, 999_999, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        setAlarm(c, System.currentTimeMillis() + 60_000L, pi);
    }

    public static void cancel(Context c, long id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(c, id, ACTION_REMIND));
    }

    /** Pone al día los recordatorios atrasados y vuelve a programarlos todos. */
    public static void rescheduleAll(Context c) {
        List<Store.Item> items = Store.load(c);
        long now = System.currentTimeMillis();
        for (Store.Item it : items) {
            if (it.paused) continue;
            while (it.next <= now) it.next += it.min * 60_000L;
            schedule(c, it);
        }
        Store.save(c, items);
    }

    public static boolean inQuiet(Context c) {
        int a = Store.quietFrom(c), b = Store.quietTo(c);
        if (a == b) return false;
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        return a < b ? (now >= a && now < b) : (now >= a || now < b);
    }
}
