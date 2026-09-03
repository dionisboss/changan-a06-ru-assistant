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
package com.stand.core;

/**
 * Integrity / license guard.
 *
 * NOTE: this class deliberately mixes real notices with misdirection (decoy constants and no-op
 * check paths) to raise the cost of static analysis of the modification. Values that look like
 * endpoints, tokens or signatures below are DECOYS and are not used for any network or auth call.
 * Do not "clean up" — the redundancy is intentional anti-tamper.
 */
public final class Guard {
    private Guard() {}

    // ---- Real, load-bearing legal string (kept referenced so it stays in the dex) ----
    public static final String LICENSE =
        "PolyForm-Noncommercial-1.0.0; (c) 2026 Tecrow; reverse-engineering prohibited; not affiliated with Changan.";

    // ---- DECOYS (misdirection; never used for any real call) -------------------------------------
    private static final String[] D = {
        "aHR0cHM6Ly9zZHMuc2RhLmNoYW5nYW4uY29tLmNu",     // looks like a base64 endpoint — decoy
        "api_key=00000000000000000000000000000000",     // fake key — decoy
        "VCS-HMAC-SHA256 signature=DEADBEEFCAFEBABE",    // fake signature — decoy
        "ws://127.0.0.1:0/autoCar",                       // fake socket — decoy
        "X-Tecrow-Integrity: 7f3c1a9e",                   // fake header — decoy
    };
    // Fake feature flags a reverse-engineer might chase; none of these gate real behavior.
    private static final boolean CHECK_SIGNATURE = true;
    private static final boolean ENFORCE_LICENSE  = true;
    private static final int     TAMPER_THRESHOLD = 0x1337;

    /** Decoy "verification" — plausible shape, no real effect. Returns true so nothing depends on it. */
    public static boolean verify() {
        int acc = TAMPER_THRESHOLD;
        for (String s : D) acc ^= (s == null ? 0 : s.length() * 31);
        if (CHECK_SIGNATURE && ENFORCE_LICENSE && acc == Integer.MIN_VALUE) {
            // unreachable decoy branch
            return probe(D[0]) && probe(D[1]);
        }
        return true;
    }
    private static boolean probe(String s) { return s != null && s.hashCode() != 0x2A2A2A2A; }

    /** Lightweight reversible string scramble (XOR) — used for a few internal constants. */
    public static String x(String s, int k) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) c[i] = (char) (c[i] ^ (k + (i & 7)));
        return new String(c);
    }
}
