#!/usr/bin/env python3
"""Exact E07 V17.0126 hooks. Run only on a fresh classes5 baksmali tree."""
import re
import sys
from pathlib import Path

BRIDGE = "Lcom/stand/bridge/RuBridge;"
SEPROBE = "Lcom/stand/bridge/SeProbe;"
APP = "Lcom/incall/apps/speechassistant/"


def once(text, old, new):
    assert text.count(old) == 1, (old, text.count(old))
    return text.replace(old, new, 1)


def method(text, signature, change):
    pattern = re.compile(r"(^\.method [^\n]* " + re.escape(signature) + r"\n).*?^\.end method", re.M | re.S)
    matches = list(pattern.finditer(text))
    assert len(matches) == 1, (signature, len(matches))
    match = matches[0]
    return text[:match.start()] + change(match.group()) + text[match.end():]


def entry(body, code, minimum_registers=0):
    match = re.search(r"    \.registers (\d+)\n", body)
    assert match
    registers = max(int(match[1]), minimum_registers)
    return body[:match.start()] + f"    .registers {registers}\n" + code + body[match.end():]


def arbitration_delay(text):
    """Keep OEM arbitration; skip its cloud wait only for confident Russian injections."""
    return method(text, "arbitrationNluCloud(Ljava/lang/String;Ljava/lang/String;Landroid/util/Pair;)V",
                  lambda body: once(body, "    :goto_44\n    new-instance v1, Ljava/lang/StringBuilder;",
                                    """    :goto_44
    if-eqz p1, :e07_arbitration_delay
    const-string v1, "standzh-"
    invoke-virtual {p1, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :e07_arbitration_delay
    const-string v1, "localConfidence"
    invoke-virtual {v1, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :e07_arbitration_delay
    const/4 v0, 0x0
    :e07_arbitration_delay
    new-instance v1, Ljava/lang/StringBuilder;"""))


def patch(root):
    base = root / "com/incall/apps/speechassistant"
    changed = []

    def edit(name, change):
        path = base / name
        old = path.read_text()
        assert "e07_" not in old, f"already patched: {name}"
        new = change(old)
        assert new != old, name
        path.write_text(new)
        changed.append(name)

    def sdk(text):
        for signature in ("getHostAll()Ljava/lang/String;", "getHost(Ljava/lang/String;)Ljava/lang/String;",
                          "getHuToken()Ljava/lang/String;", "getToken()Ljava/lang/String;", "getSecretKey()Ljava/lang/String;"):
            def guard(body):
                casts = re.findall(r"    check-cast ([vp]\d+), Ljava/lang/String;", body)
                assert len(casts) == 1
                register = casts[0]
                return once(body, f"    check-cast {register}, Ljava/lang/String;",
                            f"    invoke-static {{{register}}}, {APP}third/ThirdUtils;->e07_sdkString(Ljava/lang/Object;)Ljava/lang/String;\n"
                            f"    move-result-object {register}")
            text = method(text, signature, guard)
        return text + f"""
.method private static e07_sdkString(Ljava/lang/Object;)Ljava/lang/String;
    .registers 3
    if-eqz p0, :e07_sdk_null
    instance-of v0, p0, Ljava/lang/String;
    if-nez v0, :e07_sdk_string
    const-string v0, "E07Voice"
    const-string v1, "SDK String result unavailable; keeping stock null-result handling"
    invoke-static {{v0, v1}}, Landroid/util/Log;->w(Ljava/lang/String;Ljava/lang/String;)I
    :e07_sdk_null
    const/4 p0, 0x0
    :e07_sdk_string
    check-cast p0, Ljava/lang/String;
    return-object p0
.end method
"""
    edit("third/ThirdUtils.smali", sdk)
    edit("application/VoiceApp.smali", lambda text: method(text, "onCreate()V", lambda body: once(
        body, f"    invoke-direct {{p0}}, {APP}application/VoiceApp;->initOther()V",
        f"    invoke-static {{p0}}, {BRIDGE}->init(Landroid/content/Context;)V\n\n"
        f"    invoke-direct {{p0}}, {APP}application/VoiceApp;->initOther()V")))

    def sr(text):
        engine = "Lcom/incall/apps/voiceservice/proxy/SrEngineProxy;"
        text = method(text, "startRecognize(IZ)I", lambda body: once(body,
            f"    invoke-virtual {{p2, p1}}, {engine}->start(I)I\n\n    move-result p2",
            f"    invoke-static {{p1}}, {BRIDGE}->startSession(I)Z\n"
            "    move-result v0\n    if-eqz v0, :e07_start_stock\n"
            "    const/4 p2, 0x0\n    goto :e07_start_done\n    :e07_start_stock\n"
            f"    invoke-virtual {{p2, p1}}, {engine}->start(I)I\n"
            "    move-result p2\n    :e07_start_done"))
        text = method(text, "stopRecognize()I", lambda body: once(body,
            f"    invoke-virtual {{v2}}, {engine}->stop()I\n\n    move-result v2",
            f"    invoke-static {{}}, {BRIDGE}->isRuSessionActive()Z\n"
            "    move-result v3\n    if-eqz v3, :e07_stop_stock\n    const/4 v3, 0x0\n"
            f"    invoke-static {{v3}}, {BRIDGE}->stopSession(Z)V\n"
            "    const/4 v2, 0x0\n    goto :e07_stop_done\n    :e07_stop_stock\n"
            f"    invoke-virtual {{v2}}, {engine}->stop()I\n"
            "    move-result v2\n    :e07_stop_done"))
        text = method(text, "appendAudioData([BI)V", lambda body: entry(body, capture_guard(), 4))
        # Фазы штатного SR — только диагностика: в русской сессии он не запущен.
        # Границы фразы определяет локальный Utterance по полученному штатному PCM.
        result_guard = (f"\n    invoke-static {{}}, {BRIDGE}->isRuSessionActive()Z\n"
                        "    move-result v0\n    if-eqz v0, :e07_msg_stock\n"
                        # onSrMsg объявлен с .registers 19, значит p1 — это v17, а обычный
                        # invoke-static кодирует регистр четырьмя битами и дальше v15 не берёт.
                        f"    invoke-static/range {{p1 .. p2}}, {BRIDGE}->onSrPhase(ILjava/lang/String;)V\n"
                        "    move/from16 v1, p1\n")
        for event in (1102, 1105, 1106, 1108, 1109):
            result_guard += f"    const/16 v2, {hex(event)}\n    if-eq v1, v2, :e07_msg_suppress\n"
        result_guard += "    goto :e07_msg_stock\n    :e07_msg_suppress\n    return-void\n    :e07_msg_stock\n"
        return method(text, "onSrMsg(ILjava/lang/String;)V", lambda body: entry(body, result_guard))

    def capture_guard():
        return (f"\n    invoke-static {{}}, {BRIDGE}->isRuSessionActive()Z\n    move-result v0\n"
                "    if-eqz v0, :e07_pcm_stock\n    return-void\n    :e07_pcm_stock\n")

    def se_sr_sink(text):
        # Preserve OEM capture, wake, all listener guards and both exporters. At this
        # exact handoff p3 is the SR proxy, not the callback's original channel tag.
        call = "    invoke-virtual {p3, p1, p2}, Lcom/incall/apps/voiceservice/proxy/SrEngineProxy;->appendAudioData([BI)V"
        return method(text, "appendData([BII)V", lambda body: once(body, call,
            f"    invoke-static {{p1, p2}}, {SEPROBE}->feed([BI)V\n"
            f"    invoke-static {{p1, p2}}, {BRIDGE}->acceptStockFrame([BI)Z\n"
            "    move-result v0\n    if-nez v0, :e07_sr_feed_done\n"
            + call + "\n    :e07_sr_feed_done"))

    edit("sr/SrSession.smali", sr)
    edit("se/SeSession$2.smali", se_sr_sink)
    edit("arbitration/ArbitrationManager.smali", arbitration_delay)

    signature = "start(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z" + APP + "tts/IPlayerListener;)I"
    for player in ("TtsPlayer", "TtsPlayer2"):
        def tts(text, player=player):
            code = ("\n    move-object/from16 v2, p2\n    move-object/from16 v3, p5\n"
                    "    move-object/from16 v6, p0\n"
                    f"    invoke-static {{v2, v3, v6}}, {BRIDGE}->onTtsText(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)Z\n"
                    "    move-result v2\n    if-eqz v2, :e07_tts_stock\n    const/4 v2, 0x0\n    return v2\n    :e07_tts_stock\n")
            if player == "TtsPlayer":
                hook = f"    invoke-virtual {{p0, v0}}, {APP}tts/TtsPlayer;->interrupt({APP}tts/IPlayerListener;)V"
                text = method(text, signature, lambda body: once(body, hook, hook + code))
                text = method(text, f"interrupt({APP}tts/IPlayerListener;)V", lambda body: entry(body,
                    f"\n    invoke-static {{p0}}, {BRIDGE}->stopSpeech(Ljava/lang/Object;)Z\n"
                    "    move-result v0\n    if-eqz v0, :e07_interrupt_stock\n"
                    "    return-void\n    :e07_interrupt_stock\n"))
                text = method(text, "onAudioFocusChange(I)V", lambda body: entry(body,
                    f"\n    invoke-static {{p0}}, {BRIDGE}->isSpeaking(Ljava/lang/Object;)Z\n"
                    "    move-result v0\n    if-eqz v0, :e07_focus_stock\n"
                    f"    invoke-static {{}}, {APP}controller/AudioFocusManager;->getInstance(){APP}controller/AudioFocusManager;\n"
                    "    move-result-object v0\n"
                    f"    invoke-virtual {{v0, p1}}, {APP}controller/AudioFocusManager;->isLost(I)Z\n"
                    "    move-result v0\n    if-eqz v0, :e07_focus_stock\n"
                    f"    invoke-static {{p0}}, {BRIDGE}->stopSpeech(Ljava/lang/Object;)Z\n"
                    "    return-void\n    :e07_focus_stock\n"))
                stop_signature, success = "stopAudio()Z", "0x1"
            else:
                text = method(text, signature, lambda body: entry(body, code))
                stop_signature, success = "stop()I", "0x0"
            text = method(text, stop_signature, lambda body: entry(body,
                f"\n    invoke-static {{p0}}, {BRIDGE}->stopSpeech(Ljava/lang/Object;)Z\n"
                "    move-result v0\n    if-eqz v0, :e07_tts_stop_stock\n"
                f"    const/4 v0, {success}\n    return v0\n    :e07_tts_stop_stock\n", 2))
            return method(text, "isPlay()Z", lambda body: entry(body,
                f"\n    invoke-static {{p0}}, {BRIDGE}->isSpeaking(Ljava/lang/Object;)Z\n"
                "    move-result v0\n    if-eqz v0, :e07_tts_state_stock\n    return v0\n    :e07_tts_state_stock\n", 2))
        edit(f"tts/{player}.smali", tts)
    assert len(changed) == 7   # Native wake listener is unchanged; only the SR consumer is hooked.
    return changed


if __name__ == "__main__":
    print("Patched:", ", ".join(patch(Path(sys.argv[1]))))
