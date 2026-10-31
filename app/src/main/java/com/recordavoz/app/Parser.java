package com.recordavoz.app;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Separa "qué recordar" de "cada cuánto" en lo que dijo la persona. */
public final class Parser {

    public static final class Result {
        public final String text;
        public final int min; // 0 si no dijo intervalo
        Result(String text, int min) { this.text = text; this.min = min; }
    }

    private static final Map<String, Integer> NUM = new LinkedHashMap<>();
    static {
        // los más largos primero para que la expresión los encuentre completos
        NUM.put("cuarenta y cinco", 45);
        NUM.put("veinticinco", 25);
        NUM.put("cincuenta", 50);
        NUM.put("cuarenta", 40);
        NUM.put("noventa", 90);
        NUM.put("treinta", 30);
        NUM.put("cuatro", 4);
        NUM.put("quince", 15);
        NUM.put("veinte", 20);
        NUM.put("nueve", 9);
        NUM.put("siete", 7);
        NUM.put("cinco", 5);
        NUM.put("ocho", 8);
        NUM.put("once", 11);
        NUM.put("doce", 12);
        NUM.put("diez", 10);
        NUM.put("tres", 3);
        NUM.put("seis", 6);
        NUM.put("dos", 2);
        NUM.put("una", 1);
        NUM.put("uno", 1);
        NUM.put("un", 1);
    }

    private static final String N;
    static {
        StringBuilder sb = new StringBuilder("(\\d+(?:[.,]\\d+)?");
        for (String k : NUM.keySet()) sb.append('|').append(k);
        N = sb.append(')').toString();
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern P_MEDIA = Pattern.compile("cada\\s+media\\s+hora", F);
    private static final Pattern P_HORA_MEDIA = Pattern.compile("cada\\s+hora\\s+y\\s+media", F);
    private static final Pattern P_HORAS = Pattern.compile("cada\\s+" + N + "\\s+horas?(\\s+y\\s+media)?", F);
    private static final Pattern P_MINUTOS = Pattern.compile("cada\\s+" + N + "\\s+min(?:uto)?s?\\b", F);
    private static final Pattern P_HORA = Pattern.compile("cada\\s+hora", F);
    private static final Pattern P_PREFIJO = Pattern.compile(
            "^\\s*(oye\\s+)?(por favor\\s+)?(recu[eé]rdame|recordarme|recu[eé]rdale|an[oó]ta(me)?|ap[uú]nta(me)?|av[ií]same)(\\s+(que|de|a))?\\s+", F);
    private static final Pattern P_SUFIJO = Pattern.compile("\\s+por favor\\s*$", F);

    private Parser() {}

    private static double toNum(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        if (k.matches(".*\\d.*")) {
            try { return Double.parseDouble(k.replace(',', '.')); } catch (NumberFormatException e) { return 0; }
        }
        Integer v = NUM.get(k);
        return v == null ? 0 : v;
    }

    public static Result parse(String raw) {
        String t = raw == null ? "" : raw.trim();
        int min = 0;
        Matcher m;
        if ((m = P_MEDIA.matcher(t)).find()) min = 30;
        else if ((m = P_HORA_MEDIA.matcher(t)).find()) min = 90;
        else if ((m = P_HORAS.matcher(t)).find()) min = (int) Math.round(toNum(m.group(1)) * 60 + (m.group(2) != null ? 30 : 0));
        else if ((m = P_MINUTOS.matcher(t)).find()) min = (int) Math.round(toNum(m.group(1)));
        else if ((m = P_HORA.matcher(t)).find()) min = 60;
        else m = null;

        if (m != null) t = t.substring(0, m.start()) + t.substring(m.end());

        t = P_PREFIJO.matcher(t).replaceFirst("");
        t = P_SUFIJO.matcher(t).replaceFirst("");
        t = t.replaceAll("\\s{2,}", " ").replaceAll("^[,.\\s]+|[,.\\s]+$", "");
        if (!t.isEmpty()) t = t.substring(0, 1).toUpperCase(Locale.getDefault()) + t.substring(1);
        return new Result(t, Math.max(min, 0));
    }

    public static String fmtMin(int m) {
        if (m < 60) return m + " min";
        if (m % 60 == 0) return (m / 60) + " h";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    public static String spokenMin(int m) {
        if (m < 60) return m + " minutos";
        if (m == 60) return "una hora";
        if (m % 60 == 0) return (m / 60) + " horas";
        int h = m / 60;
        return (h == 1 ? "una hora" : h + " horas") + " y " + (m % 60) + " minutos";
    }
}
