/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow. All rights reserved.
 *
 * Required Notice: Copyright (c) 2026 Tecrow. Reverse engineering prohibited.
 * Required Notice: Noncommercial use only. See LICENSE (PolyForm Noncommercial 1.0.0).
 *
 * Licensed under the PolyForm Noncommercial License 1.0.0 — COMMERCIAL USE IS NOT PERMITTED.
 * Reverse engineering, decompilation, and disassembly are NOT permitted under this license,
 * except to the minimum extent applicable mandatory law expressly allows. This source and the
 * compiled result are protected by copyright; unauthorized redistribution is prohibited.
 * Independent mod — NOT affiliated with or endorsed by Changan Automobile. See LICENSE.
 */
package com.stand.tts.tera;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Развёртка чисел в русские слова (порт поведения num2words lang=ru).
 * Целые до миллиардов; десятичные как у num2words:
 * "2,5" -> "две целых пять десятых", "0,05" -> "ноль целых пять сотых".
 * Достаточно для реплик ассистента; склонение существительных после числа
 * не делается (как и в num2words: "двадцать один" + "градус" остаётся за текстом).
 */
final class TeraNumbers {
    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\w.])[-−]?\\d+(?:[.,]\\d+)?(?![\\w.])");

    private static final String[] UNITS = {
            "ноль", "один", "два", "три", "четыре", "пять",
            "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {
            "", "", "двадцать", "тридцать", "сорок", "пятьдесят",
            "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {
            "", "сто", "двести", "триста", "четыреста", "пятьсот",
            "шестьсот", "семьсот", "восемьсот", "девятьсот"};

    private TeraNumbers() {
    }

    static String expandNumbers(String text) {
        Matcher m = NUMBER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(speak(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String speak(String literal) {
        literal = literal.replace("−", "-");
        String sign = "";
        if (literal.startsWith("-")) {
            sign = "минус ";
            literal = literal.substring(1);
        }
        String intPart = literal;
        String fracPart = null;
        int sep = Math.max(literal.indexOf('.'), literal.indexOf(','));
        if (sep >= 0) {
            intPart = literal.substring(0, sep);
            fracPart = literal.substring(sep + 1);
        }
        StringBuilder out = new StringBuilder(sign);
        if (fracPart == null || fracPart.isEmpty()) {
            out.append(speakInt(Long.parseLong(intPart), false));
        } else {
            // как num2words: "две целых пять десятых", "ноль целых пять сотых"
            long ip = Long.parseLong(intPart);
            out.append(speakInt(ip, true)).append(' ')
                    .append(agreeFem(ip, "целая", "целых")).append(' ');
            long fp = Long.parseLong(fracPart);
            out.append(speakInt(fp, true)).append(' ')
                    .append(agreeFem(fp, fracUnitOne(fracPart.length()), fracUnitMany(fracPart.length())));
        }
        return out.toString().trim();
    }

    private static String fracUnitOne(int digits) {
        switch (digits) {
            case 1: return "десятая";
            case 2: return "сотая";
            case 3: return "тысячная";
            case 4: return "десятитысячная";
            case 5: return "стотысячная";
            default: return "миллионная";
        }
    }

    private static String fracUnitMany(int digits) {
        switch (digits) {
            case 1: return "десятых";
            case 2: return "сотых";
            case 3: return "тысячных";
            case 4: return "десятитысячных";
            case 5: return "стотысячных";
            default: return "миллионных";
        }
    }

    private static String agreeFem(long n, String one, String many) {
        return (n % 10 == 1 && n % 100 != 11) ? one : many;
    }

    private static String speakInt(long n, boolean feminine) {
        if (n == 0) return "ноль";
        StringBuilder out = new StringBuilder();
        long billions = n / 1_000_000_000L;
        long millions = (n / 1_000_000L) % 1000;
        long thousands = (n / 1000L) % 1000;
        long rest = n % 1000;
        if (billions > 0) {
            out.append(triple(billions, false)).append(' ')
                    .append(plural(billions, "миллиард", "миллиарда", "миллиардов")).append(' ');
        }
        if (millions > 0) {
            out.append(triple(millions, false)).append(' ')
                    .append(plural(millions, "миллион", "миллиона", "миллионов")).append(' ');
        }
        if (thousands > 0) {
            out.append(triple(thousands, true)).append(' ')
                    .append(plural(thousands, "тысяча", "тысячи", "тысяч")).append(' ');
        }
        if (rest > 0) {
            out.append(triple(rest, feminine));
        }
        return out.toString().trim();
    }

    private static String triple(long n, boolean feminine) {
        StringBuilder out = new StringBuilder();
        int h = (int) (n / 100), t = (int) ((n / 10) % 10), u = (int) (n % 10);
        if (h > 0) out.append(HUNDREDS[h]).append(' ');
        if (t == 1) {
            out.append(TEENS[u]);
        } else {
            if (t >= 2) out.append(TENS[t]).append(' ');
            if (u > 0) {
                if (feminine && u == 1) out.append("одна");
                else if (feminine && u == 2) out.append("две");
                else out.append(UNITS[u]);
            }
        }
        return out.toString().trim();
    }

    private static String plural(long n, String one, String few, String many) {
        long mod100 = n % 100, mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return many;
        if (mod10 == 1) return one;
        if (mod10 >= 2 && mod10 <= 4) return few;
        return many;
    }
}