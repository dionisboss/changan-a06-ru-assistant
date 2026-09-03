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
package com.stand.vosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Test/dev trigger for the stand:
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es json '{...arbitration...}'
 *     -> NluManager.onArbitrationResult(json)  (execute a car command)
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es q 'текст'
 *     -> cloud dialog (chat)
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es cmd 'открой окно водителя'
 *     -> mapCommand(phrase): known car command -> arbitration, else -> chat (same routing as live ASR)
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es status 1
 *     -> pushStatus(): collect car status snapshot -> POST our backend /aibox/loadStatus
 *   am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es dicts 1
 *     -> pushDicts(): POST personalized dictionaries -> our backend /aibox/loadDicts
 */
public class StandNluReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String json = intent.getStringExtra("json");
        if (json != null && !json.isEmpty()) {
            Log.i("VoskBridge", "trigger execArbitration");
            VoskBridge.execArbitration(json);
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        if (cmd != null && !cmd.isEmpty()) {
            Log.i("VoskBridge", "trigger phrase: " + cmd);
            VoskBridge.handlePhrase(cmd);
            return;
        }
        if (intent.hasExtra("status")) { Log.i("VoskBridge", "trigger pushStatus"); VoskBridge.pushStatus(); return; }
        if (intent.hasExtra("dicts"))  { Log.i("VoskBridge", "trigger pushDicts");  VoskBridge.pushDicts();  return; }
        String diag = intent.getStringExtra("diag");
        if (diag != null && !diag.isEmpty()) { Log.i("VoskBridge", "trigger diagDump"); VoskBridge.diagDump(diag); return; }
        String dub = intent.getStringExtra("dubheb64");   // UTF-8 base64: send ZH straight to real Dubhe cloud
        if (dub != null && !dub.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(dub, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("VoskBridge", "trigger dubheTest(b64): " + dec);
                VoskBridge.dubheTest(dec);
            } catch (Throwable t) { Log.e("VoskBridge", "dubheb64", t); }
            return;
        }
        String rub = intent.getStringExtra("rub64");   // UTF-8 base64 Russian: full ru2zh/MyMemory→cloud→RU loop
        if (rub != null && !rub.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(rub, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("VoskBridge", "trigger handlePhraseZh(b64): " + dec);
                VoskBridge.handlePhraseZh(dec);
            } catch (Throwable t) { Log.e("VoskBridge", "rub64", t); }
            return;
        }
        String zhc = intent.getStringExtra("zhcloudb64");   // ZH -> self-contained cloud ask (answer -> RU speak)
        if (zhc != null && !zhc.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(zhc, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("VoskBridge", "trigger cloudAsk(b64): " + dec);
                VoskBridge.cloudAsk(dec);
            } catch (Throwable t) { Log.e("VoskBridge", "zhcloudb64", t); }
            return;
        }
        String zhb = intent.getStringExtra("zhb64");   // UTF-8 base64: shell-safe for CJK experiments
        if (zhb != null && !zhb.isEmpty()) {
            try {
                String dec = new String(android.util.Base64.decode(zhb, android.util.Base64.DEFAULT), "UTF-8");
                Log.i("VoskBridge", "trigger injectZh(b64): " + dec);
                VoskBridge.injectZh(dec);
            } catch (Throwable t) { Log.e("VoskBridge", "zhb64", t); }
            return;
        }
        String zh = intent.getStringExtra("zh");
        if (zh != null && !zh.isEmpty()) { Log.i("VoskBridge", "trigger injectZh"); VoskBridge.injectZh(zh); return; }
        String ru = intent.getStringExtra("ru");
        if (ru != null && !ru.isEmpty()) { Log.i("VoskBridge", "trigger ru2zh"); VoskBridge.handlePhraseZh(ru); return; }
        String say = intent.getStringExtra("say");
        if (say != null && !say.isEmpty()) { Log.i("VoskBridge", "trigger TeraTts.speak"); com.stand.tts.TeraTts.speak(say); return; }
        String q = intent.getStringExtra("q");
        if (q != null && !q.isEmpty()) {
            Log.i("VoskBridge", "trigger chat: " + q);
            VoskBridge.sendToCloud(q);
        }
    }
}