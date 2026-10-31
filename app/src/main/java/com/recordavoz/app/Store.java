package com.recordavoz.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Guarda los recordatorios y ajustes en el teléfono. */
public final class Store {
    private static final String PREFS = "recordavoz";

    public static final class Item {
        public long id;
        public String text;
        public int min;
        public long next;
        public boolean paused;
    }

    private Store() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.getLong("id");
                it.text = o.getString("text");
                it.min = o.getInt("min");
                it.next = o.getLong("next");
                it.paused = o.optBoolean("paused", false);
                out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static synchronized void save(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        try {
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("text", it.text);
                o.put("min", it.min);
                o.put("next", it.next);
                o.put("paused", it.paused);
                arr.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("items", arr.toString()).commit();
    }

    public static synchronized long nextId(Context c) {
        long id = prefs(c).getLong("seq", 1) ;
        prefs(c).edit().putLong("seq", id + 1).commit();
        return id;
    }

    public static Item find(List<Item> items, long id) {
        for (Item it : items) if (it.id == id) return it;
        return null;
    }

    // ---- Ajustes ----
    public static boolean speak(Context c) { return prefs(c).getBoolean("speak", true); }
    public static void setSpeak(Context c, boolean v) { prefs(c).edit().putBoolean("speak", v).apply(); }

    /** Minutos desde medianoche. */
    public static int quietFrom(Context c) { return prefs(c).getInt("quietFrom", 22 * 60); }
    public static int quietTo(Context c) { return prefs(c).getInt("quietTo", 7 * 60); }
    public static void setQuietFrom(Context c, int v) { prefs(c).edit().putInt("quietFrom", v).apply(); }
    public static void setQuietTo(Context c, int v) { prefs(c).edit().putInt("quietTo", v).apply(); }

    public static int lastMin(Context c) { return prefs(c).getInt("lastMin", 120); }
    public static void setLastMin(Context c, int v) { prefs(c).edit().putInt("lastMin", v).apply(); }
}
