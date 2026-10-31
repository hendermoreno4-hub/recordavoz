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
        try {
            if (canExact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.next, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.next, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.next, pi);
        }
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
