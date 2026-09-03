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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Частотный словарь ударений для рантайма — fallback к вшитому лексикону
 * (TeraAccents). ~110k самых частотных русских словоформ с ударением из
 * ruaccent, отсортированы по ключу; бинарный поиск прямо по байтам файла
 * (assets/ruaccent.bin, ~2 МБ). Без нейросети, lookup ~микросекунды.
 *
 * Формат: строки «ударённое_слово\n», UTF-8, отсортированы по слову без '+'
 * в порядке кодпоинтов (== String.compareTo на кириллице BMP).
 */
final class RuAccentDict {
    private final byte[] data;

    RuAccentDict(Path binFile) throws IOException {
        this.data = Files.readAllBytes(binFile);
    }

    /** @return слово с '+' или null, если его нет в словаре. wordLower — нижний регистр. */
    String lookup(String wordLower) {
        int lo = 0, hi = data.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int lineStart = lineStartAt(mid);
            int lineEnd = lineEndAt(lineStart);
            String marked = new String(data, lineStart, lineEnd - lineStart, StandardCharsets.UTF_8);
            String key = marked.replace("+", "");
            int cmp = key.compareTo(wordLower);
            if (cmp == 0) {
                return marked;
            } else if (cmp < 0) {
                lo = lineEnd + 1;              // искомое правее
            } else {
                hi = lineStart;               // искомое левее
                if (lineStart == 0) break;
            }
        }
        return null;
    }

    /** Начало строки, содержащей байт pos: откат к байту после предыдущего '\n'. */
    private int lineStartAt(int pos) {
        if (pos >= data.length) pos = data.length - 1;
        while (pos > 0 && data[pos - 1] != '\n') pos--;
        return pos;
    }

    private int lineEndAt(int start) {
        int i = start;
        while (i < data.length && data[i] != '\n') i++;
        return i;
    }
}