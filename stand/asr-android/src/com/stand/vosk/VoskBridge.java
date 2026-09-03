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

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import org.json.JSONObject;

/**
 * In-process offline Russian ASR tap for SpeechAssistant.
 * Hooked into SrBaseSession's processed-audio callback (onMSDataProc): the same
 * AEC/beamformed PCM the native iFlytek ASR consumes is streamed into Vosk.
 * Triggered automatically by the native wake (knob), no separate mic/AudioRecord.
 */
public final class VoskBridge {
    private static final String TAG = "VoskBridge";

    /**
     * LICENSE / LEGAL NOTICE (kept as a runtime string so it survives compilation into the APK and
     * is visible in any decompilation — do not strip). Russian Voice Assistant mod for Changan A06.
     * Copyright (c) 2026 Tecrow. Licensed under PolyForm Noncommercial 1.0.0 — COMMERCIAL USE IS NOT
     * PERMITTED. Reverse engineering / decompilation / disassembly are NOT permitted under this
     * license (except where mandatory law expressly allows). Independent mod — NOT affiliated with,
     * endorsed by, or produced by Changan Automobile. Full terms: assets/NOTICE.txt and LICENSE.
     */
    public static final String MOD_VERSION = "1.0.2";
    public static final String NOTICE =
        "Copyright (c) 2026 Tecrow. Author: Voronov Aleksei Sergeevich. "
      + "Russian Voice Assistant mod for Changan A06 (C390) v1.0.2. "
      + "Licensed under PolyForm Noncommercial 1.0.0 — NONCOMMERCIAL USE ONLY, COMMERCIAL USE PROHIBITED. "
      + "Reverse engineering, decompilation and disassembly are PROHIBITED by this license. "
      + "Independent modification, NOT affiliated with or endorsed by Changan Automobile.";

    // Stock TTS-engine registry (world-writable dir; the app runs as uid=system). Registering our
    // engine here is what makes install one-step — no adb edit of the config needed.
    private static final String TTS_CFG =
        "/resources/iflytek/speech/changan_assets/tts_config.txt";
    private static final String OUR_TTS_CLASS = "com.stand.tts.PiperCaTts";
    private static final String[] STOCK_TTS_CLASSES = {
        "com.iflytek.speech.tts.IssTtsStreamImpl",
        "com.incall.apps.speechassistant.tts.ChanganTtsImpl",
    };

    /** Self-install: point the active TTS engines in tts_config.txt at our PiperCaTts (idempotent,
     *  keeps a one-time .bak). Targeted className replacement preserves all other unit-specific fields.
     *  May be blocked by SELinux on some builds → then the manual adb step is the fallback. Takes full
     *  effect after the next app restart (the stock config is already loaded on the current launch). */
    static void ensureTtsRegistered() {
        try {
            File cfg = new File(TTS_CFG);
            if (!cfg.exists()) { Log.i(TAG, "tts_config not found, skip self-register"); return; }
            String txt = readTextFile(cfg);
            if (txt == null || txt.isEmpty()) return;
            if (txt.contains(OUR_TTS_CLASS)) return;                 // already registered
            File bak = new File(TTS_CFG + ".bak");
            if (!bak.exists()) writeTextFile(bak, txt);              // one-time backup of the stock config
            String patched = txt;
            for (String s : STOCK_TTS_CLASSES) patched = patched.replace(s, OUR_TTS_CLASS);
            if (patched.equals(txt)) { Log.w(TAG, "tts_config: no stock TTS class to replace"); return; }
            boolean ok = writeTextFile(cfg, patched);
            Log.i(TAG, "tts_config: PiperCaTts " + (ok ? "registered (self-install; restart to activate)"
                                                       : "WRITE FAILED (SELinux? use manual adb step)"));
        } catch (Throwable t) { Log.e(TAG, "ensureTtsRegistered", t); }
    }
    private static String readTextFile(File f) {
        try { byte[] b = new byte[(int) f.length()]; java.io.FileInputStream in = new java.io.FileInputStream(f);
              int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; in.close();
              return new String(b, 0, off, "UTF-8"); }
        catch (Throwable t) { Log.e(TAG, "readTextFile " + f, t); return null; }
    }
    private static boolean writeTextFile(File f, String s) {
        try { java.io.FileOutputStream o = new java.io.FileOutputStream(f);
              o.write(s.getBytes("UTF-8")); o.flush(); o.close(); return true; }
        catch (Throwable t) { Log.e(TAG, "writeTextFile " + f, t); return false; }
    }

    /** True if our TTS engine is currently registered in tts_config.txt (for the settings switch state). */
    public static boolean isRuTtsOn() {
        try { String t = readTextFile(new File(TTS_CFG)); return t != null && t.contains(OUR_TTS_CLASS); }
        catch (Throwable t) { return false; }
    }

    /** Default the custom wake word to «你好» so users can activate the assistant by saying "нихао".
     *  Stored in Settings.Global "voice_custom_name"; a ContentObserver re-registers it with the IVW
     *  engine. Only set when empty — never clobber a word the user chose. App is uid=system → allowed. */
    static void ensureCustomWakeword() {
        try {
            // Apply the «你好» default ONCE (first run after install), then respect the user's choice —
            // a marker in our prefs guards it so we never clobber a word the user later sets in Settings.
            android.content.SharedPreferences sp = appCtx.getSharedPreferences("stand", 0);
            if (sp.getBoolean("wakeword_defaulted", false)) return;
            android.content.ContentResolver cr = appCtx.getContentResolver();
            boolean ok = android.provider.Settings.Global.putString(cr, "voice_custom_name", "你好"); // 你好
            sp.edit().putBoolean("wakeword_defaulted", true).apply();
            Log.i(TAG, "wakeword: default «你好» applied once (" + ok + ")");
        } catch (Throwable t) { Log.e(TAG, "ensureCustomWakeword", t); }
    }

    /** Restore the stock TTS engine from the one-time backup (.bak). Effect after the next restart. */
    static void restoreTts() {
        try {
            File cfg = new File(TTS_CFG), bak = new File(TTS_CFG + ".bak");
            if (!bak.exists()) { Log.w(TAG, "restoreTts: no .bak"); return; }
            String orig = readTextFile(bak);
            if (orig == null || orig.isEmpty()) return;
            boolean ok = writeTextFile(cfg, orig);
            Log.i(TAG, "tts_config: stock " + (ok ? "restored from .bak (restart to activate)" : "restore FAILED"));
        } catch (Throwable t) { Log.e(TAG, "restoreTts", t); }
    }

    /** Settings-switch entry point: on → register our TTS engine, off → restore stock. Returns the
     *  resulting state (registered?). Safe to call from the UI thread (small file I/O). */
    public static boolean setRuTts(boolean on) {
        if (on) ensureTtsRegistered(); else restoreTts();
        return isRuTtsOn();
    }

    /** "верни/восстанови заводскую/оригинальную/китайскую озвучку/голос" → restore stock TTS. */
    private static boolean isRestoreVoice(String s) {
        boolean voice = s.contains("озвуч") || s.contains("голос");
        boolean stock = s.contains("заводск") || s.contains("оригинальн") || s.contains("штатн")
                     || s.contains("стандартн") || s.contains("китайск");
        return voice && stock;
    }
    /** "включи/верни русскую озвучку" → re-register our TTS. */
    private static boolean isEnableVoice(String s) {
        boolean voice = s.contains("озвуч") || s.contains("голос");
        return voice && s.contains("русск") && (s.contains("включ") || s.contains("верни") || s.contains("вернуть"));
    }

    /** Injected at the end of SettingsActivity.onCreate: add a native-looking "Русская озвучка" switch
     *  row at the top of the settings list (above the wakeup row) so the user can turn our TTS engine
     *  on/off without adb. Everything is done in code — no stock layout/resource edit. Idempotent. */
    public static void installTtsSwitch(final android.app.Activity act) {
        try {
            final android.content.Context ctx = act;
            android.view.View content = act.findViewById(android.R.id.content);
            if (!(content instanceof android.view.ViewGroup)) return;
            android.widget.Switch wake = findFirstSwitch((android.view.ViewGroup) content);
            if (wake == null) return;
            // Climb to the row that sits inside the vertical rows-list container.
            android.view.View row = wake;
            android.view.ViewParent vp = row.getParent();
            while (vp instanceof android.view.ViewGroup) {
                android.view.ViewGroup g = (android.view.ViewGroup) vp;
                if (g instanceof android.widget.LinearLayout
                        && ((android.widget.LinearLayout) g).getOrientation() == android.widget.LinearLayout.VERTICAL
                        && g.getChildCount() >= 2) break;
                row = g; vp = g.getParent();
            }
            if (!(vp instanceof android.view.ViewGroup)) return;
            final android.view.ViewGroup container = (android.view.ViewGroup) vp;
            if (container.findViewWithTag("ru_tts_row") != null) return;   // already added
            int idx = container.indexOfChild(row); if (idx < 0) idx = 0;

            android.widget.LinearLayout myRow = new android.widget.LinearLayout(ctx);
            myRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            myRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            myRow.setTag("ru_tts_row");
            try { myRow.setPadding(row.getPaddingLeft(), row.getPaddingTop(),
                                   row.getPaddingRight(), row.getPaddingBottom()); } catch (Throwable ignored) {}
            try { android.view.ViewGroup.LayoutParams src = row.getLayoutParams();
                  if (src != null) myRow.setLayoutParams(new android.view.ViewGroup.LayoutParams(src)); }
            catch (Throwable ignored) {}

            android.widget.TextView label = new android.widget.TextView(ctx);
            label.setText("Русская озвучка");
            try { label.setTextColor(0xFFFFFFFF); } catch (Throwable ignored) {}
            try { label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 40f); } catch (Throwable ignored) {}
            label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            final android.widget.Switch sw = new android.widget.Switch(ctx);
            sw.setChecked(isRuTtsOn());
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    boolean now = setRuTts(on);
                    if (now != on) b.setChecked(now);
                    try { android.widget.Toast.makeText(ctx, now
                            ? "Русская озвучка включена. Перезапустите ассистента."
                            : "Заводская озвучка восстановлена. Перезапустите ассистента.",
                            android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
                }
            });

            myRow.addView(label);
            myRow.addView(sw);
            container.addView(myRow, idx);          // above the wakeup row
            Log.i(TAG, "settings: RU-TTS switch installed at idx " + idx);
        } catch (Throwable t) { Log.e(TAG, "installTtsSwitch", t); }
    }

    private static android.widget.Switch findFirstSwitch(android.view.ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            android.view.View c = g.getChildAt(i);
            if (c instanceof android.widget.Switch) return (android.widget.Switch) c;
            if (c instanceof android.view.ViewGroup) {
                android.widget.Switch s = findFirstSwitch((android.view.ViewGroup) c);
                if (s != null) return s;
            }
        }
        return null;
    }
    private static final String ACTION = "com.stand.NLU";
    private static Context appCtx;
    private static volatile String lastText = "";   // latest ASR hypothesis for current utterance
    private static volatile int wakeZone = 1;        // detected speaking zone (SrBaseSession.getCurrentDirect: 1=driver,2=passenger,3/4=rear,5=rear-mid)
    // Force offline: online chat backend isn't ready, so unrecognized phrases give a local RU reply
    // instead of hitting the cloud. Flip to false once the LLM backend is live.
    private static final boolean OFFLINE_ONLY = true;   // RELEASE: forced offline-only (no cloud/chat; no personal backend)

    // Online conversational path via the NATIVE (device-signed) Dubhe cloud + MyMemory translation:
    // a free-form phrase the rule-based ru2zh can't map is translated RU->ZH (MyMemory) and injected
    // into the stock NLU, which consults the real Changan cloud; the Chinese answer is turned back to
    // Russian on the way out. Independent of OFFLINE_ONLY (that only gates the dead 127.0.0.1 backend).
    private static final boolean CLOUD_MT = true;

    // Consult the real Changan Dubhe cloud in parallel with the local NLU on every injected Chinese
    // query (see injectZh): cloud carControl executes and the Chinese answer (dmResults.tts) returns.
    // Requires a NATIVE_CLOUD build (else the endpoint is redirected to a dead 127.0.0.1).
    private static final boolean CLOUD_INJECT = false;

    // ---- ASR: GigaAM-v3 (sherpa-onnx offline CTC) ------------------------------------------------
    // GigaAM-v3 CTC is a full-utterance Conformer (no streaming): we accumulate the whole utterance
    // (wp==1..wp==3) and decode it once at the end via com.stand.asr.GigaAsr, yielding a single
    // hypothesis. That text is fuzzy-corrected against GRAMMAR_WORDS (see chooseQuery / fuzzyFix).
    //
    // ---- ASR strategy: open vocabulary + fuzzy correction ----------------------------------------
    // The decoder is open-vocabulary (no grammar restriction). GRAMMAR_WORDS is NOT a grammar — it is
    // a vetted CORRECTION dictionary for fuzzyFix() (every ru2zh contains()-stem has a form here);
    // since it includes "не"/question words, the negation/question guards in ru2zh always see them.
    private static volatile java.util.Set<String> grammarSet; // GRAMMAR_WORDS as a set (for fuzzyFix)
    private static final String[] GRAMMAR_WORDS = {
        // ---- verbs / actions ----
        "включи","включить","включай","вруби","выключи","выключить","выключай","выруби","вырубай",
        "отключи","открой","открыть","открывай","закрой","закрыть","закрывай","закрытие","подними",
        "поднять","опусти","опустить","прибавь","убавь","увеличь","уменьши","повысь","понизь",
        "установи","поставь","сделай","переключи","смени","поменяй","верни","добавь","запусти",
        "активируй","убери","убрать","убирай","приоткрой","заблокируй","разблокируй","разблокировку",
        "запри","отопри","сложи","разложи","разверни","сверни","погаси","разбуди","усыпи","проснись",
        "помой","протри","вытри","брызни","найди","поищи","покажи","запомни","забудь","сохрани",
        "удали","выбери","продолжи","продолжай","останови","перемотай","перезвони","позвони",
        "набери","ответь","возьми","сбрось","отклони","отбой","отвечай","подожди","подзови",
        "подъезжай","припаркуйся","выезжай","выехать","заезжай","перестройся","обгони","держи",
        "держись","следуй","отмени","выйди","закройся","отстань","уйди","отвали","листай","проветри",
        "прогрей","согрей","погрей","подогрей","греть","охлади","синхронизируй","выровняй","начни",
        "сними","запиши","сфоткай","скачай","замолчи","замолкни","перестань","говорить","хватит",
        "стоп","поехали","вези","едем","ехать","объезжай","объедь","объехать","приблизь","отдали",
        "поверни","заверши","положи","продли","погрузи","погрузить","раздай","вернись",
        // ---- climate ----
        "температуру","температура","градус","градуса","градусов","кондиционер","кондиционеры",
        "кондей","кондер","климат","печку","печка","обдув","вентилятор","вентиляцию","вентиляция",
        "рециркуляцию","рециркуляция","циркуляцию","воздух","воздуха","обогрев","подогрев",
        "разморозку","запотели","потеют","стекло","стекла","лобовое","лобового","авто",
        "автоматический","охлаждение","нагрев","осушение","влажность","дуй","лицо","ноги","поток",
        "качание","теплее","холоднее","потеплее","похолоднее","прохладнее","жарко","душно","замерз",
        "холодно","синхронизацию","одинаковая","аромат","ароматизатор","парфюм","запах","электро",
        // ---- seats ----
        "сиденье","сиденья","сидение","сидений","кресло","кресла","кресел","массаж","массажа",
        "поясницу","поясница","спинку","спинка","подушку","подставку","подножку","опору","наклон",
        "позицию","вип","посадку","выход","вперед","назад",
        // ---- windows / roof / doors / locks ----
        "окно","окна","окон","окнах","окошко","стеклоподъемники","люк","шторку","шторки","шторка",
        "шторы","солнцезащитную","боковые","багажник","багажника","багажнике","капот","фрунк",
        "дверь","двери","дверцу","замок","замки","блокировку","лючок","бак","бензобака","детский",
        "половину","наполовину","процентов","процент","треть","четверть","щелочку","щель","борт",
        "верхнюю","нижний","охране","охрану","охраны","дождь","дожде","дождя","подходе","уходе",
        "отходе",
        // ---- lights ----
        "подсветку","подсветка","подсветки","атмосферную","цвет","красный","синий","зеленый","белый",
        "желтый","фиолетовый","оранжевый","розовый","ярче","темнее","притуши","приглуши","фары",
        "свет","дальний","ближний","аварийку","аварийную","сигнализацию","габариты","противотуманки",
        "стояночные","позиционные","плафон","салона","салоне","чтения","лампу","лампа","фонари",
        "фонарь","шоу","светомузыку","световое","эффект","градиент","переливы","такт","ритм",
        // ---- mirrors / steering / wipers ----
        "зеркало","зеркала","зеркал","руль","руля","рулевое","дворники","дворников","щетки",
        "стеклоочистители","омыватель","чувствительность","датчик","датчика","легче","тяжелее",
        "тяжелый","усилие","автоскладывание","ремонт","сервисный","замену","стриминговое",
        // ---- hud / displays ----
        "проекцию","проекция","проекции","хад","худ","экран","экрана","экране","дисплей","яркость",
        "автояркость","цветовую","шрифт","ночной","дневной","горизонтально","вертикально","угол",
        "высоту","защиту","глаз","очистку","почистить","пассажирского",
        // ---- media / sound ----
        "громкость","громче","тише","потише","погромче","звук","звука","звуком","звуковую","музыку",
        "музыка","музычку","песню","песня","песенку","песни","трек","композицию","следующий",
        "следующую","следующая","предыдущий","предыдущая","предыдущую","пауза","паузу","играй",
        "играет","радио","станцию","станция","канал","частоту","волну","источник","плейлист",
        "избранное","избранного","повтор","повтори","заново","сначала","начало","запись","записи",
        "минут","минуту","минуты","секунд","скорость","воспроизведения","лайк","нравится","текст",
        "караоке","качество","юсб","флешка","флешку","историю","история","прослушивания","перемешай",
        "кругу","порядку","случайно","сцену","улучшение","динамик","список","онлайн","прошлую",
        "другой","другую",
        // ---- navi ----
        "навигацию","навигатор","навигации","навигационный","карту","карта","карточку","слои",
        "маршрут","домой","работу","заправку","заправка","заправиться","зарядку","зарядка","зарядки",
        "зарядный","парковку","стоянку","кафе","кофе","ресторан","поесть","кушать","есть",
        "проголодался","аптеку","больницу","туалет","супермаркет","банкомат","гостиницу","отель",
        "мойку","магазин","продуктов","пробки","пробок","спутник","обычную","платных","шоссе",
        "трассы","дорог","дорога","дороге","дорогу","пути","подсказки","кратко","подробно","север",
        "курсу","далеко","долго","приедем","доедем","место","места","адрес","пункт","назначения",
        "аэропорт","вокзал","центр","куда","километров","километра","осталось","время","полный",
        "ближайшая","ближайший","азс",
        // ---- phone ----
        "трубку","контакты","контакт","контактах","маме","папе","жене","мужу","брату","сестре",
        "журнал","вызовов","звонков","звонки","пропущенные","звонок","вызов","номер","сервис",
        "телефон","телефона",
        // ---- modes / vehicle / misc ----
        "режим","режимы","спорт","эко","экономичный","комфорт","комфортный","стандартный","снежный",
        "снег","зимний","бездорожье","внедорожный","подвеску","подвеска","клиренс","мягче","жестче",
        "рекуперацию","рекуперация","электрический","электричестве","гибрид","гибридный","чисто",
        "круиз","круизом","адаптивный","автопилот","пилот","вождения","дистанцию","следования",
        "следование","полосу","полосе","ограничение","автопарковку","сама","машина","машину",
        "машины","холодильник",
        "холодильника","приватный","блютус","вайфай","интернет","точку","точка","доступа","розетку",
        "беспроводную","регистратор","регистратора","камеру","камера","камеры","обзор","панораму",
        "круговой","вида","видео","кадр","фото","фотографии","селфи","галерею","альбом","виджеты",
        "минус","персонажа","аватар","тему","темы","тема","оформления","обои","заставку","голос",
        "мужской","женский","слово","пробуждения","активации","будильник","напоминание",
        "напоминания","ремнях","кино","фильм","кровать","отдых","кемпинг","кемпинга","палатку",
        "бодрость","взбодри","вздремнуть","подремать","поспать","макияж","встречи","погрузки",
        "очиститель","осушитель","пылесос","дома","домашний","квартире","расширения","общение",
        "свободное","ассистент","помощник","внешний","настройки","настройку","приложение",
        "приложения","браузер","календарь","беспокоить","сценарии","сценарий","главный","главную",
        "рабочий","стол","страница","страницу","батарея","батареи","давление","шин","шинах","колес",
        "пробег","бензина","топлива","заряд","заряда","заряжена","уровень","запас","хода","статус",
        "пожаловаться","укачивает","тошнит","часов",
        // ---- zones ----
        "все","всех","весь","салон","водителю","водителем","водителя","водительское","пассажиру",
        "пассажира","пассажиром","пассажирам","пассажирское","заднему","задней","заднее","заднего",
        "задние","задних","передние","переднее","передних","спереди","сзади","слева","справа",
        "правое","правому","правее","левое","левому","левее","вверх","вниз","влево","вправо",
        // ---- grades / quantities ----
        "максимум","максимально","минимум","минимально","полностью","больше","меньше","выше","ниже",
        "сильнее","слабее","посильнее","послабее","побольше","поменьше","повыше","пониже","быстрее",
        "медленнее","длиннее","короче","крупнее","мельче","чуть","немного","еще","ближе","дальше",
        "впереди","снова","раз",
        // ---- numbers (spoken) ----
        "ноль","один","одну","два","две","три","четыре","пять","шесть","семь","восемь","девять",
        "десять","одиннадцать","двенадцать","тринадцать","четырнадцать","пятнадцать","шестнадцать",
        "семнадцать","восемнадцать","девятнадцать","двадцать","тридцать","сорок","пятьдесят",
        "шестьдесят","семьдесят","восемьдесят","девяносто","сто","полтора",
        "первый","первую","первая","второй","вторую","вторая","третий","третья","третью",
        "четвертый","четвертую","пятый","шестой","седьмой","восьмой","девятый","десятый",
        // ---- particles / question & guard words (нужны для negation/question guard!) ----
        "на","до","в","и","с","у","за","по","не","о","про","или","без","при","надо","нет","да",
        "мне","меня","мы","я","нас","это","эту","этой","эта","ли","было","очень",
        "пожалуйста","слушай","давай","сколько","где","когда","как","что","почему","зачем","можно",
        "хочу","хочется","нужно","нужна","какой","какая","стоит","лучше","такое","значит","менять",
        "работает","пользоваться","опасен","опасно","вчера","забыл","оставил","потерял","соседа",
        "погода","погоду","расскажи","объясни",
        // народные формулировки (обдув в рот, продув пердака и родня)
        "рот","морду","морда","харю","пердак","пердака","жопу","жопы","жопа","булки","булок",
        "пятую","пятой","точки","дубак","колотун","холодрыга","сауна","пекло","духота","бане",
        // ---- слова из tests.tsv, недостававшие после переезда словаря в VoskBridge (2026-09-03) ----
        "автоматические","автомобиле","амбиентную","аудиокнигу","бензобак","быстро","веди","вокзала","врубай","вспотел",
        "вызови","говори","деактивируй","дистанция","для","дует","дхо","едь","жару","жесткая",
        "заглуши","задний","заедем","зажги","запарился","заряди","зарядке","зафиксируй","звякни","избранные",
        "капли","ко","кондишку","контроль","крыше","крышу","лампочка","лампочку","лампы","машине",
        "машиной","морозилку","музон","музыки","мягкая","навигация","наклони","направь","натопи","неоновую",
        "огни","освещение","остуди","отвези","откинь","отодвинь","отопление","панорамную","парковки","паркуйся",
        "пассажирский","переверни","перегорела","передний","переключись","поближе","поддай","поедем","полную","положение",
        "полоса","полчаса","помассируй","понравилась","попогрейку","порт","порядок","послушать","потуши","правый",
        "придвинь","продрог","продув","рядом","салонный","свежего","светлую","сильно","сильное","скинь",
        "скриншот","слабо","слишком","сломался","случайный","смахни","сна","снимок","со","совсем",
        "сократи","спину","спины","срочно","такси","тачку","тело","темную","тепла","той",
        "триста","туманки","тут","форточки","форточку","ходовые","холодос","цветовая","цели","через",
        "эконом",
        // ---- словоформы под стемы ru2zh без тест-фраз ----
        "авторежим","везде","ветер","вперемешку","всю","выгони","выеду","грудь","двигатель","жопогрейку","заскочим","ионизацию","катушку","колонки","корпус","медиа","нуля","обогреватель","отруби","парилка","пододвинь","поезжай","помощь","послушаем","пригаси","пятая","разбил","резко","скорее","там","торс","улице","эвакуатор","эмбиент","энергосбережение",
        "[unk]"
    };

    /** GRAMMAR_WORDS as a fast lookup set (built once), for fuzzyFix — excludes the "[unk]" token. */
    private static java.util.Set<String> grammarSet() {
        java.util.Set<String> g = grammarSet;
        if (g == null) {
            g = new java.util.HashSet<>();
            for (String w : GRAMMAR_WORDS) if (!w.equals("[unk]")) g.add(w);
            grammarSet = g;
        }
        return g;
    }

    /** Nudge near-miss ASR words back to the vetted command vocabulary so ru2zh's contains()-stems
     *  match. Open-vocab ASR sometimes mis-hears a term by a letter or two ("кондицанер"); we replace
     *  only when a single close candidate exists (edit distance within ~1 per 4 chars), leaving normal
     *  words, names and destinations untouched. Used as a RESCUE when the raw text fails (see chooseQuery). */
    static String fuzzyFix(String phrase) {
        if (phrase == null || phrase.isEmpty()) return phrase;
        java.util.Set<String> voc = grammarSet();
        String[] toks = phrase.split("\\s+");
        boolean changed = false;
        for (int i = 0; i < toks.length; i++) {
            String w = toks[i];
            if (w.length() < 4 || voc.contains(w)) continue;      // short or already valid -> keep
            int budget = Math.max(1, w.length() / 4);   // len/4 — проверено: len/3 портит имена («андрею»->«адрес») и тянет к ближнему чужому слову
            String best = null; int bestD = budget + 1;
            for (String c : voc) {
                if (Math.abs(c.length() - w.length()) > budget) continue;
                int d = lev(w, c, budget);
                if (d < bestD) { bestD = d; best = c; if (d == 0) break; }
            }
            if (best != null && bestD <= budget) { toks[i] = best; changed = true; }
        }
        return changed ? String.join(" ", toks) : phrase;
    }

    /** Bounded Levenshtein: exact distance, or budget+1 once it provably exceeds budget. */
    private static int lev(String a, String b, int budget) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i; int rowMin = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                if (cur[j] < rowMin) rowMin = cur[j];
            }
            if (rowMin > budget) return budget + 1;               // early exit — can't get under budget
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        return prev[m];
    }

    /** Choose the phrase to act on from the single GigaAM hypothesis: the RAW text if ru2zh understands
     *  it (keeps names / free-text intact), else the FUZZY-corrected text if that is understood (rescue),
     *  else the fuzzy-cleaned text — which falls through to the offline stub / chat. */
    static String chooseQuery(String text) {
        if (text == null || text.isEmpty()) return "";
        // SAFETY: negation/question («не закрывай окно», «как работает…») -> whole phrase to chat,
        // don't let fuzzyFix strip the «не» and turn it into a command.
        if (isGuarded(text.toLowerCase().replace('ё', 'е'))) return text;
        if (ru2zh(text) != null) return text;                          // understood as-is
        String fx = fuzzyFix(text);
        if (!fx.equals(text) && ru2zh(fx) != null) return fx;          // fuzzy rescue
        return fuzzyFix(text);
    }

    public static void init(final Context ctx) {
        appCtx = ctx.getApplicationContext();
        Log.i(TAG, NOTICE);   // emit legal notice (also anchors the string into the dex)
        try { if (!com.stand.core.Guard.verify()) Log.w(TAG, com.stand.core.Guard.LICENSE); } catch (Throwable ignored) {}
        // VoiceApp.onCreate runs in EVERY process (main, :tts, :voiceprint), so init runs 3×. Load each
        // heavy model only in the process that actually uses it — otherwise GigaAM (~224 MB) and TeraTTS
        // (~300 MB) get loaded 3 times (measured: :voiceprint ballooned to ~686 MB for nothing).
        boolean mainProc = isMainProcess();
        boolean ttsProc  = isTtsProcess();
        if (mainProc) { ensureTtsRegistered(); ensureCustomWakeword(); }  // self-install once (shared file/setting)
        // TeraTTS is loaded ONLY in :tts (PiperCaTts synthesizes there). main speaks via GlobalTtsClient,
        // which routes to the :tts engine, so main needs no ~300 MB Tera copy of its own.
        if (ttsProc) { try { com.stand.tts.TeraTts.init(appCtx); } catch (Throwable ignored) {} }
        // GigaAM ASR: the audio-callback feed (SrBaseSession) only runs in the main process.
        if (mainProc) {
            try { com.stand.asr.GigaAsr.init(appCtx); } catch (Throwable t) { Log.e(TAG, "gigaam init", t); }
            new Thread(new Runnable() { public void run() {
                try {   // prime backend dicts + status
                    try { Thread.sleep(4000); } catch (Throwable ignored) {}
                    pushDicts();
                    pushStatus();
                } catch (Throwable t) { Log.e(TAG, "init", t); }
            }}).start();
        }
    }

    /** Called from SrBaseSession ASR-data callback. session=this (unused); wp=phase; inst=SR instance.
     *  ASR engine is GigaAM-v3 (offline CTC) — the whole utterance is decoded once at wp==3. */
    public static void feed(Object session, int nm, int dt, long wp, byte[] pcm, int inst) {
        feedGigaam(session, wp, pcm);
    }

    /** GigaAM-v3 path: accumulate the utterance (wp==1..3) and decode once at the end (offline CTC).
     *  No streaming/partials — the stock SR gives clean start/end, so no VAD is needed. */
    private static void feedGigaam(Object session, long wp, byte[] pcm) {
        try {
            if (wp == 1) { com.stand.asr.GigaAsr.reset(); lastText = ""; }
            if (pcm != null && pcm.length > 0) com.stand.asr.GigaAsr.accept(pcm, pcm.length);
            if (wp == 3) {
                wakeZone = currentDirect(session, wakeZone); // which seat spoke
                String text = com.stand.asr.GigaAsr.finish();
                if (text != null && !text.isEmpty()) Log.i(TAG, "RU ASR (GigaAM): " + text);
                String query = chooseQuery(text == null ? "" : text); // raw, then fuzzyFix
                if (query != null && !query.isEmpty()) {
                    lastText = query;   // so swap()/CloudNlu surface our RU text, never the native Chinese
                    Log.i(TAG, "RU ASR final (zone " + wakeZone + "): " + query);
                    showOnScreen(cap(query), TYPE_NLP);
                    handlePhraseZh(query); // ru2zh → stock NLU pipeline
                }
            }
        } catch (Throwable t) { Log.e(TAG, "feedGigaam", t); }
    }

    /** Speak text via the assistant's TTS engine. */
    private static void speak(String text) {
        try {
            Class<?> tc = Class.forName("com.changan.speech.tts.GlobalTtsClient");
            Object tts = tc.getMethod("getInstance").invoke(null);
            tc.getMethod("startPlayAuto", String.class).invoke(tts, text);
        } catch (Throwable t) { Log.e(TAG, "speak", t); }
    }

    // Assistant text-bar states (PgsSwitcherView `type`), matching the stock voice UI:
    static final int TYPE_PGS = 1;      // live dictation (partial ASR)
    static final int TYPE_NLP = 2;      // recognized command echo
    static final int TYPE_GUIDE = 3;    // idle rotating hints
    static final int TYPE_FEEDBACK = 4; // answer / reply message
    static final int TYPE_CLEAR = 9;    // clear the bar

    /** Drive the launcher's assistant text-bar (PgsView/PgsSwitcherView) with the stock
     *  platform broadcast. `type` selects the visual state (see TYPE_* above); the stock
     *  voice UI uses the very same Intent (NotifierUtil.notifyTextChanged). */
    static void showOnScreen(String text, int type) {
        try {
            if (appCtx == null || text == null || text.isEmpty()) return;
            Intent it = new Intent("com.incall.action.UPDATE_TEXT");
            it.putExtra("text", text);
            it.putExtra("type", type);
            it.putExtra("level", 1);            // LEVEL_HIGH
            if (type == TYPE_GUIDE) it.putExtra("scroll", true);
            it.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            appCtx.sendBroadcast(it);
        } catch (Throwable t) { Log.e(TAG, "showOnScreen", t); }
    }

    // ===================== System-wide ZH→RU TTS interception =====================
    // Hooked into TtsPlayer.start(role, text, sid, bool, IPlayerListener) (classes5). Any Chinese
    // text the system hands to TTS is intercepted here: translate ZH→RU and speak it with our Piper
    // voice, driving the listener callbacks so the stock dialog flow continues. Unknown text →
    // return false → stock Mandarin TTS plays as fallback (low risk).

    private static boolean hasCJK(String s) { for (int i=0;i<s.length();i++){char c=s.charAt(i); if(c>=0x4E00&&c<=0x9FFF) return true;} return false; }
    private static boolean hasCyrillic(String s) { for (int i=0;i<s.length();i++){char c=s.charAt(i); if(c>=0x0400&&c<=0x04FF) return true;} return false; }

    /** Text → Russian for the native TTS engine (PiperCaTts). Strips prosody tags ([se55], [w0]);
     *  MIXED overlay text (mostly Russian with a stray Chinese word like «громкость 语音 уменьшена»)
     *  keeps the Russian and cleans the CJK inline; a PURE Chinese tip goes through zh2ru; latin/digits
     *  pass as-is. Returns null only when nothing speakable remains. */
    public static String ttsTextToRu(String text) {
        if (text == null) return null;
        String s = text.replaceAll("\\[[^\\]]*\\]", "").trim();   // drop [se55]/[w0]… prosody tags
        if (s.isEmpty()) return null;
        String out;
        if (hasCyrillic(s)) {                                      // overlay Russian (maybe + stray CJK)
            if (hasCJK(s)) s = fixInlineZh(s);
            out = s.trim().isEmpty() ? null : s.trim();
        } else if (hasCJK(s)) {
            out = zh2ru(s);                                        // pure Chinese tip → fixed table
            if (out == null && CLOUD_MT) {                         // free-form cloud answer → MyMemory ZH→RU
                String mt = Translate.zhToRu(s);
                if (mt != null && !mt.isEmpty()) out = mt;
            }
            if (out == null) {                                    // unknown response: never leave CJK on screen/TTS
                String f = fixInlineZh(s);
                out = f.isEmpty() ? null : f;
            }
        } else {
            // Stock NLG sometimes confirms with a bare Latin "OK" — unchanged, its cache key stays the
            // same → the stock Chinese prefab ("OK" in the doudou voice) plays, bypassing our engine.
            // Rewrite it to a RU ack so the text changes (cache miss → our engine synthesizes it).
            String core = s.toLowerCase().replaceAll("[^a-zа-яё]", "");
            if (core.equals("ok") || core.equals("okay")) out = "Хорошо";
            else out = s;                                          // other latin / digits only
        }
        Log.i(TAG, "ttsTextToRu: [" + text + "] -> [" + out + "]");
        return out;
    }

    /** System-wide TtsPlayer.start rewrite: return the text with any Chinese turned into Russian
     *  (never null — falls back to the original), so no CJK is ever spoken/shown, whatever engine plays it. */
    public static String ttsRewrite(String text) {
        try {
            // On a no-answer/timeout the stock voices the CURRENT rotating guide word ("you can say X")
            // via TtsPlayer.start — those are our RU_HINTS, meant to be DISPLAYED, not spoken. Suppress
            // by rewriting to empty (both our engine and the stock cache then play nothing).
            if (isHintText(text)) { Log.i(TAG, "ttsRewrite: suppress guide-word voicing: " + text); return ""; }
            String r = ttsTextToRu(text); return (r != null && !r.isEmpty()) ? r : text;
        } catch (Throwable t) { return text; }
    }

    private static java.util.Set<String> HINT_SET;
    /** True if the TTS text is one of the rotating widget hints (RU_HINTS) — those must never be voiced. */
    private static boolean isHintText(String text) {
        if (text == null) return false;
        String s = text.replaceAll("\\[[^\\]]*\\]", "").trim().toLowerCase();   // drop [se55]/[w0] prosody
        if (s.isEmpty()) return false;
        if (HINT_SET == null) {
            java.util.HashSet<String> set = new java.util.HashSet<String>();
            for (String h : RU_HINTS) set.add(h.trim().toLowerCase());
            HINT_SET = set;
        }
        return HINT_SET.contains(s);
    }

    /** Clean stray Chinese words embedded in otherwise-Russian overlay text: map the common ones,
     *  drop the rest, collapse spaces. */
    private static String fixInlineZh(String s) {
        // common NLG fragments (longer forms first) so unmapped responses still read as Russian
        s = s.replace("好的", "Хорошо").replace("已为您", "").replace("已经", "").replace("为您", "")
             .replace("已调亮", "ярче").replace("已调暗", "темнее").replace("调亮", "ярче").replace("调暗", "темнее")
             .replace("已调到", "установлено ").replace("已调", "").replace("已打开", "включено").replace("已关闭", "выключено")
             .replace("已开启", "включено").replace("中控屏", "экран").replace("屏幕", "экран").replace("亮度", "яркость")
             .replace("语音", "").replace("音量", "громкость").replace("温度", "температура")
             .replace("空调", "климат").replace("座椅", "сиденье").replace("车窗", "окно");
        s = s.replaceAll("[\\u4E00-\\u9FFF]+", "");               // drop any remaining CJK
        return s.replaceAll("\\s{2,}", " ").replaceAll("\\s+([,.:;!?])", "$1").trim();
    }

    /** Intercept every text handed to the stock TTS. The ru.lang.* overlays already RUSSIFY the NLG
     *  text, so most of it is Cyrillic already — we just voice it with our Piper RU engine instead of
     *  the Mandarin iFlytek one (which mangles Russian). Residual Chinese → zh2ru table.
     *  @return true if we handled it (Piper + callbacks); false → let stock TTS play it. */
    public static boolean onTtsText(String text, final Object listener) {
        try {
            if (text == null || text.trim().isEmpty()) return false;
            Log.i(TAG, "TTS text in: " + text);
            String ru;
            if (hasCJK(text)) {                       // still Chinese (not overlaid) → translate
                ru = zh2ru(text);
                if (ru == null) return false;         // unknown Chinese → stock plays it
                Log.i(TAG, "TTS ZH->RU: [" + text + "] -> " + ru);
            } else if (hasCyrillic(text)) {           // already Russian (overlays) → voice with Piper
                ru = text;
            } else {
                return false;                          // latin/digits only → let stock handle
            }
            // NB: do NOT push the answer to the widget here — the stock UI already renders it (the
            // russified NLG text). A duplicate showOnScreen leaves the previous answer lingering and
            // it flashes for a frame at the next dictation. We only replace the VOICE, not the UI.
            callListener(listener, "onStartState", int.class, Integer.valueOf(0));
            callListener(listener, "onPlayBegin", String.class, ru);
            com.stand.tts.TeraTts.speak(ru, new Runnable() { public void run() {
                callListener(listener, "onPlayComplete", null, null);
            }});
            return true;
        } catch (Throwable t) { Log.e(TAG, "onTtsText", t); return false; }
    }

    private static void callListener(Object l, String method, Class<?> sig, Object arg) {
        if (l == null) return;
        try {
            if (sig == null) l.getClass().getMethod(method).invoke(l);
            else l.getClass().getMethod(method, sig).invoke(l, arg);
        } catch (Throwable t) { Log.e(TAG, "callListener " + method, t); }
    }

    /** Wakeup / sleep / reject / easter-egg TIP text (comes from GlobalTtsClient.startPlayAuto, i.e.
     *  the one-shot voiceserver Mandarin synth — NOT the TtsPlayer.start response path). Voice it in
     *  Russian with Piper instead. @return true if we spoke it (stock skips); false → stock plays it. */
    public static boolean onTipText(String zh) {
        // Tip voicing DISABLED. These one-shot tips (reject "try saying…"/easter-egg suggestions from
        // GlobalTtsClient) echo the rotating widget hints and were being spoken on failed/offline
        // queries. Return true to suppress them entirely (our voice AND the stock Mandarin tip) → silence.
        // Wakeup/sleep greetings are unaffected: they go through the TTS engine (TtsPlayer), not here.
        return true;
    }

    /** Random tip array (reject/egg via startPlayAutoRandom) — DISABLED, suppressed like onTipText. */
    public static boolean onTipRandom(String[] arr) { return true; }

    /** ZH→RU translation for TTS. Strips prosody tags ([w0]) and a leading seat-address prefix
     *  (主驾/副驾/…), then delegates to zh2ruCore. null if unknown (grow from "TTS text in:" logs). */
    static String zh2ru(String zh) {
        if (zh == null) return null;
        String s = zh.trim().replaceAll("\\[[a-zA-Z]\\d+\\]", "");   // drop prosody tags like [w0]
        // wakeup tips prepend a seat address; translate it separately and strip it off the core
        String addr = null;
        // Full seat address incl. driver «Водитель» — TeraTTS (ru_f2) voices the soft sign fine
        // (unlike the old irina), so we greet each seat by name, e.g. "Водитель, я слушаю".
        String[][] zones = {{"主驾","Водитель"},{"副驾","Пассажир"},{"左后","Слева сзади"},
                            {"右后","Справа сзади"},{"中间","По центру"},{"后排","Сзади"}};
        for (String[] z : zones) if (s.startsWith(z[0])) { addr = z[1]; s = s.substring(z[0].length()); break; }
        s = s.replaceAll("^[，、,。~\\s]+", "").replaceAll("[，。~\\s]+$", "");   // trim residual punct
        // Stock refusals "X不支持语音控制" ("X is not voice-controllable") — e.g. door lock is blocked by
        // Changan at the DM level (controlDoorLockTask rejects 锁车). Voice a clear RU reason, not silence.
        if (s.contains("不支持语音控制")) {
            String what = s.replace("不支持语音控制", "");
            String ru = (what.contains("车门锁") || what.contains("门锁") || what.contains("车门")) ? "Блокировка дверей"
                      : what.contains("车窗") ? "Управление окнами"
                      : what.contains("后备箱") || what.contains("尾门") ? "Багажник"
                      : "Эта функция";
            return ru + " голосом не поддерживается";
        }
        String core = zh2ruCore(s);
        if (core == null) return null;
        if (addr == null || addr.isEmpty()) return core;
        // Driver: keep the greeting terse — no seat name, and the listen prompt is just «Слушаю»
        // («Водитель, я слушаю» звучит слишком сложно). Other seats are still greeted by name.
        if ("Водитель".equals(addr)) return "Я слушаю".equals(core) ? "Слушаю" : core;
        return addr + ", " + Character.toLowerCase(core.charAt(0)) + core.substring(1);
    }

    /** Core ZH→RU table: assistant tips + command confirmations. Numbers kept as-is (RU TTS reads them). */
    static String zh2ruCore(String s) {
        switch (s) {
            // --- assistant tips: wakeup / barge-in / sleep / reject (learning) / easter-egg ---
            case "我在": case "在呢": return "Я слушаю";
            case "我来了": case "来了": case "我在这儿呢": return "Я здесь";
            case "有什么可以帮您": return "Чем могу помочь";
            case "请说": return "Я слушаю";
            case "你说": case "你先": case "你先说": return "Я слушаю";
            case "再见啦": return "До свидания";
            case "有事再喊我": return "Позовите, если что понадобится";
            case "下次再见": return "До встречи";
            case "我退下了": return "Отключаюсь";
            case "我能听见": return "Я вас слышу";
            case "不要再调戏我了": case "你们不要再调戏我了": return "Хватит меня дразнить";
            case "再这样我不理你啦": return "Ещё раз — и я перестану отвечать";
            case "哎呀，这可把我问住了": return "Ох, тут вы меня озадачили";
            case "这题现在不会，马上就学习": return "Пока не знаю, сейчас научусь";
            case "我还不会呢，马上恶补一下": return "Я ещё не умею, сейчас подтяну";
            case "已读，但绞尽脑汁也不知道怎么回": return "Прочитала, но пока не знаю, что ответить";
            case "我记下啦，或许过段时间我就能帮到你了": return "Записала, может позже смогу помочь";
            case "这个知识点我已经记在小本本上啦": return "Записала это себе в блокнотик";
            case "这个问题暂时难住我啦~这就开启学习模式": return "Вопрос меня озадачил, включаю режим обучения";
            // --- command confirmations ---
            case "好的": case "好的。": return "Хорошо";
            case "已为您打开": case "已打开": return "Включаю";
            case "已为您关闭": case "已关闭": return "Выключаю";
            case "已经打开了哦": return "Уже включено";
            case "已经关闭了哦": return "Уже выключено";
            case "没有找到相关资源哦": return "Ничего не нашла";
            case "当前没有播放歌曲哦": return "Сейчас ничего не играет";
            case "请先驻车后再试": case "请先驻车后再试一下": return "Сначала остановите машину";
            case "咱们车没有这个功能哦": case "车辆无此功能呢": return "В этой машине нет такой функции";
        }
        // templated: "…温度…26…度" → temperature confirmation with the number
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(s);
        String num = m.find() ? m.group(1) : null;
        if (s.contains("温度") && num != null) return "Ставлю температуру " + num;
        if (s.contains("风") && num != null)   return "Ставлю обдув " + num;
        if (s.contains("座椅加热")) return "Включаю подогрев сиденья" + (num != null ? " " + num : "");
        if (s.contains("座椅通风")) return "Включаю вентиляцию сиденья" + (num != null ? " " + num : "");
        if (s.contains("车窗")) return s.contains("关") ? "Закрываю окно" : "Открываю окно";
        if (s.contains("空调")) return s.contains("关") ? "Выключаю климат" : "Включаю климат";
        if (s.contains("好的")) return "Готово";
        return null; // unknown → stock Chinese fallback
    }

    /** Subtitle of what's being spoken now (avatar/TTS channel), like the stock UI. */
    static void showTtsSubtitle(String text) {
        try {
            if (appCtx == null || text == null || text.isEmpty()) return;
            Intent it = new Intent("com.incall.action.TTS_CONTENT");
            it.putExtra("text", text);
            it.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            appCtx.sendBroadcast(it);
        } catch (Throwable t) { Log.e(TAG, "showTtsSubtitle", t); }
    }

    // Backend base — neutralized for the offline-only release (OFFLINE_ONLY=true → never contacted).
    // No personal address ships in the public build; set at deploy time if online is ever re-enabled.
    private static final String BACKEND   = "http://127.0.0.1:8080";
    private static final String CHAT_URL  = BACKEND + "/dubhe/dubhe-gateway/dialog-new";
    // Mirror Changan's AiBox uplink actions (aibox/AiBoxConst: loadStatus/loadDicts were declared
    // but never wired in stock firmware) — we implement them ourselves so our backend/LLM gets
    // live car context the stock cloud never receives. See memory [[aiassist-llm]].
    private static final String LOAD_STATUS_URL = BACKEND + "/aibox/loadStatus";
    private static final String LOAD_DICTS_URL  = BACKEND + "/aibox/loadDicts";

    /** Non-command Russian -> our OpenAI backend -> speak the answer. */
    static void sendToCloud(final String text) {
        new Thread(new Runnable() { public void run() {
            try {
                // Attach live car status so our LLM has context the stock cloud never gets.
                String body = new JSONObject().put("query", text).put("carStatus", collectStatus()).toString();
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(CHAT_URL).openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(30000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.getOutputStream().write(body.getBytes("UTF-8"));
                int code = c.getResponseCode();
                java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[4096]; int n; while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String resp = bo.toString("UTF-8"); in.close();
                String answer = "", audio = "";
                try {
                    JSONObject d = new JSONObject(resp).getJSONObject("data");
                    answer = d.optString("answer", "");
                    audio = d.optString("audioPcm16k", "");
                } catch (Throwable ignored) {}
                Log.i(TAG, "LLM answer for [" + text + "]: " + answer);
                if (!answer.isEmpty()) {
                    showOnScreen(answer, TYPE_FEEDBACK); // answer message on the assistant widget
                    showTtsSubtitle(answer);             // spoken-subtitle channel (avatar/TTS)
                }
                if (!audio.isEmpty()) playPcm(audio);       // RU speech from our backend
                else if (!answer.isEmpty()) speak(answer);   // fallback: iFlytek TTS (Chinese voice)
            } catch (Throwable t) {
                Log.e(TAG, "sendToCloud", t);
            }
        }}).start();
    }

    // ============================== Car status -> our backend ==============================
    // Reachable read APIs inside SpeechAssistant (no extra car permission needed — SA already
    // reads these): com.incall.apps.speechassistant.third.CaCarManager.getInstance()
    //   .getIntProperty(propId, areaId) / isGearP() / isLocationHasPerson(zone);
    //   static fields com.incall.apps.voicebase.consts.common.ComVar.{sVin,sTuid,sCarModelItems}.
    // All via reflection (SA classes aren't on our compile classpath). Every read is best-effort:
    // a property SA lacks permission for just throws and is omitted from the snapshot.

    private static Object caCarMgr() {
        try {
            Class<?> c = Class.forName("com.incall.apps.speechassistant.third.CaCarManager");
            return c.getMethod("getInstance").invoke(null);
        } catch (Throwable t) { return null; }
    }
    /** Best-effort CarProperty int read via SA's CaCarManager. Returns null on error / sentinel. */
    private static Integer carInt(Object mgr, int propId, int areaId) {
        if (mgr == null) return null;
        try {
            Object v = mgr.getClass().getMethod("getIntProperty", int.class, int.class)
                          .invoke(mgr, propId, areaId);
            int i = ((Number) v).intValue();
            return i == -200 ? null : Integer.valueOf(i);   // -200 = CaCarManager.DEFAULT sentinel
        } catch (Throwable t) { return null; }
    }
    private static Boolean carBool(Object mgr, String method, Object... args) {
        if (mgr == null) return null;
        try {
            Class<?>[] sig = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) sig[i] = int.class; // all our uses take int
            Object v = mgr.getClass().getMethod(method, sig).invoke(mgr, args);
            return (Boolean) v;
        } catch (Throwable t) { return null; }
    }
    private static Boolean carBool0(Object mgr, String method) {
        if (mgr == null) return null;
        try { return (Boolean) mgr.getClass().getMethod(method).invoke(mgr); }
        catch (Throwable t) { return null; }
    }
    /** Read a static String field of ComVar (VIN / tuid / carModel). */
    private static String comVar(String field) {
        try {
            Class<?> c = Class.forName("com.incall.apps.voicebase.consts.common.ComVar");
            Object v = c.getField(field).get(null);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) { return ""; }
    }
    private static void putInt(JSONObject o, String k, Integer v) {
        try { if (v != null) o.put(k, v.intValue()); } catch (Throwable ignored) {}
    }

    /** Collect whatever car status is readable from SA into a compact JSON snapshot. */
    static JSONObject collectStatus() {
        JSONObject s = new JSONObject();
        try {
            String vin  = comVar("sVin");            if (!vin.isEmpty())  s.put("vin", vin);
            String tuid = comVar("sTuid");           if (!tuid.isEmpty()) s.put("tuid", tuid);
            String cm   = comVar("sCarModelItems");  if (!cm.isEmpty())   s.put("carModel", cm);
            s.put("ts", System.currentTimeMillis());
            Object m = caCarMgr();
            // Confirmed-readable in stock CaCarManager (gear / door locks / seat degrees):
            putInt(s, "gear",         carInt(m, 1082131456, 0));  // 1=P 2=R 3=N (>=4 D)
            putInt(s, "doorLockMain", carInt(m, 1100493568, 1));
            putInt(s, "doorLockFL",   carInt(m, 1100493568, 4));
            putInt(s, "doorLockFR",   carInt(m, 1100493568, 16));
            putInt(s, "doorLockRL",   carInt(m, 1100493568, 32));
            putInt(s, "doorLockRR",   carInt(m, 1100493568, 64));
            putInt(s, "seatDegMain",  carInt(m, 356518791, 1));
            putInt(s, "seatDegCo",    carInt(m, 356518791, 4));
            Boolean gp = carBool0(m, "isGearP"); if (gp != null) s.put("parked", gp.booleanValue());
            Boolean p1 = carBool(m, "isLocationHasPerson", Integer.valueOf(1));
            Boolean p2 = carBool(m, "isLocationHasPerson", Integer.valueOf(4));
            if (p1 != null) s.put("occDriver", p1.booleanValue());
            if (p2 != null) s.put("occPassenger", p2.booleanValue());
            // Best-effort comfort props (may be permission-gated -> silently omitted). Ids from doc 08.
            putInt(s, "acTempDriverRaw", carInt(m, 4218898, 0)); // decode: T = (raw+34)/2
            putInt(s, "acTempPsgRaw",    carInt(m, 4218901, 0));
            putInt(s, "acOn",            carInt(m, 4218905, 0));
            putInt(s, "acFan",           carInt(m, 4218889, 0));
            putInt(s, "winFL",           carInt(m, 4202844, 0)); // window position %
            putInt(s, "winFR",           carInt(m, 4202860, 0));
            putInt(s, "sunroof",         carInt(m, 4202908, 0));
            putInt(s, "seatHeatDriver",  carInt(m, 4212038, 0));
            putInt(s, "seatVentDriver",  carInt(m, 4212019, 0));
            if (s.has("acTempDriverRaw")) { int r = s.getInt("acTempDriverRaw"); if (r > 0) s.put("acTempDriverC", (r + 34) / 2.0); }
        } catch (Throwable t) { Log.e(TAG, "collectStatus", t); }
        return s;
    }

    /** Generic fire-and-forget JSON POST to our backend (background thread). */
    private static void postJson(final String url, final JSONObject body, final String tag) {
        new Thread(new Runnable() { public void run() {
            try {
                Log.i(TAG, tag + " body " + body);   // observable even if backend is unreachable
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setConnectTimeout(4000); c.setReadTimeout(8000);
                c.setRequestMethod("POST"); c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.getOutputStream().write(body.toString().getBytes("UTF-8"));
                int code = c.getResponseCode();
                Log.i(TAG, tag + " -> " + url + " (" + code + ") " + body);
                try { c.getInputStream().close(); } catch (Throwable ignored) {}
            } catch (Throwable t) { Log.e(TAG, tag + " post", t); }
        }}).start();
    }

    /** Push current car-status snapshot to our backend (loadStatus endpoint). */
    public static void pushStatus() { postJson(LOAD_STATUS_URL, collectStatus(), "loadStatus"); }

    /** NEW PATH (ru2zh): inject Chinese TEXT straight into the stock NLU entry
     *  (NluManager.onFinalAsrResult) — the FULL native pipeline runs: local NLU → arbitration →
     *  (offline) actuation OR (online) cloud AI, + NLG + TTS + widget. Rides all stock options and
     *  future OTA updates. zone = detected speaking seat (wakeZone). See ru2zh/docs/00-approach.md. */
    public static void injectZh(final String zh) { injectZh(zh, true); }

    /** confident=true: local NLU is authoritative → arbitration resolves at once (known ru2zh command).
     *  confident=false: local is tentative → arbitration WAITS for the cloud result (free-form phrase
     *  that only the cloud can answer — weather/chat). Passing true made arbitration close on a local
     *  UNKNOWN before the ~1.3s-later cloud reply could win. */
    public static void injectZh(final String zh, final boolean confident) {
        if (!isMainProcess()) return;   // onFinalAsrResult only valid in the main SA process
        new Thread(new Runnable() { public void run() {
            try {
                Class<?> nmC = Class.forName("com.incall.apps.speechassistant.nlu.NluManager");
                Object nm = nmC.getMethod("getInstance").invoke(null);
                // reqId MUST start with "vosk" so the patched onArbitrationResult guard keeps it
                // (the guard drops non-"vosk" results — the native Chinese SR path).
                String reqId = "voskzh-" + System.currentTimeMillis();
                forceWakeIfAsleep(wakeZone);  // injected command must execute past the free-wake gate
                try { nmC.getMethod("updateSidTimestamp", String.class).invoke(nm, reqId); } catch (Throwable ignored) {}
                // signature is (requestId, direction, asrText, confidence) — NOT (asrText, dir, reqId, ...)
                nmC.getMethod("onFinalAsrResult", String.class, int.class, String.class, boolean.class)
                   .invoke(nm, reqId, wakeZone, zh, confident);
                Log.i(TAG, "injectZh sent (zone " + wakeZone + ") reqId=" + reqId + ": " + zh);
                // Also consult the REAL Changan cloud (dialog-new) with the SAME reqId (already
                // registered via updateSidTimestamp) so the cloud result correlates and arbitration
                // accepts it: cloud carControl executes on the car, and the Chinese answer
                // (data.dmResults[].tts) flows back through the stock NLG/TTS pipeline. Needs a
                // NATIVE_CLOUD build (endpoints not redirected). See [[dubhe-response-format]].
                if (CLOUD_INJECT) {
                    try {
                        Class<?> cc = Class.forName("com.incall.apps.speechassistant.nlu.CloudNlu");
                        Object cn = cc.getConstructor().newInstance();
                        cc.getMethod("sendPostRequest", int.class, String.class, String.class)
                          .invoke(cn, wakeZone, reqId, zh);
                        Log.i(TAG, "injectZh -> also cloud (reqId=" + reqId + ")");
                    } catch (Throwable t) { Log.e(TAG, "injectZh cloud", t); }
                }
            } catch (Throwable t) { Log.e(TAG, "injectZh", t); }
        }}).start();
    }

    /** DIAGNOSTIC: send a Chinese query straight to the REAL Changan Dubhe cloud (device-signed) and
     *  log the raw response. onFinalAsrResult only runs LOCAL NLU — the cloud is consulted from
     *  SrBaseSession, which our text injection bypasses. Here we call CloudNlu.sendPostRequest
     *  directly (public, same call SrBaseSession makes). The response is logged by CloudNlu at level i
     *  ("get nlu success: <json>"). reqId starts with "vosk" so onCloudNluResult survives our guard.
     *  Needs a NATIVE_CLOUD build (endpoints not redirected to 127.0.0.1). */
    public static void dubheTest(final String zh) {
        new Thread(new Runnable() { public void run() {
            try {
                Class<?> c = Class.forName("com.incall.apps.speechassistant.nlu.CloudNlu");
                Object cn = c.getConstructor().newInstance();
                try { c.getMethod("init").invoke(cn); } catch (Throwable ignored) {}  // location listener (optional)
                String reqId = "voskzh-" + System.currentTimeMillis();
                Log.i(TAG, "dubheTest -> cloud (zone " + wakeZone + ") reqId=" + reqId + ": " + zh);
                c.getMethod("sendPostRequest", int.class, String.class, String.class)
                 .invoke(cn, wakeZone, reqId, zh);
            } catch (Throwable t) { Log.e(TAG, "dubheTest", t); }
        }}).start();
    }

    /** Self-contained cloud ask: build the SAME signed dialog-new request the stock CloudNlu makes
     *  (via GateWayUtils.buildJsonRequest — device signature), execute it ourselves, and handle the
     *  response directly instead of relying on the stock arbitration/DM (which resolves on the local
     *  UNKNOWN before the ~1.3s cloud reply and never speaks the answer). We pull data.dmResults[].tts,
     *  turn it Russian (fixed zh2ru table → MyMemory fallback), and speak+show it. carControl in
     *  nluResults is logged (known commands already execute via the local ru2zh path). Needs a
     *  NATIVE_CLOUD build. See [[dubhe-response-format]]. */
    public static void cloudAsk(final String zh) {
        if (!isMainProcess()) return;   // one signed call + one TTS, in the process where TtsClient is ready
        new Thread(new Runnable() { public void run() {
            try {
                String vin = refStaticStr("com.incall.apps.voicebase.consts.common.ComVar", "sVin");
                String tuid = refStaticStr("com.incall.apps.voicebase.consts.common.ComVar", "sTuid");
                String carModel = refStaticStr("com.incall.apps.voicebase.consts.common.ComVar", "sCarModelItems");
                org.json.JSONObject j = new org.json.JSONObject();
                if (carModel != null) j.put("carModel", carModel);
                j.put("zoneId", wakeZone);
                if (vin != null) j.put("vin", vin);
                j.put("requestId", "voskask-" + System.currentTimeMillis());
                if (tuid != null) j.put("tuid", tuid);
                j.put("query", zh);
                j.put("isWakeFree", false);

                Class<?> cfg = Class.forName("com.incall.apps.voicebase.util.CommonConfig");
                String url = (String) cfg.getMethod("getDialogUrl").invoke(null);

                Class<?> gw = Class.forName("com.incall.apps.voicebase.util.GateWayUtils");
                Class<?> reqCls = Class.forName("okhttp3.Request");
                Object req = gw.getMethod("buildJsonRequest", String.class, String.class, String.class, boolean.class)
                               .invoke(null, url, j.toString(), "POST", true);

                Class<?> clientCls = Class.forName("okhttp3.OkHttpClient");
                Object client = clientCls.getConstructor().newInstance();
                Object call = clientCls.getMethod("newCall", reqCls).invoke(client, req);
                Object resp = call.getClass().getMethod("execute").invoke(call);
                Object rbody = resp.getClass().getMethod("body").invoke(resp);
                String body = rbody == null ? "" : (String) rbody.getClass().getMethod("string").invoke(rbody);
                try { resp.getClass().getMethod("close").invoke(resp); } catch (Throwable ignored) {}
                Log.i(TAG, "cloudAsk raw: " + body);

                org.json.JSONObject data = new org.json.JSONObject(body).optJSONObject("data");
                if (data == null) return;
                // 1) spoken answer from dmResults[].tts
                org.json.JSONArray dm = data.optJSONArray("dmResults");
                String tts = "";
                if (dm != null) for (int i = 0; i < dm.length(); i++) {
                    String t = dm.optJSONObject(i) == null ? "" : dm.optJSONObject(i).optString("tts", "");
                    if (t != null && !t.isEmpty()) { tts = t; break; }
                }
                if (!tts.isEmpty()) {
                    // Free-form cloud answer -> MyMemory directly. Do NOT use zh2ru here: that table is
                    // for SHORT stock command-confirmations and greedily matches substrings (e.g. it turned
                    // the Shanghai weather "…温度25度到30度…" into "Ставлю температуру 25").
                    String ru = Translate.zhToRu(tts);
                    if (ru == null || ru.isEmpty()) ru = tts;
                    Log.i(TAG, "cloudAsk answer: [" + tts + "] -> [" + ru + "]");
                    showTtsSubtitle(ru);
                    showOnScreen(ru, TYPE_FEEDBACK);
                    speak(ru);   // GlobalTtsClient -> :tts (PiperCaTts/Tera); keeps Tera out of main
                } else {
                    // 2) no synchronous answer (e.g. general_qa LLM streams natively) — log the routing
                    org.json.JSONArray nr = data.optJSONArray("nluResults");
                    String dom = (nr != null && nr.length() > 0) ? nr.optJSONObject(0).optString("domain", "") : "";
                    String intent = (nr != null && nr.length() > 0) ? nr.optJSONObject(0).optString("intent", "") : "";
                    Log.i(TAG, "cloudAsk no tts; domain=" + dom + " intent=" + intent + " agent=" + data.optString("agentName", ""));
                }
            } catch (Throwable t) { Log.e(TAG, "cloudAsk", t); }
        }}).start();
    }

    /** Best-effort read of a static String field via reflection (null on any error). */
    private static String refStaticStr(String cls, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            Object v = f.get(null);
            return v == null ? null : v.toString();
        } catch (Throwable t) { return null; }
    }

    /** Ensure the assistant counts as "awake" (VoiceStateCache.sVwState != 0) so FreeWakeManager
     *  lets a non-whitelisted command through. Only forces when currently asleep (0) — a real knob
     *  wake already set it, so we don't disturb that. state = wake direction (1..5). */
    private static void forceWakeIfAsleep(int zone) {
        try {
            Class<?> c = Class.forName("com.incall.apps.voicebase.consts.common.VoiceStateCache");
            java.lang.reflect.Field f = c.getDeclaredField("sVwState");
            f.setAccessible(true);
            java.util.concurrent.atomic.AtomicInteger ai = (java.util.concurrent.atomic.AtomicInteger) f.get(null);
            if (ai != null && ai.get() == 0) {
                ai.set((zone >= 1 && zone <= 5) ? zone : 1);
                Log.i(TAG, "forceWake: sVwState set to " + ai.get());
            }
        } catch (Throwable t) { Log.e(TAG, "forceWake", t); }
    }

    /** True only in the app's main process (secondary processes lack a ready NLU pipeline). */
    private static boolean isMainProcess() {
        try {
            String pn = android.app.Application.getProcessName();  // API 28+
            return pn == null || !pn.contains(":");
        } catch (Throwable t) { return true; }
    }

    /** True in the dedicated TTS process (com.incall.apps.speechassistant:tts) where PiperCaTts runs. */
    private static boolean isTtsProcess() {
        try {
            String pn = android.app.Application.getProcessName();
            return pn != null && pn.endsWith(":tts");
        } catch (Throwable t) { return false; }
    }

    /** DIAGNOSTIC: read candidate propIds (csv, hex "0x.." or decimal) across common areaIds via SA's
     *  CaCarManager.getIntProperty and log every valid value. Lets us correlate SoaBridge (group 0x4)
     *  props — windows/locks/seat-heat — that the AOSP VHAL dump can't see. Before/after a manual
     *  control change, diff the "DIAG ..." log lines to find the propId that moved. */
    public static void diagDump(final String propsCsv) {
        new Thread(new Runnable() { public void run() {
            Object m = caCarMgr();
            if (m == null) { Log.e(TAG, "DIAG: no CaCarManager"); return; }
            int[] areas = {0, 1, 4, 16, 32, 64};
            int count = 0;
            for (String tok : propsCsv.split(",")) {
                tok = tok.trim(); if (tok.isEmpty()) continue;
                int pid;
                try {
                    boolean hex = tok.startsWith("0x") || tok.startsWith("0X");
                    pid = (int) Long.parseLong(hex ? tok.substring(2) : tok, hex ? 16 : 10);
                } catch (Throwable t) { continue; }
                for (int a : areas) {
                    Integer v = carInt(m, pid, a);
                    if (v != null) { Log.i(TAG, String.format("DIAG 0x%08x a=%d v=%d", pid, a, v)); count++; }
                }
            }
            Log.i(TAG, "DIAG done: " + count + " values");
        }}).start();
    }

    /** Push personalized dictionaries (contacts / POIs / nicknames) so our backend LLM can match
     *  user vocabulary. Scaffold: id fields + empty lists for now; fill from phonebook / navi
     *  favorites / aimemory profiles when those read paths are wired (mirrors Changan loadDicts). */
    public static void pushDicts() {
        try {
            JSONObject d = new JSONObject();
            String vin = comVar("sVin");   if (!vin.isEmpty())  d.put("vin", vin);
            String tuid = comVar("sTuid"); if (!tuid.isEmpty()) d.put("tuid", tuid);
            d.put("contacts", new org.json.JSONArray());   // TODO: phone@SYNC_PHONE_CONTACT source
            d.put("pois", new org.json.JSONArray());       // TODO: navi favorites
            d.put("nicknames", new org.json.JSONArray());  // TODO: aimemory profiles
            postJson(LOAD_DICTS_URL, d, "loadDicts");
        } catch (Throwable t) { Log.e(TAG, "pushDicts", t); }
    }

    /** Play 16 kHz mono s16le PCM (base64) via AudioTrack — RU speech synthesized by our backend. */
    private static void playPcm(String b64) {
        try {
            byte[] pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            int sr = 16000;
            int min = android.media.AudioTrack.getMinBufferSize(sr,
                android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
            android.media.AudioTrack at = new android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC, sr,
                android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, pcm.length), android.media.AudioTrack.MODE_STREAM);
            at.play();
            at.write(pcm, 0, pcm.length);
            long ms = (long) (pcm.length / 2.0 / sr * 1000) + 300;
            Thread.sleep(ms);
            at.stop(); at.release();
            Log.i(TAG, "playPcm done (" + pcm.length + " bytes)");
        } catch (Throwable t) { Log.e(TAG, "playPcm", t); }
    }

    // ---- Localized rotating UI hints (replaces Chinese guide words) ----
    // Broad sample across the offline-capable families so the driver discovers the range.
    // Rotating on-screen suggestions ("вы можете сказать…"). Cover ALL function domains, mirroring
    // the stock guide_words.txt (40 phrases across climate/media/nav/system/modes/info). Every control
    // hint below was verified to resolve to a real ru2zh command (see ru2zh/translate-task tests); the
    // few info/chat ones (weather/what-to-wear) intentionally fall through to the cloud assistant.
    private static final String[] RU_HINTS = {
        // атрибуция (показывается в виджете, не озвучивается — см. isHintText)
        "Разработчик: Алексей Воронов", "Модификация распространяется бесплатно", "Версия 1.0.2",
        // климат
        "включи климат", "температура 22", "быстро охлади салон", "обдув на лицо и ноги",
        "включи обдув лобового", "включи рециркуляцию",
        // сиденья
        "подогрев сиденья водителя на 2", "вентиляция сиденья водителя", "массаж сиденья",
        "сдвинь сиденье вперёд",
        // окна / люк / зеркала
        "открой окно водителя", "оставь окно приоткрытым", "закрой все окна", "открой панораму",
        "сложи зеркала", "обогрев зеркал", "помой лобовое",
        // холодильник
        "открой холодильник", "включи холодильник", "холодильник на -5",
        // свет
        "включи ближний свет", "включи подсветку салона", "сделай подсветку ярче",
        "включи передний свет в салоне", "включи свет в багажнике",
        // медиа
        "включи музыку", "громче", "следующий трек",
        // экран / система
        "поверни экран горизонтально", "сделай экран ярче", "включи тёмную тему",
        // режимы
        "режим сна", "режим кинотеатра",
        // инфо
        "проверь давление в шинах", "сколько осталось заряда"
    };

    /** Read SrBaseSession.getCurrentDirect() on the session object (which seat woke SR). */
    private static int currentDirect(Object session, int fallback) {
        try {
            Object v = session.getClass().getMethod("getCurrentDirect").invoke(session);
            int d = ((Number) v).intValue();
            return (d >= 1 && d <= 5) ? d : fallback;
        } catch (Throwable t) { return fallback; }
    }
    /** SrBaseSession direction number -> our normalized zone code (1=driver,2=passenger,3/4/5=rear). */
    private static String zoneCode(int dir) {
        switch (dir) {
            case 1: return "DRIVER";
            case 2: return "PASSENGER";
            case 3: case 4: case 5: return "REAR";
            default: return "DRIVER";
        }
    }

    // Grade words -> normalized grade codes (as used by arbiConfig/dm: PLUS/MINUS/MIN/MAX).
    private static String gradeOf(String s) {
        if (s.contains("максим") || s.contains("на всю") || s.contains("полностью")) return "MAX";
        if (s.contains("миним")) return "MIN";
        if (s.contains("тепл") || s.contains("больш") || s.contains("выше") || s.contains("прибав")
            || s.contains("громч") || s.contains("ярче") || s.contains("сильн")) return "PLUS";
        if (s.contains("холод") || s.contains("мень") || s.contains("ниже") || s.contains("убав")
            || s.contains("тише") || s.contains("темнее") || s.contains("слаб")) return "MINUS";
        return "";
    }
    private static boolean isOn(String s) {
        return s.contains("включ") || s.contains("откр") || s.contains("подним") || s.contains("запус")
            || s.contains("вклчю") || s.contains("вкл ");
    }
    private static boolean isOff(String s) {
        return s.contains("выключ") || s.contains("отключ") || s.contains("выруб") || s.contains("закр")
            || s.contains("опусти") || s.contains("останов") || s.contains("выкл ");
    }
    public static Object ruGuideSleep() { return ruGuide(); }
    public static Object ruGuideSr()    { return ruGuide(); }
    /** Rotating idle hint — just the example phrase itself (no "Скажи:" prefix). */
    private static Object ruGuide() {
        try {
            String ex = cap(RU_HINTS[(int) ((System.currentTimeMillis() / 3000) % RU_HINTS.length)]);
            java.util.ArrayList<String> texts = new java.util.ArrayList<>();
            texts.add(ex);
            java.util.ArrayList<Integer> colors = new java.util.ArrayList<>();
            colors.add(2);
            Class<?> tc = Class.forName("com.incall.apps.speechassistant.guide.TextColorBean");
            return tc.getConstructor(java.util.ArrayList.class, java.util.ArrayList.class).newInstance(texts, colors);
        } catch (Throwable t) { Log.e(TAG, "ruGuide", t); return null; }
    }

    /** Route a recognized Russian phrase: known car command -> arbitration, else -> chat LLM.
     *  Shared by the live ASR path (feed wp==3) and the test trigger (StandNluReceiver `cmd`). */
    public static void handlePhrase(String query) {
        if (query == null || query.trim().isEmpty()) return;
        String cmd = mapCommand(query);
        if (cmd != null) { Log.i(TAG, "-> car command: " + cmd); execArbitration(cmd); }
        else if (OFFLINE_ONLY) {
            Log.i(TAG, "-> offline, unrecognized: " + query);
            showOnScreen("Не поняла команду", TYPE_FEEDBACK); // local reply; online chat not wired yet
        }
        else { Log.i(TAG, "-> chat: " + query); sendToCloud(query); }
    }


    // ==== EXTENDED ru2zh (v4: adapted for N-best/fuzzy): full 328-intent RU→ZH. One utt -> ONE ZH. ====
    /** Physical-actuation commands (doors, trunk, autopilot, parking, summon) are only injected
     *  when this is true. Off by default: the phrase is logged + a hint is shown instead. */
    private static final boolean ALLOW_UNSAFE = true;   // release: physical actuation enabled (doors/trunk/frunk/tailgates/autopilot/parking/summon)

    private static String unsafeGate(String zh) {
        if (ALLOW_UNSAFE) return zh;
        Log.w(TAG, "ru2zh: UNSAFE blocked (ALLOW_UNSAFE=false): " + zh);
        showOnScreen("Команда требует подтверждения", TYPE_FEEDBACK);
        return null;
    }

    // ---------------------------------------------------------------- zones (REPLACES VoskBridge versions)

    /** REPLACES zoneOf() in VoskBridge: adds rear-left / rear-right / front-row zones.
     *  The stock NLU distinguishes 主驾/副驾/前排/后排/后排左/后排右/所有 (see dm_cmd.json name lists).
     *  Order matters: rear+side combos BEFORE the bare "пассажир" check — «заднему правому
     *  пассажиру» must resolve to REAR_RIGHT, not front PASSENGER. "сзади" is matched explicitly
     *  ("задн" does not cover it), while "назад" must NOT trigger rear (seat-move command). */
    private static String zoneOf(String s) {
        boolean rear = s.contains("задн") || s.contains("сзади");
        if (rear && s.contains("прав")) return "REAR_RIGHT";
        if (rear && s.contains("лев")) return "REAR_LEFT";
        if (s.contains("за водителем")) return "REAR_LEFT";
        if (s.contains("за пассажиром")) return "REAR_RIGHT";
        if (rear) return "REAR";
        if (s.contains("все") || s.contains("всех") || s.contains("весь")) return "ALL";
        if (s.contains("пассажир")) return "PASSENGER";   // front-right seat (副驾)
        boolean front = s.contains("передн") || s.contains("спереди");
        if (front && s.contains("прав")) return "PASSENGER";  // "переднее правое"
        if (front && s.contains("лев")) return "DRIVER";
        if (front) return "FRONT";
        if (s.contains("водит")) return "DRIVER";
        // bare side words, lowest priority (LHD: левое = водительское, правое = пассажирское)
        if (s.contains("прав")) return "PASSENGER";
        if (s.contains("лев")) return "DRIVER";
        return "";
    }

    /** REPLACES zhZone() in VoskBridge: zone code -> Chinese zone word for command templates. */
    private static String zhZone(String code) {
        if ("DRIVER".equals(code)) return "主驾";
        if ("PASSENGER".equals(code)) return "副驾";
        if ("FRONT".equals(code)) return "前排";
        if ("REAR".equals(code)) return "后排";
        if ("REAR_LEFT".equals(code)) return "后排左";
        if ("REAR_RIGHT".equals(code)) return "后排右";
        if ("ALL".equals(code)) return "所有";
        return "";
    }

    // ---------------------------------------------------------------- new slot parsers

    /** OFF-verbs isOff() does not know: "погаси/потуши свет", "деактивируй", "отруби", "заглуши".
     *  ("гаси"/"туши" cover "гасите"/"тушите"; "притуши" = dim, handled earlier in zhLights.) */
    private static boolean offWordOf(String s) {
        return s.contains("погаси") || s.contains("потуши") || s.contains("деактив") || s.contains("отруби")
            || s.contains("заглуши") || s.contains("вырубай") || s.contains("убери запах")
            || ((s.contains("гаси") || s.contains("туши")) && !s.contains("притуши") && !s.contains("пригаси"));
    }

    /** "повысь/увеличь/прибавь" — relative-plus verbs gradeOf() does not know. */
    private static boolean plusWordOf(String s) {
        return s.contains("повы") || s.contains("увелич") || s.contains("подбав") || s.contains("прибав");
    }
    /** "понизь/уменьши/убавь" — relative-minus verbs gradeOf() does not know. */
    private static boolean minusWordOf(String s) {
        return s.contains("пониз") || s.contains("уменьш") || s.contains("убав");
    }

    /** Percent of window/roof opening: "на 30 процентов", "наполовину" -> 50. -1 if absent. */
    private static int percentOf(String s) {
        if (s.contains("наполовину") || s.contains("на половину") || s.contains("половин")) return 50;
        if (s.contains("процент") || s.contains("%")) {
            int n = numIn(s);
            if (n >= 1 && n <= 100) return n;
        }
        if (s.contains("на треть")) return 33;
        if (s.contains("на четверть")) return 25;
        return -1;
    }

    /** Ordinal / index: "первый".."десятый", "номер 3". -1 if absent. */
    private static int ordinalIn(String s) {
        if (s.contains("перв")) return 1;
        if (s.contains("втор")) return 2;
        if (s.contains("трет")) return 3;
        if (s.contains("четверт")) return 4;
        if (s.contains("пят") && !s.contains("пятнадцат")) return 5;
        if (s.contains("шест") && !s.contains("шестнадцат")) return 6;
        if (s.contains("седьм")) return 7;
        if (s.contains("восьм")) return 8;
        if (s.contains("девят") && !s.contains("девятнадцат")) return 9;
        if (s.contains("десят") && !s.contains("надцат")) return 10;
        if (s.contains("номер")) { int n = numIn(s); if (n > 0) return n; }
        return -1;
    }

    /** RU app name -> Chinese app name for 打开{app}. Empty if not a known app. */
    private static String zhAppOf(String s) {
        if (s.contains("музык")) return "音乐";
        if (s.contains("настройк")) return "设置";
        if (s.contains("камер")) return "相机";
        if (s.contains("телефон")) return "电话";
        if (s.contains("радио")) return "收音机";
        if (s.contains("браузер") || s.contains("интернет")) return "浏览器";
        if (s.contains("видео") && !s.contains("сними") && !s.contains("запиши")) return "视频";
        if (s.contains("карт") && !s.contains("карточк")) return "地图";
        if (s.contains("галере") || s.contains("альбом") || s.contains("фотограф")) return "相册";
        if (s.contains("магазин")) return "应用商店";
        if (s.contains("календар")) return "日历";
        return "";
    }

    /** RU POI category -> Chinese POI word for nearby search. Empty if none. */
    private static String zhPoiOf(String s) {
        if (s.contains("заправ") || s.contains("азс") || s.contains("бензин")) return "加油站";
        if (s.contains("зарядк") || s.contains("зарядн")) return "充电站";
        if (s.contains("парковк") || s.contains("стоянк")) return "停车场";
        if (s.contains("кафе") || s.contains("кофе")) return "咖啡店";
        if (s.contains("ресторан") || s.contains("поесть") || s.contains("еда")) return "餐厅";
        if (s.contains("туалет")) return "卫生间";
        if (s.contains("аптек")) return "药店";
        if (s.contains("больниц") || s.contains("госпитал")) return "医院";
        if (s.contains("супермаркет") || s.contains("магазин продукт")) return "超市";
        if (s.contains("банкомат")) return "ATM";
        if (s.contains("гостиниц") || s.contains("отел")) return "酒店";
        if (s.contains("мойк")) return "洗车店";
        return "";
    }

    /** Driving mode -> Chinese mode name. Empty if none. */
    private static String zhDriveModeOf(String s) {
        if (s.contains("спорт")) return "运动模式";
        if (s.contains("эко") || s.contains("экономичн")) return "经济模式";
        if (s.contains("комфорт")) return "舒适模式";
        if (s.contains("стандарт") || s.contains("обычн")) return "标准模式";
        if (s.contains("снег") || s.contains("снежн") || s.contains("зимн")) return "雪地模式";
        if (s.contains("бездорож") || s.contains("оффроуд") || s.contains("внедорож")) return "越野模式";
        return "";
    }

    /** Scenario / space mode (SET_SCENARIO_MODE) -> Chinese name from the dm catalog. */
    private static String zhScenarioOf(String s) {
        if (s.contains("кино") || s.contains("фильм")) return "影院模式";
        if (s.contains("кроват") || s.contains("спальн") || s.contains("для сна")) return "大床模式";
        if (s.contains("отдых") || s.contains("рассла")) return "休息空间";
        if (s.contains("кемпинг") || s.contains("палатк")) return "露营模式";
        if (s.contains("детск")) return "儿童模式";
        if (s.contains("караоке")) return "K歌房模式";
        if (s.contains("взбодр") || s.contains("бодрост")) return "提神模式";
        if (s.contains("укачив") || s.contains("тошн")) return "晕车缓解";
        if (s.contains("макияж") || s.contains("косметик")) return "化妆空间";
        return "";
    }

    /** Free text after a trigger word ("позвони маме" -> "маме"). Empty if nothing follows. */
    private static String tailAfter(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return "";
        String t = s.substring(i + key.length()).trim();
        // strip leading fillers (repeat until stable: "меня в аэропорт" -> "аэропорт")
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String f : new String[]{"на ", "в ", "к ", "до ", "мне ", "меня ", "нас ", "пожалуйста ", "быстрее ", "давай "}) {
                if (t.startsWith(f)) { t = t.substring(f.length()).trim(); changed = true; }
            }
        }
        return t;
    }

    // ---------------------------------------------------------------- main translator

    /**
     * RU→ZH: translate a recognized Russian command into ONE natural Chinese command that the stock
     * NLU understands, or null (→ offline stub / chat). Order matters: specific contexts (seats,
     * defrost) are checked before generic ones (temperature, volume); bare context words
     * ("да", "дальше", "назад") are matched last so they never shadow real commands.
     */
    /** Negation + question/narrative guard: true = the utterance must NOT actuate anything.
     *  Shared by ru2zh() and the N-best arbitration (ru2zhBest applies it to the TOP hypothesis
     *  so a lower alternative missing the "не" cannot bypass it). */
    /** «на единичку/двойку/троечку/четвёрку/пятёрку…» — числительные-существительные, которых numIn не знает.
     *  Используется как fallback для n в ru2zh(): обдув, подогрев/вентиляция сиденья, дворники и т.п. -1 если нет. */
    static int levelWordOf(String s) {
        if (s.contains("единичк") || s.contains("единиц")) return 1;
        if (s.contains("двойк") || s.contains("двоечк")) return 2;
        if (s.contains("тройк") || s.contains("троечк")) return 3;
        if (s.contains("четверк") || s.contains("четверочк")) return 4;
        if (s.contains("пятерк") || s.contains("пятерочк")) return 5;
        if (s.contains("шестерк") || s.contains("шестерочк")) return 6;
        if (s.contains("семерк") || s.contains("семерочк")) return 7;
        return -1;
    }

    static boolean isGuarded(String s) {
        // negation: "не закрывай окно" must not actuate anything -> chat/stub
        if (s.contains("не включ") || s.contains("не выключ") || s.contains("не откр") || s.contains("не закр")
            || s.contains("не вруб") || s.contains("не выруб") || s.contains("не отключ") || s.contains("не убир")
            || s.contains("не едь") || s.contains("не езжай") || s.contains("не гони"))
            return true;
        // question/narrative: "как пользоваться круизом", "почему окна потеют", "у меня дома…"
        // are chat, not commands — they must never actuate hardware
        return s.contains("как ") || s.contains("почему") || s.contains("зачем") || s.contains("что такое")
            || s.contains("что значит") || s.contains("что лучше") || s.contains("расскажи") || s.contains("объясни")
            || s.contains("можно ли") || s.contains("стоит ли") || s.contains("сколько стоит")
            || s.contains("у меня") || s.contains("у нас") || s.contains("у соседа")
            || s.contains("вчера") || s.contains("опасн") || s.contains("опасен")
            || s.contains("оставил") || s.contains("забыл") || s.contains("потерял")
            || s.contains("не работает") || s.contains("сломал") || s.contains("перегорел") || s.contains("разбил")
            || (s.contains("когда ") && !s.contains("приедем") && !s.contains("доедем"));
    }

    static String ru2zh(String t) {
        String s = t.toLowerCase().replace('ё', 'е');
        if (isGuarded(s)) return null;
        boolean off = isOff(s) || offWordOf(s);
        String zone = zoneOf(s); if (zone.isEmpty()) zone = zoneCode(wakeZone);
        String z = zhZone(zone);            // zone with speaker-seat default (seats, windows)
        String zx = zhZone(zoneOf(s));      // EXPLICIT zone only, "" if not named (climate: canonical 把温度调到24度)
        String grade = gradeOf(s);
        int n = numIn(s);
        if (n < 0) n = levelWordOf(s);   // «обдув на пятёрку»
        String r;

        if ((r = zhSeats(s, off, z, grade, n)) != null) return r;
        if ((r = zhClimate(s, off, zx, grade, n)) != null) return r;
        if ((r = zhWindowsRoof(s, off, z, n)) != null) return r;
        if ((r = zhDoorsLocks(s, off, z)) != null) return r;
        if ((r = zhLights(s, off, grade)) != null) return r;
        if ((r = zhMirrorsSteerWipers(s, off, grade, n)) != null) return r;
        if ((r = zhHudDisplays(s, off, grade)) != null) return r;
        if ((r = zhAutoPilot(s, off, n)) != null) return r;      // unsafe-gated inside
        if ((r = zhDriveEnergy(s, off, grade)) != null) return r;
        if ((r = zhComfortModes(s, off, n)) != null) return r;
        if ((r = zhConnectivity(s, off)) != null) return r;
        if ((r = zhCameraDvr(s, off)) != null) return r;
        if ((r = zhNavi(s, off, grade)) != null) return r;
        if ((r = zhPhone(s)) != null) return r;
        if ((r = zhSoundMedia(s, off, grade, n)) != null) return r;
        if ((r = zhVehicleInfo(s)) != null) return r;
        if ((r = zhSmartHome(s, off, n)) != null) return r;
        if ((r = zhAppUi(s, off)) != null) return r;
        if ((r = zhGeneralUi(s, n)) != null) return r;           // bare context words — last
        return null; // not a known command
    }

    // ---------------------------------------------------------------- carControl: seats

    private static String zhSeats(String s, boolean off, String z, String grade, int n) {
        boolean seat = s.contains("сиден") || s.contains("кресл") || s.contains("кресел") // "кресел" - fleeting vowel
            // народные имена сиденья: «продув пердака», «подогрей жопу», «помассируй булки»
            || s.contains("пердак") || s.contains("жоп") || s.contains("булк")
            || s.contains("пятую точку") || s.contains("пятой точк") || s.contains("пятая точка");
        // bare "включи подогрев" / "попогрейку" (без объекта) = подогрев сиденья говорящего;
        // руль/зеркала/стёкла/холодильник имеют свои домены и исключаются
        boolean bareHeat = (s.contains("подогрев") || s.contains("подогрей") || s.contains("попогрей") || s.contains("жопогрей"))
            && !s.contains("рул") && !s.contains("зеркал") && !s.contains("стек")
            && !s.contains("лоб") && !s.contains("холодильник") && !s.contains("морозилк") && !s.contains("двигател");
        // heating / ventilation / massage
        if (seat || s.contains("массаж") || s.contains("помассир") || s.contains("массир")
            || s.contains("поясниц") || s.contains("спинк") || bareHeat) {
            if (s.contains("подогре") || s.contains("обогре") || s.contains("грей") || s.contains("греть") || s.contains("тепл")) {
                if (n >= 1 && n <= 3) return z + "座椅加热" + n + "档";
                if (grade.equals("PLUS")) return z + "座椅加热调高一点";
                if (grade.equals("MINUS")) return z + "座椅加热调低一点";
                if (grade.equals("MAX")) return z + "座椅加热调到最高";
                return (off ? "关闭" : "打开") + z + "座椅加热";
            }
            if (s.contains("вентил") || s.contains("продув") || s.contains("обдув")) {
                if (n >= 1 && n <= 3) return z + "座椅通风" + n + "档";
                if (grade.equals("PLUS")) return z + "座椅通风调高一点";
                if (grade.equals("MINUS")) return z + "座椅通风调低一点";
                return (off ? "关闭" : "打开") + z + "座椅通风";
            }
            if (s.contains("массаж") || s.contains("массир")) {
                if (s.contains("режим") || s.contains("друго")) return "换个按摩模式";       // SWITCH_SEAT_MASSAGE_MODE
                if (grade.equals("PLUS")) return "按摩强度调大一点";
                if (grade.equals("MINUS")) return "按摩强度调小一点";
                return (off ? "关闭" : "打开") + z + "座椅按摩";
            }
            if (s.contains("поясниц")) {                                                    // ADJUST_LUMBAR_POSITION
                if (grade.equals("MINUS") || s.contains("слабее") || s.contains("ниже")) return "腰部支撑调低一点";
                return "腰部支撑调高一点";
            }
            if (s.contains("спинк") || s.contains("наклон") || s.contains("разложи") || s.contains("откинь")) { // BACKREST
                if (s.contains("разложи") || s.contains("положи")) return "放倒" + z + "座椅靠背";
                if (s.contains("подними") && !s.contains("немного")) return "竖起" + z + "座椅靠背";
                if (s.contains("назад") || s.contains("откинь") || s.contains("опусти") || grade.equals("PLUS"))
                    return z + "座椅靠背角度调大一点";
                return z + "座椅靠背角度调小一点";
            }
            if (s.contains("подушк")) {                                                     // CUSHION
                return grade.equals("MINUS") || s.contains("ниже")
                    ? z + "座椅坐垫调低一点" : z + "座椅坐垫调高一点";
            }
            if (s.contains("сохрани") || s.contains("запомни")) return "保存当前座椅位置";   // SWITCH_SAVE_SEAT_POSITION
            if (s.contains("вперед") || s.contains("придвинь") || s.contains("пододвинь")) return z + "座椅往前调一点"; // ADJUST_SEAT_POSITION
            if (s.contains("назад") || s.contains("отодвинь")) return z + "座椅往后调一点";
            if (s.contains("выше") || s.contains("подним") || grade.equals("PLUS")) return z + "座椅位置调高一点";
            if (s.contains("ниже") || s.contains("опусти") || grade.equals("MINUS")) return z + "座椅位置调低一点";
        }
        if (s.contains("подставк") || s.contains("подножк") || (s.contains("ног") && s.contains("опор"))) {
            if (s.contains("длинн")) return "腿托调长一点";                                  // ADJUST_LEG_SUPPORT_LENGTH
            if (s.contains("короче")) return "腿托调短一点";
            if (off || s.contains("убери") || s.contains("сложи")) return "收起" + z + "脚托"; // SET_SEAT_FOOTREST
            if (s.contains("ниже") || grade.equals("MINUS")) return "腿部支撑调低一点";       // ADJUST_LEG_SUPPORT_POSITION
            if (s.contains("выше") || grade.equals("PLUS")) return "腿部支撑调高一点";
            return "打开" + z + "脚托";
        }
        if (s.contains("вип") && s.contains("пассажир")) return (off ? "关闭" : "打开") + "尊享副驾"; // EXCLUSIVE_FRONT_PASSENGER
        if (s.contains("комфортн") && (s.contains("посадк") || s.contains("выход")))
            return (off ? "关闭" : "打开") + "舒适进出";                                     // OP_COMFORTABLE_ENTRY
        return null;
    }

    // ---------------------------------------------------------------- carControl: climate

    private static String zhClimate(String s, boolean off, String z, String grade, int n) {
        // home devices ("кондиционер дома") belong to smartHome, not car climate
        if (s.contains("дома") || s.contains("домашн") || s.contains("квартир")) return null;
        // defrost first — "обогрев стекла" must not fall into temperature
        if (s.contains("обдув лоб") || s.contains("обдув стек") || s.contains("обогрев стек")
            || s.contains("обогрев лоб") || s.contains("обогрев задн") || s.contains("разморозк")
            || s.contains("отпот") || s.contains("запотел") || s.contains("потеют")) {
            boolean rear = s.contains("задн");
            return (off ? "关闭" : "打开") + (rear ? "后挡除霜" : "前挡除霜");
        }
        // complaints -> temperature ("мне холодно" = WARMER, "жарко/душно" = cooler)
        // "холодно( в машине)" — жалоба = ТЕПЛЕЕ; "холоднее/похолоднее" — просьба = ХОЛОДНЕЕ
        if (s.contains("замерз") || s.contains("продрог") || s.contains("тепла хочу") || s.contains("хочу тепла")
            || s.contains("дубак") || s.contains("колотун") || s.contains("холодрыг")
            || (s.contains("поддай") && (s.contains("жар") || s.contains("тепл")))
            || ((s.contains("холодно ") || s.endsWith("холодно")) && !s.contains("холоднее")))
            return "温度调高一点";
        if (s.contains("жарко") || s.contains("душно") || s.contains("мне жарко") || s.contains("парилка")
            || s.contains("запарил") || s.contains("вспотел") || s.contains("сауна") || s.contains("как в бане")
            || s.contains("пекло") || s.contains("духота")) return "温度调低一点";
        if (s.contains("прохладн")) return "温度调低一点";
        // «быстро/срочно/максимально охлади» -> 强力制冷 (мощное охлаждение), иначе обычное 制冷
        boolean strong = s.contains("быстр") || s.contains("скорее") || s.contains("срочно") || s.contains("сильн")
            || s.contains("максимал") || s.contains("на всю") || s.contains("мощн") || s.contains("резко");                                    // "сделай прохладнее"
        if ((s.contains("прогрей") || s.contains("согрей") || s.contains("натопи"))
            && (s.contains("салон") || s.contains("машин") || s.contains("тачк")))
            return strong ? "打开强力制热模式" : "打开制热模式";                            // «быстро прогрей» -> 强力制热
        if (s.contains("охлади") || s.contains("остуди")) return strong ? "打开强力制冷模式" : "打开制冷模式";
        if (s.contains("проветри") || (s.contains("свеж") && s.contains("воздух"))) return "打开外循环"; // "свежий воздух"
        if ((s.contains("очист") && s.contains("воздух")) || (s.contains("очистител") && !s.contains("стекл"))
            || s.contains("ионизац"))                                                        // не «стеклоОЧИСТИТЕЛи»
            return (off ? "关闭" : "打开") + "空气净化";                                     // mode 空气净化
        // "дует слишком сильно / слабо" -> fan complaints (но не про ветер на улице)
        if (s.contains("дует") && !s.contains("улиц") && !s.contains("ветер")) {
            if (s.contains("сильн") || s.contains("слишком")) return "风量调小一点";
            if (s.contains("слаб") || s.contains("еле")) return "风量调大一点";
        }
        // "печка" = heater ("отопление", "обогреватель")
        if (s.contains("печк") || s.contains("отоплен") || s.contains("обогреватель")) {
            if (off) return "关闭空调";
            if (grade.equals("PLUS")) return "温度调高一点";
            if (grade.equals("MINUS")) return "温度调低一点";
            return "打开制热模式";
        }
        // bare "включи обогрев" (без объекта — зеркала/руль/стёкла/сиденья ловятся в других доменах)
        if (s.contains("обогрев") && !s.contains("зеркал") && !s.contains("руль") && !s.contains("рулев")
            && !s.contains("сиден") && !s.contains("кресл"))
            return off ? "关闭空调" : "打开制热模式";
        // temperature (excludes seat/steering/mirror/fridge heat — handled elsewhere)
        if ((s.contains("температур") || s.contains("градус") || s.contains("теплее") || s.contains("холодн"))
            && !s.contains("сиден") && !s.contains("кресл") && !s.contains("руль") && !s.contains("зеркал")
            && !s.contains("холодильник") && !s.contains("морозилк") && !s.contains("холодос") && !s.contains("цветов")) {
            if (s.contains("синхрон") || s.contains("одинаков")) return off ? "关闭温度同步" : "温度同步"; // TEMPERATURE_SYNC
            if (n >= 16 && n <= 33) return "把" + z + "温度调到" + n + "度";
            if (grade.equals("MAX")) return "温度调到最高";
            if (grade.equals("MIN")) return "温度调到最低";
            // "теплее на 2 градуса" -> relative step with value
            if (grade.equals("PLUS") || plusWordOf(s)) return (n >= 1 && n <= 8 && s.contains("на ")) ? "温度调高" + n + "度" : "温度调高一点";
            if (grade.equals("MINUS") || minusWordOf(s)) return (n >= 1 && n <= 8 && s.contains("на ")) ? "温度调低" + n + "度" : "温度调低一点";
            return null;
        }
        // airflow direction: "дуй в лицо/в ноги/на стекло"
        if (s.contains("дуй") || s.contains("обдув в") || s.contains("обдув на") || s.contains("поток в")
            || s.contains("направь воздух") || ((s.contains("обдув") || s.contains("поток")) && (s.contains("лицо") || s.contains("ноги")))) {
            // «стекло/лобовое» — не направление обдува, а режим разморозки (единственный обдув стекла в NLU);
            // «обдув на стекло и ноги» = одна команда за фразу -> разморозка
            if (s.contains("стекл") || s.contains("лобов")) return (off ? "关闭" : "打开") + "前挡除霜";
            // «тело/грудь/корпус» — не значение каталога (面|脚|吹面吹脚), считаем верхом = лицо
            boolean face = s.contains("лицо") || s.contains("лиц") || s.contains("на меня") || s.contains("грудь")
                || s.contains("тело") || s.contains("корпус") || s.contains("торс")
                || s.contains("в рот") || s.contains("морд") || s.contains("в харю") || s.contains("в щи");
            boolean feet = s.contains("ноги") || s.contains("ног");
            if (face && feet) return "空调吹面吹脚";
            if (face) return "空调吹面";
            if (feet) return "空调吹脚";
        }
        if (s.contains("качани") || s.contains("шторк обдув") || s.contains("扫风"))
            return off ? "关闭扫风" : (s.contains("друго") || s.contains("смени") ? "换个扫风模式" : "打开扫风");
        // fan speed (exclude defrost + seat contexts, as in reference)
        boolean fanCtx = !s.contains("лобов") && !s.contains("стекл") && !s.contains("сиден") && !s.contains("кресл");
        if (fanCtx && (s.contains("обдув") || s.contains("вентилятор") || s.contains("дуй")
            || (s.contains("поток") && (s.contains("воздух") || s.contains("сильн") || s.contains("слаб")))
            || ((s.contains("скорост") || s.contains("сил")) && s.contains("вент")))) {   // «дуй сильнее», «поток воздуха слабее»
            if (n >= 1 && n <= 7) return "风量调到" + n + "档";
            if (grade.equals("MAX") || s.contains("на полную")) return "风量调到最大";
            if (grade.equals("MIN")) return "风量调到最小";
            if (grade.equals("PLUS") || plusWordOf(s) || s.contains("усил") || s.contains("быстр")) return "风量调大一点";
            if (grade.equals("MINUS") || minusWordOf(s) || s.contains("ослаб") || s.contains("медленн")) return "风量调小一点";
            return off ? "关闭空调风量" : "打开空调风量";
        }
        if (s.contains("рециркул") || s.contains("циркуляц") || (s.contains("забор") && s.contains("воздух"))) {
            if (s.contains("переключ") || s.contains("смени")) return "切换内外循环";        // SWITCH_AIR_CIRCULATION
            return off ? "打开外循环" : "打开内循环";                                        // SET_AIR_CIRCULATION
        }
        // AC mode
        if (s.contains("климат") || s.contains("кондиц") || s.contains("кондер") || s.contains("кондей")
            || s.contains("кондишн") || s.contains("кондишк")) {
            if (s.contains("авто")) return "打开空调自动模式";                                // SET_AIR_CONDITIONER_MODE
            if (s.contains("охлажд")) return strong ? "打开强力制冷模式" : "打开制冷模式";
            if (s.contains("обогрев") || s.contains("нагрев")) return strong ? "打开强力制热模式" : "打开制热模式";
            if (s.contains("эконом") || s.contains("энергосбер")) return "打开空调节能模式"; // mode 节能
            if (s.contains("осушен") || s.contains("влажн")) return "打开除湿模式";
            if (n >= 16 && n <= 33) return "把温度调到" + n + "度";                            // "кондиционер на 22"
            // "климат/климат-контроль" = автоматический климат-контроль; "кондиционер" = просто AC.
            // Выключение у обоих одно: 关闭空调. Явная зона важнее авторежима ("климат пассажиру").
            if (s.contains("климат") && !off && z.isEmpty()) return "打开空调自动模式";        // SET_AIR_CONDITIONER_MODE
            // «кондиционер/кондей» = КОМПРЕССОР A/C, не вся установка: mappingRule нормализует 制冷 -> AC.
            // «выключи кондиционер» гасит только охлаждение, вентилятор/печка остаются; «климат» = 空调 целиком.
            if (!s.contains("климат") && z.isEmpty()) return off ? "关闭制冷模式" : "打开制冷模式";
            return (off ? "关闭" : "打开") + z + "空调";                                     // OP_AIR_CONDITIONER (z = explicit zone or "")
        }
        // fragrance
        // "запах" только с глаголом действия — "странный запах в машине" это болтовня
        if (s.contains("ароматизат") || s.contains("аромат") || s.contains("парфюм")
            || (s.contains("запах") && (isOn(s) || off || s.contains("смени") || s.contains("убери") || s.contains("сделай")))) {
            if (s.contains("смени") || s.contains("друго")) return "换个香氛";               // SWITCH_FRAGRANCE
            return off ? "关闭香氛" : "打开香氛";                                            // SET_FRAGRANCE
        }
        return null;
    }

    // ---------------------------------------------------------------- carControl: windows / roof

    private static String zhWindowsRoof(String s, boolean off, String z, int n) {
        // "окон" (gen.pl.) does NOT contain "окн" — fleeting vowel, match both forms
        // "стекло" is the everyday word for a car window ("опусти стекло") — but only with
        // open/close verbs and outside washer/defrost contexts
        boolean glass = s.contains("стекл") && !s.contains("помой") && !s.contains("омыват")
            && !s.contains("обогрев") && !s.contains("обдув") && !s.contains("лобов")
            && !s.contains("протри") && !s.contains("вытри") && !s.contains("запотел")
            && (s.contains("подним") || s.contains("опусти") || s.contains("приоткр")
                || s.contains("закр") || s.contains("откр") || s.contains("вверх") || s.contains("вниз")
                || s.contains("заблок") || s.contains("разблок") || s.contains("блокир"));
        boolean win = (s.contains("окн") && !s.contains("аудиокн"))  // "аудиОКНига" - не окно!
            || s.contains("окон") || s.contains("окош") || s.contains("форточ")
            || s.contains("стеклоподъемн") || glass;
        if (win && !s.contains("шторк") && !s.contains("солнцезащит")) {
            if (s.contains("блокир") || s.contains("замок")) {                                 // OP_WINDOW_LOCK ("заблокируй/разблокируй стеклоподъемники")
                boolean disable = off || s.contains("разблок") || s.contains("сними");
                return (disable ? "关闭" : "打开") + "车窗锁";
            }
            if (s.contains("в дождь") || s.contains("дожд")) {                                 // OP_RAIN_CLOSE_WINDOW
                // "закрывай окна в дождь" = ENABLE the feature; only explicit off disables
                boolean disable = s.contains("выключ") || s.contains("отключ") || s.contains("не закрывай");
                return (disable ? "关闭" : "打开") + "雨天自动关窗";
            }
            if (s.contains("при закрыт") || s.contains("на охран")) {                          // OP_LOCK_CLOSE_WINDOW
                boolean disable = s.contains("выключ") || s.contains("отключ");                // "закрой окна на охране" = ENABLE
                return (disable ? "关闭" : "打开") + "锁车关窗";
            }
            if (s.contains("приоткр") || s.contains("щелочк") || s.contains("щель")) return z + "车窗留缝";
            int p = percentOf(s);
            if (p == 50) return z + "车窗开一半";
            if (p > 0) return z + "车窗开到百分之" + p;
            // "опусти" = OPEN (window goes down), "подними/вверх" = CLOSE — inverse of isOff!
            boolean close = s.contains("подним") || s.contains("закр") || s.contains("выключ") || s.contains("вверх");
            return (close ? "关闭" : "打开") + z + "车窗";
        }
        // "крыша/панорама" = люк, но НЕ "крышка багажника/бензобака/зарядки" и не шторка
        if ((s.contains("люк") || s.contains("панорам")
             || (s.contains("крыш") && !s.contains("багажник") && !s.contains("заряд")
                 && !s.contains("бак") && !s.contains("капот")))
            && !s.contains("шторк") && !s.contains("солнцезащит")) {
            int p = percentOf(s);
            if (p == 50) return "天窗开一半";
            return off ? "关闭天窗" : "打开天窗";
        }
        if (s.contains("шторк") || s.contains("солнцезащит")) {
            if (win || s.contains("боков") || s.contains("задн") || s.contains("сзади"))
                return (off ? "关闭" : "打开") + "车窗遮阳帘";                                // SET_WINDOW_SUN_SHADE
            return off ? "关闭遮阳帘" : "打开遮阳帘";                                        // SET_SUN_SHADE
        }
        if (s.contains("все") && (s.contains("открой") || s.contains("закрой"))
            && !s.contains("приложен") && !win)
            return off ? "一键全关" : "一键全开";                                            // OPEN/CLOSE_ALL_ONE_KEY
        return null;
    }

    // ---------------------------------------------------------------- carControl: doors / trunks / locks

    private static String zhDoorsLocks(String s, boolean off, String z) {
        if (s.contains("багажник") && !s.contains("свет") && !s.contains("лампа")) {
            if (s.contains("передн") || s.contains("фрунк")) return unsafeGate(off ? "关闭前备箱" : "打开前备箱"); // OP_FRUNK
            if (s.contains("верхн")) return unsafeGate(off ? "关闭上尾门" : "打开上尾门");   // OP_UPPER_TAILGATE
            if (s.contains("нижн") || s.contains("борт")) return unsafeGate(off ? "关闭下尾门" : "打开下尾门"); // OP_LOWER_TAILGATE
            return unsafeGate(off ? "关闭后备箱" : "打开后备箱");                            // OP_TRUNK
        }
        if (s.contains("капот")) return unsafeGate(off ? "关闭前备箱" : "打开前备箱");       // OP_FRUNK
        if (s.contains("детск") && (s.contains("замок") || s.contains("блокировк")))
            return (off ? "关闭" : "打开") + "儿童锁";                                       // OP_CHILD_SAFETY_LOCK
        if (s.contains("двер")) {
            if (s.contains("запр") || s.contains("отопр") || s.contains("заблок") || s.contains("разблок")
                || s.contains("замкни") || s.contains("замок")) {
                boolean unlock = s.contains("отопр") || s.contains("разблок") || s.contains("открой");
                return unsafeGate(unlock ? "解锁" : "锁车");                                 // OP_DOOR_LOCK
            }
            if (s.contains("холодильник")) return (off ? "关闭" : "打开") + "冰箱门";        // SET_REFRIGERATOR_DOOR
            return unsafeGate((off ? "关闭" : "打开") + z + "车门");                         // OP_CAR_DOOR
        }
        if ((s.contains("запр") || s.contains("отопр") || s.contains("заблокируй") || s.contains("разблокируй")
             || s.contains("закрой") || s.contains("открой"))
            && (s.contains("машин") || s.contains("автомобил") || s.contains("тачк")
                || s.contains("замок"))) {  // "закрой (на) замок" — детский/оконный замок ловятся раньше
            boolean unlock = s.contains("отопр") || s.contains("разблок") || s.contains("открой");
            return unsafeGate(unlock ? "解锁" : "锁车");                                     // OP_DOOR_LOCK ("закрой машину")
        }
        // "открой/закрой зарядку, бак" без слова "лючок" — тоже крышки портов
        // (только с откр/закр: "включи зарядку" — НЕ крышка, уходит дальше/в чат)
        if (s.contains("лючок") || s.contains("порт зарядк") || (s.contains("зарядн") && s.contains("порт"))
            || ((s.contains("откр") || s.contains("закр"))
                && (s.contains("заряд") || s.contains("бак") || s.contains("заправ")))) {
            // «бак для зарядки», «зарядное отверстие» — порт зарядки: слово «заряд» важнее «бака»
            if (s.contains("заряд")) return (off ? "关闭" : "打开") + "充电口";            // OP_CHARGING_PORT_COVER
            if (s.contains("бензо") || s.contains("бак") || s.contains("топлив") || s.contains("заправ"))
                return (off ? "关闭" : "打开") + "油箱盖";                                   // OP_FUEL_TANK_CAP ("заправочный лючок")
            return (off ? "关闭" : "打开") + "充电口";                                       // голый «лючок» = зарядка
        }
        if (s.contains("подход") && s.contains("разблок")) return (off ? "关闭" : "打开") + "近车自动解锁"; // OP_APPROACH_UNLOCK
        if ((s.contains("уход") || s.contains("отход")) && (s.contains("закрыв") || s.contains("блокир")))
            return (off ? "关闭" : "打开") + "离车自动闭锁";                                 // OP_LEAVE_LOCK
        return null;
    }

    // ---------------------------------------------------------------- carControl: lights

    private static String zhLights(String s, boolean off, String grade) {
        if (s.contains("подсветк") || s.contains("атмосферн") || s.contains("амбиент") || s.contains("эмбиент")
            || s.contains("неонов") || s.contains("неоновую")) {
            if (s.contains("настройк")) return "打开氛围灯设置";                              // CONTROL_AMBIENT_LIGHTING_PAGE
            String c = colorOf(s);
            if (!c.isEmpty()) return "氛围灯调成" + zhColor(c);                              // SET_MOOD_LIGHTS_COLOR
            if (s.contains("друго") && s.contains("цвет")) return "氛围灯换个颜色";           // SWITCH_M_L_COLOR
            if (s.contains("эффект")) return "氛围灯换个灯效";                                // SWITCH_M_L_EFFECT
            if (s.contains("тем") && s.contains("подсветк") && (s.contains("друг") || s.contains("смени")))
                return "氛围灯换个主题";                                                     // SET_MOOD_LIGHTS_THEME
            if (s.contains("такт") || s.contains("ритм") || s.contains("музык")) return "打开氛围灯律动模式"; // SET_MOOD_LIGHTS_MODE
            if (s.contains("градиент") || s.contains("перелив")) return "打开氛围灯渐变效果"; // SET_MOOD_LIGHTS_GRADIENT
            if (s.contains("ярче") || grade.equals("PLUS") || plusWordOf(s)) return "氛围灯调亮一点"; // SET_MOOD_LIGHTS_BRIGHTNESS
            if (s.contains("темнее") || s.contains("притуш") || s.contains("приглуш")
                || grade.equals("MINUS") || minusWordOf(s)) return "氛围灯调暗一点";
            return off ? "关闭氛围灯" : "打开氛围灯";                                        // OP_MOOD_LIGHTS
        }
        if (s.contains("световое шоу") || s.contains("светомузык") || s.contains("шоу свет"))
            return (off ? "关闭" : "打开") + "音乐灯光秀";                                    // CONTROL_LIGHT_SHOW
        boolean beamVerb = s.contains("свет") || s.contains("включ") || s.contains("выключ") || s.contains("вруб") || s.contains("выруб");
        // авто-режим фар: в dm-каталоге есть устройства 自动近光灯/自动远光灯 (OPEN/CLOSE).
        // «автомат»/«авто режим» — не «авто» голое, чтобы «свет в автомобиле» сюда не попал
        if ((s.contains("фар") || s.contains("ближн") || s.contains("дальн") || s.contains("свет"))
            && (s.contains("автомат") || s.contains("авто режим") || s.contains("авторежим"))
            && !s.contains("подсветк")) {
            boolean high = s.contains("дальн");
            return (off ? "关闭" : "打开") + (high ? "自动远光灯" : "自动近光灯");
        }
        if (s.contains("дальн") && beamVerb) return off ? "关闭远光灯" : "打开远光灯";        // OP_HIGH_BEAM ("вруби дальний")
        // fallback: «фары в авто» с голым «авто» (не «автомат») — лучше в чат, чем включить ближний
        if ((s.contains("фар") || s.contains("ближн")) && s.contains("авто")) return null;
        if ((s.contains("ближн") && beamVerb) || (s.contains("фары") && !s.contains("дальн"))) {
            if (s.contains("выше") || s.contains("ниже") || s.contains("высот"))              // SET_DIPPED_BEAM_HEIGHT
                return s.contains("ниже") || grade.equals("MINUS") ? "近光灯高度调低一点" : "近光灯高度调高一点";
            return off ? "关闭近光灯" : "打开近光灯";                                        // OP_DIPPED_BEAM
        }
        if (s.contains("аварийк") || s.contains("аварийн")) return off ? "关闭双闪" : "打开双闪"; // OP_WARNING_LIGHT
        if (s.contains("противотуман") || s.contains("туманк")) return (off ? "关闭" : "打开") + "后雾灯"; // OP_REAR_FOG_LIGHT
        if (s.contains("габарит")) return (off ? "关闭" : "打开") + "示廓灯";                 // OP_OUTLINE_LIGHT
        if (s.contains("стояночн") || s.contains("позиционн") || s.contains("ходов") || s.contains("дхо"))
            return (off ? "关闭" : "打开") + "位置灯";                                        // OP_SIDE_LIGHTS (+ДХО: ближайший интент)
        if (s.contains("задн") && (s.contains("фонар") || s.contains("фонарь"))) return (off ? "关闭" : "打开") + "尾灯"; // OP_TAILLIGHT
        if (s.contains("багажник") && (s.contains("свет") || s.contains("ламп"))) return (off ? "关闭" : "打开") + "后备箱灯"; // OP_TRUNK_LIGHT
        if ((s.contains("притуш") || s.contains("приглуш")) && s.contains("свет")) return "氛围灯调暗一点";
        // Салонный свет (OP_READ_LIGHTS). Проверено на авто: голое 阅读灯 НЕ работает, нужна позиция:
        //   打开车内所有灯光 / 关闭车内所有灯光 — весь свет в салоне («включи свет», «весь свет»)
        //   打开右后阅读灯 и т.п.          — лампа чтения с позицией (左前/右前/左后/右后/前排/后排)
        // (нужен глагол действия: "я люблю свет луны" — болтовня; "зажги/погаси свет" тоже сюда)
        if ((s.contains("свет") || s.contains("освещен") || s.contains("ламп") || s.contains("плафон"))
            && !s.contains("светл") && !s.contains("свето") && !s.contains("светк") && !s.contains("дома") // «(пад)светку» — не сюда
            && (isOn(s) || off || s.contains("зажги") || s.contains("вруб") || s.trim().equals("свет"))) {
            String on = off ? "关闭" : "打开";
            String zc = zoneOf(s);
            boolean all = zc.equals("ALL") || s.contains("весь") || s.contains("везде");
            boolean lamp = s.contains("чтени") || s.contains("ламп") || s.contains("плафон");
            if (all) return on + "车内所有灯光";
            if (lamp || !zc.isEmpty()) {                       // лампа чтения / свет с зоной -> позиция
                if (zc.isEmpty()) zc = zoneCode(wakeZone);       // «включи лампу» = лампа над говорящим
                return on + zhReadZone(zc) + "阅读灯";
            }
            return on + "车内所有灯光";                          // голое «включи свет» = весь салон
        }
        return null;
    }

    /** Zone code -> позиция лампы чтения (штатный NLU: 左前/右前/左后/右后/前排/后排). */
    private static String zhReadZone(String code) {
        if ("DRIVER".equals(code)) return "左前";
        if ("PASSENGER".equals(code)) return "右前";
        if ("REAR_LEFT".equals(code)) return "左后";
        if ("REAR_RIGHT".equals(code)) return "右后";
        if ("REAR".equals(code)) return "后排";
        if ("FRONT".equals(code)) return "前排";
        return "";
    }

    // ---------------------------------------------------------------- carControl: mirrors / steering / wipers

    private static String zhMirrorsSteerWipers(String s, boolean off, String grade, int n) {
        if (s.contains("зеркал")) {
            if (s.contains("обогре") || s.contains("подогре") || s.contains("грей") || s.contains("греть"))
                return (off ? "关闭" : "打开") + "后视镜加热";                                // REAR_MIRROR_WARM
            if (s.contains("сложи") || s.contains("сверни")) return "折叠后视镜";             // OP_REAR_MIRROR_CONTROL
            if (s.contains("разложи") || s.contains("разверни")) return "展开后视镜";
            if (s.contains("автоскладыв") || s.contains("автомат")) return (off ? "关闭" : "打开") + "后视镜自动折叠"; // OP_REAR_MIRROR_AUTO
            if (s.contains("выше") || s.contains("вверх")) return "后视镜向上调一点";          // ADJ_REARVIEW_MIRROR
            if (s.contains("ниже") || s.contains("вниз")) return "后视镜向下调一点";
            if (s.contains("влево") || s.contains("левее")) return "后视镜向左调一点";
            if (s.contains("вправо") || s.contains("правее")) return "后视镜向右调一点";
            if (s.contains("стриминг") || s.contains("камер")) return (off ? "关闭" : "打开") + "流媒体后视镜"; // OP_STREAM_MEDIA_REAR_VIEW
        }
        if (s.contains("руль") || s.contains("руля") || s.contains("рулев")) {
            if (s.contains("выше") || s.contains("подним")) return "方向盘调高一点";           // ADJ_STEER_DIRECTION
            if (s.contains("ниже") || s.contains("опусти")) return "方向盘调低一点";
            if (s.contains("легче") || s.contains("тяжелее") || s.contains("усили"))          // SET_STEERING_STYLE
                return s.contains("легче") ? "转向调到轻便模式" : "转向调到运动模式";
            // руль-подогрев только по явным словам тепла — "зафиксируй руль" не должен греть
            if (s.contains("подогре") || s.contains("обогре") || s.contains("грей") || s.contains("греть") || s.contains("тепл")) {
                if (n >= 1 && n <= 3) return "方向盘加热调到" + n + "档";                      // SET_STEER_WARM
                if (grade.equals("PLUS")) return "方向盘加热调高一点";
                return off ? "关闭方向盘加热" : "打开方向盘加热";                             // OP_STEER_WARM
            }
            return null;
        }
        if ((s.contains("помой") && (s.contains("лобов") || s.contains("стекл"))) || s.contains("омыват") || s.contains("брызни"))
            return "喷水洗玻璃";                                                             // WASH_WIPER
        if (s.contains("датчик") && s.contains("дожд"))                                       // SET_WIPER_SENSITIVITY (без слова "дворники")
            return grade.equals("MINUS") ? "雨刮灵敏度调低一点" : "雨刮灵敏度调高一点";
        if ((s.contains("вытри") || s.contains("протри") || s.contains("смахни")) && s.contains("стекл"))
            return "打开雨刮";                                                                // "протри/смахни капли со стекла"
        if (s.contains("дворник") || s.contains("щетк") || s.contains("стеклоочистит")) {
            if (s.contains("сервис") || s.contains("ремонт") || s.contains("замен"))
                return (off ? "关闭" : "打开") + "雨刮维修模式";                              // REPAIR_WIPER
            if (s.contains("чувствительн") || s.contains("датчик"))                           // SET_WIPER_SENSITIVITY
                return grade.equals("MINUS") ? "雨刮灵敏度调低一点" : "雨刮灵敏度调高一点";
            if (n >= 1 && n <= 4) return "雨刮调到" + n + "档";                                // SET_WIPER_SPEED
            if (s.contains("быстрее") || grade.equals("PLUS") || grade.equals("MAX")) return "雨刮快一点";
            if (s.contains("медленн") || grade.equals("MINUS")) return "雨刮慢一点";
            return off ? "关闭雨刮" : "打开雨刮";
        }
        return null;
    }

    // ---------------------------------------------------------------- carControl: HUD / displays

    private static String zhHudDisplays(String s, boolean off, String grade) {
        // "главный экран"/"предыдущий экран" are UI navigation, not display control
        if (s.contains("главн") || s.contains("предыдущ") || s.contains("рабочий стол")) return null;
        if ((s.contains("экран") || s.contains("дисплей")) && (s.contains("ко мне") || s.contains("к водителю") || s.contains("на меня")))
            return null;                                                                      // наклона экрана к водителю нет — не выдавать 横屏
        if (s.contains("hud") || s.contains("проекц") || s.contains("хад") || s.contains("худ")) {
            if (s.contains("ярк") || s.contains("ярч")) return grade.equals("MINUS") || s.contains("темнее") // SET_HUD_BRIGHTNESS
                ? "HUD亮度调低一点" : "HUD亮度调高一点";
            if (s.contains("угол") || s.contains("наклон"))                                   // SET_HUD_ANGLE
                return s.contains("ниже") || grade.equals("MINUS") ? "HUD角度调低一点" : "HUD角度调高一点";
            if (s.contains("выше") || s.contains("подним")) return "HUD高度调高一点";          // SET_HUD_HEIGHT
            if (s.contains("ниже") || s.contains("опусти")) return "HUD高度调低一点";
            if (s.contains("цвет")) return "切换HUD颜色模式";                                  // SWITCH_HUD_COLOR_MODE
            if (s.contains("режим") || s.contains("вид")) return "切换HUD显示模式";            // SET_HUD_MODE / SWITCH_HUD_DISPLAY_MODE
            if (s.contains("настройк")) return "打开抬头显示设置";                             // CONTROL_HUD_PAGE
            return off ? "关闭抬头显示" : "打开抬头显示";                                     // OP_HUD
        }
        if (s.contains("экран") || s.contains("дисплей")) {
            if (s.contains("пассажир") && (s.contains("угол") || s.contains("наклон") || s.contains("выше") || s.contains("ниже")))
                return grade.equals("MINUS") || s.contains("ниже")                             // SET_PDISPLAY_ANGLE
                    ? "副驾屏幕角度调低一点" : "副驾屏幕角度调高一点";
            if (s.contains("автояркост") || (s.contains("авто") && s.contains("ярк")))
                return (off ? "关闭" : "打开") + "自动亮度";                                  // OP_AUTO_DISPLAY_BRIGHTNESS
            if (s.contains("яркост") || s.contains("ярче") || s.contains("темнее")) {          // SET_DISPLAY_BRIGHTNESS
                if (grade.equals("MAX")) return "屏幕亮度调到最大";
                if (grade.equals("MIN")) return "屏幕亮度调到最小";
                return s.contains("темнее") || s.contains("притуш") || s.contains("приглуш")
                    || grade.equals("MINUS") || minusWordOf(s) ? "屏幕调暗一点" : "屏幕调亮一点";
            }
            if (s.contains("цветов") && s.contains("температур"))                              // SET_DISPLAY_COLOR_TEMPERATURE
                return s.contains("холодн") ? "屏幕色温调冷一点" : "屏幕色温调暖一点";
            if (s.contains("защит") && s.contains("глаз")) return (off ? "关闭" : "打开") + "护眼模式"; // SET_DISPLAY_EYE_PROTECTION
            if (s.contains("ночн")) return "切换到夜间模式";                                   // SET_DISPLAY_MODE
            if (s.contains("дневн")) return "切换到白天模式";
            if (s.contains("горизонт") || s.contains("поверни") || s.contains("переверни")) return "切换到横屏"; // SET_DISPLAY_ORIENTATION
            if (s.contains("вертикал")) return "切换到竖屏";
            if (s.contains("очист") || s.contains("протер") || s.contains("протр"))
                return (off ? "关闭" : "打开") + "屏幕清洁模式";                              // OP_DISPLAY_CLEAN
            if (s.contains("усыпи") || s.contains("погаси")) return "息屏";                    // DISPLAY_SLEEP
            if (s.contains("разбуди") || s.contains("проснись")) return "亮屏";                // DISPLAY_UNSLEEP
            return off ? "关闭屏幕" : "打开屏幕";                                             // OP_DISPLAY_POWER
        }
        if (s.contains("шрифт")) return grade.equals("MINUS") || s.contains("мельче")          // SET_FONT_SIZE
            ? "字体调小一点" : "字体调大一点";
        // bare "яркость" without an object -> screen brightness (подсветка/HUD ловятся раньше)
        if (s.contains("яркост")) {
            if (s.contains("темнее") || grade.equals("MINUS") || minusWordOf(s)) return "屏幕调暗一点";
            if (s.contains("ярче") || grade.equals("PLUS") || plusWordOf(s)) return "屏幕调亮一点";
            return null;   // без направления не действуем: raw-гипотеза с искажённым глаголом не должна дать ЯРЧЕ
        }
        // bare "цветовая температура" без слова "экран"
        if (s.contains("цветов") && s.contains("температур"))
            return s.contains("холодн") ? "屏幕色温调冷一点" : "屏幕色温调暖一点";
        return null;
    }

    // ---------------------------------------------------------------- autoPilot (ALL unsafe)

    private static String zhAutoPilot(String s, boolean off, int n) {
        if (s.contains("круиз")) {
            if (n >= 30 && n <= 150) return unsafeGate("巡航速度调到" + n);                   // SET_CRUISE_CAR_SPEED
            if (s.contains("адаптивн")) return unsafeGate((off ? "关闭" : "打开") + "自适应巡航"); // OP_ACC
            return unsafeGate((off ? "关闭" : "打开") + "自适应巡航");
        }
        if (s.contains("автопилот") || (s.contains("автоматическ") && s.contains("вожден")))
            return unsafeGate((off ? "关闭" : "打开") + "自动驾驶");                          // OP_AUTO_DRIVE
        if (s.contains("iacc") || s.contains("иакк")) return unsafeGate((off ? "关闭" : "打开") + "IACC"); // OP_IACC
        if (s.contains("nca") || s.contains("навигационн") && s.contains("пилот"))
            return unsafeGate((off ? "关闭" : "打开") + "领航辅助");                          // OP_NCA
        if (s.contains("дистанц")) {                                                          // "сократи дистанцию", "дистанция побольше"
            if (n >= 1 && n <= 4) return unsafeGate("跟车距离调到" + n + "档");                // SET_CRUISE_FOLLOW_GAP
            return unsafeGate(s.contains("мень") || s.contains("ближе") || s.contains("сократи")
                || s.contains("помень") ? "跟车距离调小一点" : "跟车距离调大一点");
        }
        if (s.contains("следуй") || s.contains("следован")) {
            if (off || s.contains("отмени") || s.contains("не следуй")) return unsafeGate("取消跟车"); // CANCEL_FOLLOW_CAR
            return unsafeGate("跟着前车走");                                                 // CONTROL_FOLLOW_CAR
        }
        if (s.contains("перестро") || s.contains("полос")) {                                  // CONTROL_LANE_CHANGE ("полоса левее")
            if (s.contains("лев")) return unsafeGate("向左变道");
            if (s.contains("прав")) return unsafeGate("向右变道");
            int i = ordinalIn(s);
            if (i > 0) return unsafeGate("走第" + i + "车道");                                // INDEX_LANE_DRIVE
        }
        if (s.contains("держи полос") || s.contains("держись полос")) return unsafeGate("保持车道行驶"); // KEEP_LANE_DRIVE
        if (s.contains("обгони")) return unsafeGate("超过前车");                              // OVERTAKE_SPECIFIC_CAR
        if (s.contains("ограничен") && s.contains("скорост"))
            return unsafeGate((off ? "关闭" : "打开") + "限速提醒");                          // SPEED_LIMIT_CONTROL
        if (s.contains("парк")) {
            if (s.contains("запомни")) return unsafeGate("开始记忆泊车");                      // MEMORIZE_DRIVING_CONTROL
            if (s.contains("удали") || s.contains("забудь")) return unsafeGate("删除记忆泊车路线"); // DELETE_DRIVE_MEMORY
            if (s.contains("пауз") || s.contains("подожди")) return unsafeGate("暂停泊车");    // PARKING_CONTROL
            if (s.contains("продолж")) return unsafeGate("继续泊车");
            if (s.contains("выезжай") || s.contains("выехать") || s.contains("выйди") || s.contains("выгони")
                || s.contains("выеду")) return unsafeGate("泊出");                            // DRIVING_OUT
            if (s.contains("заезжай") || s.contains("это место")) return unsafeGate("泊入车位"); // PARK_IN
            if (s.contains("автопарк") || s.contains("сама") || s.contains("паркуйся"))
                return unsafeGate("帮我泊车");                                                // PARKING / OP_AUTO_PARKING ("припаркуйся" тоже)
        }
        if ((s.contains("едь за") || s.contains("езжай за") || s.contains("поезжай за")) && s.contains("машин"))
            return unsafeGate("跟着前车走");                                                  // CONTROL_FOLLOW_CAR ("едь за той машиной")
        if (s.contains("подъезжай") || s.contains("подзови") || (s.contains("призыв") && s.contains("машин")))
            return unsafeGate("语音召唤");                                                    // carControl@OP_VOICE_SUMMON
        return null;
    }

    // ---------------------------------------------------------------- driving / energy / suspension

    private static String zhDriveEnergy(String s, boolean off, String grade) {
        String dm = zhDriveModeOf(s);
        if (!dm.isEmpty() && !s.contains("музы") && !s.contains("звук") && !s.contains("подсветк")
            && (s.contains("режим") || s.contains("вожден") || s.contains("переключ")
                || s.contains("включи") || s.trim().equals("эко") || s.trim().equals("спорт")))
            return "切换到" + dm;                                                             // SET_DRIVING_MODE
        if (s.contains("электрическ") || s.contains("чисто электро") || s.contains("на электричестве"))
            return "切换到纯电模式";                                                          // SET_ENERGY_MODE
        if (s.contains("гибрид") || s.contains("увеличен запас")) return "切换到增程模式";
        if (s.contains("сохран") && s.contains("заряд")) return "切换到保电模式";
        if (s.contains("рекупер") && (grade.length() > 0 || s.contains("слабее") || s.contains("сильнее")))
            return grade.equals("MINUS") || s.contains("слабее") ? "能量回收调弱一点" : "能量回收调强一点"; // SET_ENERGY_RECOVERY; вопросы «что такое рекуперация» -> чат
        if (s.contains("подвеск") || s.contains("клиренс")) {
            if (s.contains("мягч") || s.contains("мягк")) return "悬架调软一点";               // SET_SUSP_DAMPING
            if (s.contains("жестч") || s.contains("жестк") || s.contains("жесч")) return "悬架调硬一点";
            if (s.contains("выровн")) return "一键调平";                                      // ONE_CLICK_LEVELING
            if (s.contains("ниже") || grade.equals("MINUS") || minusWordOf(s)) return "悬架降低一点"; // SET_SUSP_HEIGHT
            return "悬架升高一点";
        }
        if (s.contains("погрузк") || s.contains("погрузи")) return "打开轻松搬运模式";         // CARRY_GOODS_EASILY
        return null;
    }

    // ---------------------------------------------------------------- comfort / scenario / misc modes

    private static String zhComfortModes(String s, boolean off, int n) {
        String sc = zhScenarioOf(s);
        if (!sc.isEmpty()) return (off ? "关闭" : "打开") + sc;                               // SET_SCENARIO_MODE
        if ((s.contains("охран") && !s.contains("сохран"))  // "сОХРАНи песню" — не режим охраны!
            || s.contains("часов") || s.contains("сторож")
            || (s.contains("сигнализаци") && !s.contains("аварийн")))
            return (off ? "关闭" : "打开") + "哨兵模式";                                      // OP_SENTINEL_MODE ("поставь на сигнализацию")
        if (s.contains("приватн")) return (off ? "关闭" : "打开") + "隐私模式";               // OP_PRIVACY_MODE
        if (s.contains("мойк") && s.contains("режим")) return (off ? "关闭" : "打开") + "洗车模式"; // OP_CAR_WASH
        if (s.contains("встреч") && s.contains("режим")) return (off ? "关闭" : "打开") + "接驾模式"; // OP_PICKUP_MODE
        if (s.contains("подремать") || s.contains("поспать") || s.contains("вздремн") || s.contains("режим сна")
            || (s.contains("разбуди") && (s.contains("минут") || s.contains("час")))) {
            if (s.contains("полчаса")) return "我要睡30分钟";
            if (s.contains("еще") || s.contains("продли")) return "再睡" + (n > 0 ? n : 10) + "分钟"; // EXTENDED_NAP_MODE_TIME
            if (n > 0 && (s.contains("час") && n <= 12)) return "睡到" + n + "点叫我";         // SET_NAP_MODE_CLOCK
            if (n > 0) return "我要睡" + n + "分钟";                                          // SET_NAP_MODE_TIME
            return "我要睡一会儿";
        }
        if (s.contains("холодильник") || s.contains("морозилк") || s.contains("холодос")) {
            // проверено на авто: 把车载冰箱温度调到5度 / 零下5度 («минус пять» = 零下, ниже нуля)
            if (n >= 0 && n <= 20 && (s.contains("градус") || s.contains("на ") || s.contains("минус"))) {
                boolean neg = s.contains("минус") || s.contains("ниже нуля") || s.contains("-");
                return "把车载冰箱温度调到" + (neg ? "零下" : "") + n + "度";              // SET_REFRIGERATOR
            }
            // НЕ gradeOf(): «холодИЛЬНИК» содержит «холод» и даёт MINUS на любую фразу
            if (s.contains("холоднее") || s.contains("похолодн")) return "车载冰箱温度调低一点";
            if (s.contains("теплее") || s.contains("потепл")) return "车载冰箱温度调高一点";
            if (s.contains("подогрев") || s.contains("нагрев")) return "车载冰箱调到加热模式"; // SET_REFRIGERATOR_MODE
            // открой/закрой = ДВЕРЬ (冰箱门, SET_REFRIGERATOR_DOOR); включи/выключи = ПИТАНИЕ (车载冰箱)
            if (s.contains("откр")) return "打开冰箱门";                                     // open the fridge DOOR
            if (s.contains("закр")) return "关闭冰箱门";                                     // close the fridge DOOR
            // 车载冰箱, not bare 冰箱: bare fridge may classify as a smartHome device (cloud) and fail offline
            return off ? "关闭车载冰箱" : "打开车载冰箱";                                     // OP_REFRIGERATOR (power)
        }
        if (s.contains("напоминан")) {
            if (s.contains("телефон")) return (off ? "关闭" : "打开") + "手机遗忘提醒";        // OP_FORGET_PHONE_ALERT
            if (s.contains("рем")) return (off ? "关闭" : "打开") + "安全带未系提醒";          // OP_SEATBELT_UNFASTENED_ALERT
            return (off ? "关闭" : "打开") + "日程提醒";                                      // CONTROL_SCHEDULE_ALERTS
        }
        if (s.contains("звук") && s.contains("скорост")) return (off ? "关闭" : "打开") + "低速提示音"; // OP_LOW_SPEED_ALERT
        if (s.contains("виджет") || s.contains("минус один") || s.contains("минус-один"))
            return (off ? "关闭" : "打开") + "负一屏";                                        // OP_NEGATIVE_ONE_SCREEN
        if (s.contains("расширен") && s.contains("режим")) return (off ? "关闭" : "打开") + "拓展模式"; // OP_EXTENSION_MODE
        if (s.contains("персонаж") || s.contains("аватар")) return "换个精灵形象";            // CHANGE_SPRITE
        if (s.contains("тем") && (s.contains("темн") || s.contains("ночн"))) return "切换到夜间模式"; // «тёмная тема» = ночной режим
        if (s.contains("тем") && (s.contains("светл") || s.contains("дневн"))) return "切换到白天模式";
        if (s.contains("тему") || s.contains("тема оформлен")) return "换个主题";             // SWITCH_THEME
        if (s.contains("обои") || s.contains("заставк")) return "换个壁纸";                   // SWITCH_WALLPAPER
        if (s.contains("голос") && (s.contains("смени") || s.contains("друго"))) return "换个声音"; // SWITCH_VOICE_TONE
        if (s.contains("мужск") && s.contains("голос")) return "换成男声";                    // SET_VOICE_TONE
        if (s.contains("женск") && s.contains("голос")) return "换成女声";
        if (s.contains("слово пробужден") || s.contains("слово активац")) return "修改唤醒词"; // SET_WAKE_WORD
        if (s.contains("без пробужден") || s.contains("свободное общение"))
            return (off ? "关闭" : "打开") + "免唤醒";                                        // OP_NON_WUW
        return null;
    }

    // ---------------------------------------------------------------- connectivity / charging

    private static String zhConnectivity(String s, boolean off) {
        if ((s.contains("блютус") || s.contains("bluetooth"))
            && !s.contains("источник") && !s.contains("музык"))                               // "источник блютус" -> media source
            return (off ? "关闭" : "打开") + "蓝牙";                                          // OP_BT
        if ((s.contains("вайфай") || s.contains("wifi") || s.contains("wi-fi")) && !s.contains("раздай"))
            return (off ? "关闭" : "打开") + "WiFi";                                          // OP_WIFI ("раздай вайфай" -> хотспот ниже)
        if (s.contains("точку доступа") || s.contains("точка доступа")
            || (s.contains("раздай") && (s.contains("интернет") || s.contains("вайфай") || s.contains("wifi"))))
            return (off ? "关闭" : "打开") + "热点";                                          // OP_HOTSPOT ("интернет раздай")
        if ((s.contains("беспроводн") && s.contains("заряд"))
            || (s.contains("заряди") && s.contains("телефон"))) return (off ? "关闭" : "打开") + "无线充电"; // OP_WIRELESS_CHARGING
        if (s.contains("розетк") || (s.contains("отдач") && s.contains("энерг")) || s.contains("разрядк"))
            return (off ? "关闭" : "打开") + "对外放电";                                      // OP_DISCHARGE_POWER
        if (s.contains("не беспокоить")) return (off ? "关闭" : "打开") + "勿扰模式";         // OP_MOBILE_DND
        return null;
    }

    // ---------------------------------------------------------------- cameras / DVR

    private static String zhCameraDvr(String s, boolean off) {
        if (s.contains("регистратор")) {
            if (s.contains("альбом") || s.contains("записи")) return "打开行车记录仪相册";     // OP_DVR_ALBUM
            if (s.contains("кадр") || s.contains("скрин") || s.contains("фото")) return "行车记录仪拍照"; // CAPTUR_DVR
            if (off || s.contains("останови")) return "停止录像";                             // TAKE_DVR_REC (close)
            return "行车记录仪开始录像";                                                      // TAKE_DVR_REC
        }
        if (s.contains("сфотк") || s.contains("сделай фото") || s.contains("селфи")
            || s.contains("снимок") || (s.contains("фото") && s.contains("камер"))) return "拍照"; // TAKE_PHOTO
        if (s.contains("сними видео") || s.contains("запиши видео")) return "拍个视频";       // TAKE_VIDEO
        if (s.contains("360") || s.contains("круговой обзор") || s.contains("кругов обзор")
            || (s.contains("камер") && (numIn(s) == 360 || s.contains("триста шестьдесят"))))  // "камера триста шестьдесят"
            return off ? "关闭360" : "打开360全景影像";                                       // SET_SVM
        if (s.contains("задн") && s.contains("обзор") && s.contains("ассистент"))
            return (off ? "关闭" : "打开") + "后方视野辅助";                                  // OP_REAR_VIEW_ASSIST
        if (s.contains("камер") && s.contains("задн"))
            return off ? "关闭360" : "打开360全景影像";                                       // "камера заднего вида" -> SET_SVM
        if (s.contains("вид камеры") || (s.contains("переключи") && s.contains("камер")))
            return "切换摄像头视角";                                                          // SWITCH_CAMERA_VIEW
        return null;
    }

    // ---------------------------------------------------------------- navi

    private static String zhNavi(String s, boolean off, String grade) {
        if (s.contains("навигаци") || s.contains("навигат")) {
            if (s.contains("подсказк") || s.contains("громкост") || grade.length() > 0) {
                if (s.contains("выключ") || s.contains("замолч")) return "关闭导航播报";       // SET_BROADCAST
                if (s.contains("включ")) return "打开导航播报";
                if (s.contains("кратк")) return "切换到简洁播报";
                if (s.contains("подробн")) return "切换到详细播报";
                if (grade.equals("PLUS")) return "导航音量调大一点";                           // ADJ_BROADCAST
                if (grade.equals("MINUS")) return "导航音量调小一点";
            }
            if (s.contains("карточк")) return (off ? "关闭" : "打开") + "导航卡片";           // OP_NAVIGATION_CARD
            if (s.contains("домой")) return "回家";                                           // "навигатор домой"
            if (s.contains(" до ")) {                                                          // "навигатор до аэропорта"
                String d = tailAfter(s, " до ");
                if (!d.isEmpty()) return "导航去" + d;
            }
            if (off || s.contains("заверши") || s.contains("выйди")) return "退出导航";       // OP_NAVIGATION
            return "开始导航";
        }
        if (s.contains("маршрут") && (s.contains("останови") || s.contains("отмени")
            || s.contains("заверши") || s.contains("сбрось") || s.contains("удали")))
            return "退出导航";                                                                // "останови маршрут"
        if (s.contains("домой")) return "回家";                                              // LBS_ROUTE (bare "домой" = navigate, not desktop)
        if (s.contains("на работу") && (s.contains("поехали") || s.contains("поедем") || s.contains("едем")
            || s.contains("вези") || s.contains("отвези") || s.contains("маршрут"))) return "去公司";
        for (String k : new String[]{"поехали", "поедем в", "поедем на", "едем в", "едем на",
                "отвези", "вези", "гони в", "езжай в", "маршрут до", "доедем до", "доехать до"}) {
            if (s.contains(k)) {
                String poi = zhPoiOf(s);                       // "заедем на заправку" -> ближайшая АЗС
                if (!poi.isEmpty()) return "附近的" + poi;
                String dest = tailAfter(s, k);
                // junk tails are not destinations ("поехали уже", "поехали быстрее")
                if (!dest.isEmpty() && dest.length() >= 3
                    && !dest.matches("(уже|туда|сюда|потом|позже|быстрее|скорее|дальше|давай|ну)( .*)?"))
                    return "导航去" + dest;  // RU place name as-is — geocoder may need testing
                break;
            }
        }
        if (s.contains("хочу есть") || s.contains("хочу кушать") || s.contains("проголодал")) return "附近的餐厅"; // SEARCH_POI
        if (s.contains("найди") || s.contains("поищи") || s.contains("где ближайш") || s.contains("где ")
            || s.contains("заед") || s.contains("заскочим")
            || s.contains("хочу") || s.contains("хочется") || s.contains("нужн") || s.contains("надо")) {
            String poi = zhPoiOf(s);
            if (!poi.isEmpty()) return s.contains("по пути") || s.contains("по дороге")
                ? "沿途搜" + poi : "附近的" + poi;                                            // SEARCH_PASSBY / SEARCH_POI
        }
        if (s.contains("где мы") || s.contains("где я") || s.contains("наше местоположен")) return "我在哪里"; // ASK_LOCATION
        if (s.contains("куда мы едем") || s.contains("куда едем") || s.contains("пункт назначен")) return "目的地是哪里"; // ASK_NAVI_POI
        if ((s.contains("сколько") && !s.contains("пробег")
             && (s.contains("км") || s.contains("километр") || s.contains("до места")))
            || s.contains("далеко еще") || s.contains("еще далеко")) return "还有多远到";       // ASK_DISTANCE_LEFT
        if (s.contains("сколько ехать") || s.contains("когда приедем") || s.contains("время в пути")
            || s.contains("долго еще") || s.contains("еще долго")) return "还要多久到";        // ASK_TIME_LEFT
        if (s.contains("объезжай") || s.contains("объедь") || s.contains("объехать")) return "躲避拥堵"; // SET_ROUTE_PREFERENCE — before the traffic question
        if (s.contains("пробк") || (s.contains("как") && s.contains("дорог") && s.contains("впереди"))) return "前方路况怎么样"; // ASK_TRAFFIC_CONDITION
        if (s.contains("карту") || s.contains("карта")) {
            if (s.contains("приблиз") || s.contains("увелич") || grade.equals("PLUS")) return "放大地图"; // ZOOM_MAP_SIZE ("карту побольше")
            if (s.contains("отдали") || s.contains("уменьши") || grade.equals("MINUS")) return "缩小地图";
            if (s.contains("спутник")) return "切换到卫星地图";                                // SWITCH_MAP_LAYER
            if (s.contains("обычн") || s.contains("стандартн")) return "切换到标准地图";
            if (s.contains("север")) return "切换到正北朝上";                                  // SWITCH_VIEW_ORIENTATION
            if (s.contains("по курсу") || s.contains("по ходу")) return "切换到车头朝上";
            if (s.contains("3d") || s.contains("объемн")) return "切换到3D视角";
            if (off || s.contains("закрой")) return "关闭地图";                                // CLOSE_MAP_PAGE
            return "打开地图";                                                                // SWITCH_MAP_PAGE
        }
        if (s.contains("весь маршрут") || s.contains("полный маршрут")) return "查看全程路线"; // VIEW_FULL_ROUTE
        if (s.contains("без платных") || s.contains("платн дорог")) return "避开收费";        // SET_ROUTE_PREFERENCE
        if (s.contains("без шоссе") || s.contains("без трассы")) return "不走高速";
        if (s.contains("объезжай пробк") || s.contains("объедь пробк")) return "躲避拥堵";
        if (s.contains("сохрани") && (s.contains("место") || s.contains("адрес"))) return "收藏这个地点"; // COLLECT_ADDRESS
        if (s.contains("запомни дом") || (s.contains("дом") && s.contains("адрес"))) return "设置家的地址"; // SET_HOME_POI
        if (s.contains("запомни работу") || (s.contains("работ") && s.contains("адрес"))) return "设置公司地址"; // SET_COMPANY_POI
        return null;
    }

    // ---------------------------------------------------------------- phone

    private static String zhPhone(String s) {
        // "вызови такси/эвакуатор/помощь" — не звонок контакту, пусть уходит в чат
        boolean callVerb = s.contains("позвони") || s.contains("набери")
            || ((s.contains("вызови") || s.contains("звякни"))
                && !s.contains("такси") && !s.contains("эвакуатор") && !s.contains("помощь"));
        if (callVerb) {
            if (s.contains("еще раз") || s.contains("снова") || s.contains("повтори")) return "重拨"; // REDIAL
            String who = tailAfter(s, s.contains("позвони") ? "позвони"
                : s.contains("набери") ? "набери" : s.contains("вызови") ? "вызови" : "звякни");
            if (who.startsWith("номер")) who = who.substring(5).trim();
            if (who.matches("[\\d\\s+-]+")) return "拨打" + who.replaceAll("[^\\d+]", "");     // digits -> dial number
            if (!who.isEmpty()) return "给" + who + "打电话";  // CALL_REQUEST — RU contact name as-is
            return null;
        }
        if (s.contains("перезвони")) return "回拨";                                           // CALL_BACK
        if (s.contains("ответь") || s.contains("возьми трубку") || s.contains("прими звонок")) return "接听"; // ANSWER_CALL
        if (s.contains("сбрось") || s.contains("положи трубку") || s.contains("отбой")
            || s.contains("скинь вызов") || s.contains("скинь звонок")) return "挂断";        // HANG_UP
        if (s.contains("отклони") || s.contains("не отвечай")) return "拒接";                 // IGNORE_CALL
        if (s.contains("отмени вызов") || s.contains("отмени звонок")) return "取消拨打";     // CANCEL_DIAL
        if (s.contains("журнал") && s.contains("вызов") || s.contains("история звонк")) return "打开通话记录"; // RENDER_CALL_HISTORY
        if (s.contains("пропущенн")) return "播放未接来电";
        if (s.contains("контакт")) {
            if (s.contains("синхрон")) return "同步通讯录";                                   // SYNC_PHONE_CONTACT
            if (s.contains("найди")) {                                                        // SEARCH_PHONEBOOK
                String who = tailAfter(s, "найди");
                if (who.startsWith("в контактах")) who = who.substring("в контактах".length()).trim();
                if (!who.isEmpty()) return "查找联系人" + who;
            }
            return "打开通讯录";                                                              // RENDER_PHONE_CONTACT
        }
        return null;
    }

    // ---------------------------------------------------------------- sound / media

    private static String zhSoundMedia(String s, boolean off, String grade, int n) {
        if (s.contains("выключи звук") || s.contains("без звука") || s.contains("замолчи")
            || s.contains("убери звук") || s.contains("убрать звук")) return "静音";           // MUTE
        if (s.contains("включи звук") || s.contains("со звуком") || s.contains("верни звук")) return "取消静音"; // UNMUTE
        if (s.contains("хватит") || s.contains("перестань говорить") || s.contains("замолкни")) return "停止播报"; // STOP_BROADCAST
        // "на полную (катушку)" рядом со звуком/музыкой = volume max
        if ((s.contains("на полную") || s.contains("на всю катушку"))
            && (s.contains("звук") || s.contains("громк") || s.contains("музы") || s.contains("колонк")))
            return s.contains("музы") ? "音乐音量调到最大" : "音量调到最大";
        if ((s.contains("громкост") || s.contains("громче") || s.contains("тише")
             || (s.contains("звук") && (grade.length() > 0 || n >= 0)))
            && !s.contains("едь") && !s.contains("езжай")) {                                   // "тише едь" - не про громкость
            // "музыку громче" = громкость МЕДИА (音乐音量), иначе штатная система крутит
            // громкость голосового ассистента; generic 声音 — только без упоминания музыки
            boolean media = s.contains("музы") || s.contains("музон") || s.contains("песн")
                || s.contains("трек") || s.contains("медиа") || s.contains("радио");
            String vol = media ? "音乐音量" : "音量";
            // "громче на 5" is a RELATIVE step, not "set volume to 5"
            boolean rel = s.contains("громче") || s.contains("тише") || s.contains("прибав") || s.contains("убав");
            if (!rel && n >= 0 && n <= 40 && (s.contains("громкост") || s.contains("на "))) return vol + "调到" + n; // SET_VOLUME
            if (grade.equals("MAX") || s.contains("на полную")) return vol + "调到最大";
            if (grade.equals("MIN")) return vol + "调到最小";
            if (s.contains("громче") || grade.equals("PLUS") || plusWordOf(s))
                return media ? "音乐音量调大一点" : "声音调大一点";
            if (s.contains("тише") || grade.equals("MINUS") || minusWordOf(s))
                return media ? "音乐音量调小一点" : "声音调小一点";
            return null;
        }
        if (s.contains("что") && s.contains("играет")) return "这是什么歌";                   // "что играет" без слова "песня"
        if (s.contains("играй дальше") || s.trim().equals("играй")) return "播放";            // resume без слова "музыка"
        // media source switch before the track branch ("включи блютус музыку" is a source, not a song)
        if (s.contains("блютус") || s.contains("bluetooth")) return "切换到蓝牙音乐";          // CONTROL_MEDIA_SOURCE
        if (s.contains("юсб") || s.contains("usb") || s.contains("флешк")) return "切换到USB音乐";
        // radio station next/prev ("следующая станция")
        if (s.contains("станци") || s.contains("канал")) {
            if (s.contains("предыдущ") || s.contains("прошл")) return "上一首";
            if (s.contains("следующ") || s.contains("переключи") || s.contains("смени") || s.contains("друг")) return "下一首";
        }
        // "песен(ку)" - fleeting vowel; "музычку" needs the shorter root "музы"
        boolean track = s.contains("трек") || s.contains("песн") || s.contains("песен")
            || s.contains("композиц") || s.contains("музы") || s.contains("музон");
        if (track) {
            if (s.contains("следующ") || s.contains("дальше") || s.contains("переключи") || s.contains("смени")
                || s.contains("другую") || s.contains("другой трек")) return "下一首";        // NEXT_MEDIA
            if (s.contains("предыдущ") || s.contains("прошл")) return "上一首";               // PREVIOUS_MEDIA
            // "не нравится" ДО "нравится" — иначе отрицание попадает в лайк
            if (s.contains("убери из избранн") || s.contains("не нрав")) return "取消收藏这首歌"; // CANCEL_COLLECT_MEDIA
            if (s.contains("нрав") || s.contains("в избранное") || s.contains("лайк") || s.contains("сохрани"))
                return "收藏这首歌";                                                          // COLLECT_MEDIA ("понравилась песня")
            if (s.contains("скачай")) return "下载这首歌";                                    // DOWNLOAD_SONG
            if (s.contains("начал") || s.contains("заново")) return "重新播放";                // RESTART_PLAYBACK ("сначала", "в начало")
            if (s.contains("что") && (s.contains("играет") || s.contains("за"))) return "这是什么歌"; // ASK_CURRENT_MEDIA
            if (s.contains("текст")) return (off ? "关闭" : "打开") + "歌词";                 // OP_LYRICS
            if (s.contains("по кругу") || s.contains("повтор")) return "单曲循环";             // SET_PLAYBACK_MODE
            if (s.contains("случайн") || s.contains("перемешай") || s.contains("вперемешку")) return "随机播放";
            if (s.contains("по порядку")) return "顺序播放";
            // проверено на авто: голое 暂停 ставит текущее воспроизведение на паузу (работает оффлайн)
            if (off || s.contains("выключи") || s.contains("останови") || s.contains("стоп")) return "暂停";
            if (s.contains("пауз")) return "暂停";                                            // проверено на авто
            if (s.contains("продолж") || s.contains("играй")) return "播放";                   // проверено на авто: 播放 запускает проигрыватель
            if ((s.contains("включи") || s.contains("вруб") || s.contains("поставь") || s.contains("запусти")
                 || s.contains("давай") || s.contains("послушать") || s.contains("послушаем") || s.contains("хочу"))
                && (s.contains("музы") || s.contains("музон")))
                return "播放";  // проверено на авто: голое 播放 запускает проигрыватель (оффлайн)
            String w = s.trim();
            if (w.equals("музыку") || w.equals("музыка") || w.equals("музычку")) return "播放"; // bare "музыку!"
            String what = tailAfter(s, "включи");
            if (what.isEmpty()) what = tailAfter(s, "поставь");
            if (!what.isEmpty()) return "我想听" + what;  // free media item as-is
        }
        if (s.contains("перемотай")) {
            if (s.contains("начал")) return "重新播放";                                       // "перемотай в начало"
            if (n > 0 && s.contains("минут")) return "跳到第" + n + "分钟";                    // SET_PLAYBACK_TIME_POINT
            if (n > 0) return (s.contains("назад") ? "快退" : "快进") + n + "秒";              // ADJUST_PLAYBACK_PROGRESS
            return s.contains("назад") ? "快退15秒" : "快进15秒";
        }
        if (s.contains("скорост") && s.contains("воспроизведен")) {
            if (s.contains("полтора") || s.contains("1.5")) return "1.5倍速播放";              // SPEED_PLAY
            if (n == 2) return "2倍速播放";
            return "1倍速播放";
        }
        if (s.contains("радио")) {
            if (n > 0 && (s.contains("частот") || s.contains("волн") || s.contains("fm"))) return "收音机调到" + n + "兆赫"; // OP_RADIO_HARDWARE
            return off ? "关闭收音机" : "打开收音机";
        }
        if (s.contains("источник")) {
            if (s.contains("блютус")) return "切换到蓝牙音乐";                                 // CONTROL_MEDIA_SOURCE
            if (s.contains("юсб") || s.contains("usb") || s.contains("флешк")) return "切换到USB音乐";
            if (s.contains("онлайн")) return "切换到在线音乐";
        }
        if (s.contains("случайный порядок") || s.contains("перемешай")) return "随机播放";    // без слова "песня"
        if (s.contains("плейлист") || s.contains("список воспроизведен")) return "打开播放列表"; // CONTROL_PLAYLIST
        if (s.contains("избранн") && (s.contains("песн") || s.contains("трек") || s.contains("музы")))
            return "打开我的收藏";                                                            // OP_MEDIA_FAVORITES_LIST ("покажи избранные песни")
        if (s.contains("истори") && s.contains("прослушив")) return "打开播放历史";           // OP_MEDIA_HISTORY
        if (s.contains("звук на водителя") || (s.contains("звуков") && s.contains("сцен")))
            return s.contains("весь") || s.contains("всех") ? "音场切换到全车" : "音场切换到主驾"; // SWITCH_SOUND_FIELD
        if (s.contains("качество звука") || s.contains("аудиофил")) return "切换到高音质";    // SET_SOUND_QUALITY
        if (s.contains("улучшени звука") || s.contains("улучшение звука")) return (off ? "关闭" : "打开") + "音质增强"; // OP_QUALITY_ENHANCE
        if (s.contains("караоке")) return (off ? "关闭" : "打开") + "无麦K歌";                // OP_NO_MIC_KARAOKE
        return null;
    }

    // ---------------------------------------------------------------- vehicleInfo

    private static String zhVehicleInfo(String s) {
        if ((s.contains("сколько") && (s.contains("заряд") || s.contains("батаре")))
            || (s.contains("заряд") && (s.contains("какой") || s.contains("уровень") || s.contains("остал")
                || s.contains("покажи") || s.contains("скажи")))
            || s.contains("что по заряд") || s.contains("как там заряд")
            || s.contains("запас хода") || s.contains("сколько бензина") || s.contains("остаток топлива"))
            return "还有多少电";                                                              // REMAINING_POWER
        if (s.contains("давлени") && (s.contains("шин") || s.contains("колес"))) return "胎压是多少"; // TIRE_PRESSURE
        if (s.contains("пробег")) return "现在的里程是多少";                                  // CURRENT_MILEAGE
        if (s.contains("воздух") && s.contains("салон")) return "车内空气质量怎么样";         // IN_CAR_AIR_QUALITY
        if (s.contains("пожалов") || s.contains("фидбек") || s.contains("обратн связ")) return "我要反馈问题"; // FEEDBACK
        return null;
    }

    // ---------------------------------------------------------------- smartHome (all cloud)

    private static String zhSmartHome(String s, boolean off, int n) {
        if (!s.contains("дома") && !s.contains("домашн") && !s.contains("в квартире")) return null;
        if (s.contains("кондиционер")) {
            if (n >= 16 && n <= 33) return "家里空调调到" + n + "度";                          // SET_HOME_AIR_CONDITIONER
            return (off ? "关闭" : "打开") + "家里的空调";
        }
        if (s.contains("свет")) return (off ? "关闭" : "打开") + "家里的灯";                  // SET_HOME_LIGHT
        if (s.contains("штор")) return (off ? "关闭" : "打开") + "家里的窗帘";                // SET_HOME_CURTAIN
        if (s.contains("очистител")) return (off ? "关闭" : "打开") + "家里的空气净化器";     // SET_HOME_AIR_PURIFIER
        if (s.contains("осушител")) return (off ? "关闭" : "打开") + "家里的除湿机";          // SET_HOME_DEHUMIDIFIER
        if (s.contains("пылесос")) return "让扫地机器人开始打扫";                             // SET_HOME_ROBOTIC_VACUUM
        return null;
    }

    // ---------------------------------------------------------------- appPageControl

    private static String zhAppUi(String s, boolean off) {
        if (s.contains("главный экран") || s.contains("рабочий стол") || s.contains("на главную")) return "返回桌面"; // BACK_DESKTOP ("домой" -> navi 回家)
        if ((s.contains("ассистент") || s.contains("помощник")) && (off || s.contains("выйди") || s.contains("закройся")))
            return "退出语音";                                                                // EXIT_VOICE_ASSIST
        if (s.contains("отстань") || s.contains("уйди") || s.contains("отвали")
            || s.trim().equals("закройся")) return "退下";                                    // EMOTION_EXIT_ASSIST
        if (s.contains("предыдущ") && (s.contains("экран") || s.contains("страниц"))) return "返回上一页"; // GO_BACK_PAGE
        if (s.contains("внешн") && (s.contains("голос") || s.contains("динамик")))
            return (off ? "关闭" : "打开") + "车外语音";                                      // OP_EXTERNAL_VOICE
        if (s.contains("галере") || (s.contains("фотограф") && s.contains("открой"))) return "打开相册"; // OP_PHOTO_ALBUM
        if (s.contains("настройк")) {
            if (s.contains("подсветк")) return "打开氛围灯设置";                              // CONTROL_AMBIENT_LIGHTING_PAGE
            if (s.contains("зеркал")) return "打开后视镜设置";                                // CONTROL_REAR_MIRROR_PAGE
            return "打开设置";                                                                // CONTROL_APP
        }
        if (s.contains("сценари") && s.contains("открой")) return "打开场景模式页面";         // CONTROL_SCENARIO_PAGE
        if (s.contains("приложени") || s.contains("открой") || s.contains("запусти")) {
            String app = zhAppOf(s);
            if (!app.isEmpty()) return (off || s.contains("закрой") ? "关闭" : "打开") + app; // CONTROL_APP
        }
        return null;
    }

    // ---------------------------------------------------------------- generalControl (bare context words — LAST)

    private static String zhGeneralUi(String s, int n) {
        String w = s.trim();
        if (w.equals("да") || w.equals("подтверждаю") || w.equals("согласен") || w.equals("давай")) return "确认"; // CONFIRM
        if (w.equals("нет") || w.equals("не надо") || w.equals("отмена")) return "不用了";     // CONFIRM_NO
        if (w.equals("назад") || w.equals("вернись")) return "返回";                          // BACK
        if (w.equals("продолжай") || w.equals("продолжи")) return "继续";                     // CONTINUE
        if (s.contains("пауз") || w.equals("подожди")) return "暂停";                         // PAUSE ("поставь на паузу")
        if (w.equals("следующий") || w.equals("дальше") || w.equals("следующая")) return "下一个"; // NEXT
        if (w.equals("предыдущий") || w.equals("предыдущая")) return "上一个";                // PREVIOUS
        if (w.equals("закрой все")) return "全部关闭";                                        // CLOSE_ALL
        int i = ordinalIn(s);
        if (i > 0) {
            if (s.contains("страниц")) return "第" + i + "页";                                // PAGE_SELECTION
            if (s.contains("удали")) return "删除第" + i + "个";                              // DELETE_INDEX
            if (s.contains("позвони") || s.contains("набери")) return "打第" + i + "个";      // LIST_SELECTION_PHONE
            if (s.contains("включи") || s.contains("песн") || s.contains("трек")) return "播放第" + i + "首"; // LIST_SELECTION_MEDIA
            if (s.contains("выбери") || w.matches("(перв|втор|трет|четверт|пят)\\S*")) return "第" + i + "个"; // LIST_SELECTION
        }
        if (s.contains("листай") || s.contains("следующая страница")) return "下一页";        // TURN_PAGE
        if (s.contains("предыдущая страница")) return "上一页";
        if (s.contains("в избранное") || w.equals("сохрани")) return "收藏";                  // COLLECT
        if (s.contains("убери из избранного")) return "取消收藏";                             // CANCEL_COLLECT
        if (s.contains("избранное") && s.contains("открой")) return "打开收藏夹";             // OP_COLLECTION
        return null;
    }

// ---------------------------------------------------------------------------------------------
// NOTE on cloud/chat domains (weather, lifeService, carKnowledge, xiaoAnWorldview,
// sceneArrangement, memorizeUserInfo): these are useCloud — an unmatched phrase falls through to
// null and handlePhrase() routes it to the chat LLM, which is the intended behaviour. Weather
// questions can optionally be pre-translated (今天天气怎么样 etc., see RESULT.md) when the stock
// cloud stack is reachable.
// NOTE: langOf() from the TODO list is intentionally NOT added — the 328-intent catalog contains
// no system-language intent to feed it.

    /** Normalized color code -> Chinese color word. */
    private static String zhColor(String c) {
        if ("RED".equals(c)) return "红色";
        if ("BLUE".equals(c)) return "蓝色";
        if ("GREEN".equals(c)) return "绿色";
        if ("WHITE".equals(c)) return "白色";
        if ("YELLOW".equals(c)) return "黄色";
        if ("PURPLE".equals(c)) return "紫色";
        if ("ORANGE".equals(c)) return "橙色";
        if ("PINK".equals(c)) return "粉色";
        return "";
    }

    /** ru2zh voice/test routing: RU phrase → Chinese command → stock pipeline; else offline reply. */
    public static void handlePhraseZh(String ru) {
        if (ru == null || ru.trim().isEmpty()) return;
        String low = ru.toLowerCase();
        // Voice on/off for our TTS engine (ASR/commands are independent of tts_config, so BOTH work):
        //   "верни заводскую озвучку" → stock TTS;  "включи русскую озвучку" → our TTS. Effect after restart.
        if (isRestoreVoice(low)) {
            speak("Возвращаю заводскую озвучку. Перезапустите ассистента.");
            restoreTts(); showOnScreen("Заводская озвучка (перезапустите ассистента)", TYPE_FEEDBACK); return;
        }
        if (isEnableVoice(low)) {
            ensureTtsRegistered();
            speak("Русская озвучка включена. Перезапустите ассистента.");
            showOnScreen("Русская озвучка (перезапустите ассистента)", TYPE_FEEDBACK); return;
        }
        String zh = ru2zh(ru);
        if (zh != null) { Log.i(TAG, "ru2zh: [" + ru + "] -> " + zh); injectZh(zh); return; }
        // Free-form (weather / joke / general chat): the rule map missed. Translate RU->ZH online and
        // feed the stock cloud NLU (native, device-signed) so the real Changan Dubhe answers.
        if (CLOUD_MT) {
            final String q = ru;
            new Thread(new Runnable() { public void run() {
                String mt = Translate.ruToZh(q);
                if (mt != null && !mt.isEmpty()) { Log.i(TAG, "mt ru2zh: [" + q + "] -> " + mt); cloudAsk(mt); }
                else if (OFFLINE_ONLY) showOnScreen("Не поняла команду", TYPE_FEEDBACK);
                else sendToCloud(q);
            }}).start();
            return;
        }
        if (OFFLINE_ONLY) { Log.i(TAG, "ru2zh: unrecognized: " + ru); showOnScreen("Не поняла команду", TYPE_FEEDBACK); }
        else { Log.i(TAG, "ru2zh: -> chat: " + ru); sendToCloud(ru); }
    }

    /** Extract a plain integer from the phrase: digits first, else a Russian number word. */
    private static int numIn(String s) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1)); // capped at 3 digits -> no overflow
        } catch (Throwable ignored) {}
        return ruNum(s);
    }

    /*
     * ============================================================================================
     *  ЕЩЁ НЕ РЕАЛИЗОВАНО — карта оставшихся интентов для будущего «настоящего» bridge/архитектуры.
     * ============================================================================================
     *  Ниже — весь оставшийся оффлайн-поддерживаемый каталог (useLocalRule, см.
     *  stand/knowledge/09-offline-coverage.md и nlu_catalog/intent_label.txt). Имена даны как
     *  canonical `domain@INTENT`, чтобы их можно было напрямую подставлять в arbFlat(...).
     *
     *  Текущий mapCommand — плоский if-else PoC. Логичная замена (идея на будущее):
     *    - декларативная таблица правил {regex/keywords -> domain@intent + slot-extractors},
     *    - отдельные «слот-парсеры» (zone/grade/number/color/percent/mode/target),
     *    - реестр интентов с ожидаемыми слотами (валидация до отправки),
     *    - раздельные бэкенды актуации: (A) arbitration-inject (текущий), (B) прямой CarProperty
     *      (нужны car-permissions в манифесте, см. doc 08 Option B), (C) CaSdkManager in-process.
     *  ⚠️ Значения зон/режимов (DRIVER/HEATING/…) пока не подтверждены на железе — возможно
     *     обработчик ждёт китайские литералы. Проверять на авто (echo распознан, но не исполняется).
     *
     *  ---- КЛИМАТ (доп.) ----
     *   carControl@SET_AIR_CONDITIONER_AIRFLOW / SET_AIR_CONDITIONER_AIR_BLOW /
     *     SWITCH_AIR_CONDITIONER_AIRFLOW  — направление обдува (лицо/ноги/стекло/комбо)
     *   carControl@SWITCH_AIR_CIRCULATION  — переключить рециркуляцию/забор
     *   carControl@TEMPERATURE_SYNC        — синхронизировать климат-зоны  («синхронизируй климат»)
     *   carControl@SET_FRAGRANCE           — выбрать аромат/интенсивность (SWITCH_FRAGRANCE уже есть)
     *
     *  ---- СИДЕНЬЯ (позиция/эргономика) ----  (SET_SEAT_HEAT/VENTILATE/MASSAGE уже реализованы)
     *   carControl@ADJUST_SEAT_POSITION          — двигать сиденье вперёд/назад/выше/ниже
     *   carControl@ADJUST_SEAT_BACKREST_POSITION / OP_SEAT_BACKREST_POSITION — спинка (наклон)
     *   carControl@ADJUST_SEAT_CUSHION_POSITION  — подушка
     *   carControl@ADJUST_LUMBAR_POSITION        — поясничный подпор
     *   carControl@ADJUST_LEG_SUPPORT_POSITION / ADJUST_LEG_SUPPORT_LENGTH — подколенный упор
     *   carControl@SET_SEAT_FOOTREST             — подставка для ног (реклайнер)
     *   carControl@SWITCH_SEAT_MASSAGE_MODE      — режим массажа
     *   carControl@SWITCH_SAVE_SEAT_POSITION     — сохранить/вызвать позицию памяти
     *   carControl@EXCLUSIVE_FRONT_PASSENGER     — режим «королевы» переднего пассажира
     *
     *  ---- СВЕТ (доп.) ----  (OP_DIPPED_BEAM/OP_HIGH_BEAM/OP_WARNING_LIGHT/OP_READ_LIGHTS есть)
     *   carControl@OP_OUTLINE_LIGHT / OP_SIDE_LIGHTS — габариты/боковые
     *   carControl@OP_REAR_FOG_LIGHT   — задний противотуманный
     *   carControl@OP_TAILLIGHT        — задние фонари
     *   carControl@OP_TRUNK_LIGHT      — свет багажника
     *   carControl@SET_DIPPED_BEAM_HEIGHT — корректор фар
     *   carControl@CONTROL_LIGHT_SHOW  — световое шоу
     *
     *  ---- ПОДСВЕТКА САЛОНА (доп.) ----  (OP_MOOD_LIGHTS/BRIGHTNESS/COLOR есть)
     *   carControl@SET_MOOD_LIGHTS_MODE / SET_MOOD_LIGHTS_THEME / SET_MOOD_LIGHTS_GRADIENT
     *   carControl@SWITCH_M_L_EFFECT / SWITCH_M_L_COLOR — переключить эффект/цвет
     *
     *  ---- ЗЕРКАЛА / РУЛЬ ----  (OP_STEER_WARM/REAR_MIRROR_WARM есть)
     *   carControl@ADJ_REARVIEW_MIRROR    — регулировка зеркал (наклон)
     *   carControl@OP_REAR_MIRROR_CONTROL / OP_REAR_MIRROR_AUTO — сложить/авто-затемнение
     *   carControl@ADJ_STEER_DIRECTION    — вылет/наклон руля
     *   carControl@SET_STEER_WARM         — уровень обогрева руля (0..N)
     *   carControl@SET_STEERING_STYLE     — тип усилителя (комфорт/спорт)
     *
     *  ---- ДВОРНИКИ ----  (SET_WIPER_SPEED/WASH_WIPER есть)
     *   carControl@SET_WIPER_SENSITIVITY  — чувствительность авто-режима
     *   carControl@REPAIR_WIPER           — сервисное положение
     *
     *  ---- ОКНА / КУЗОВ (доп.) ----  (SET_CAR_WINDOW/SUNROOF/SUN_SHADE/WINDOW_LOCK/TRUNK/FRUNK есть)
     *   carControl@SET_WINDOW_SUN_SHADE   — шторки окон
     *   carControl@OP_LOCK_CLOSE_WINDOW   — дозакрытие окон при блокировке
     *   carControl@OP_RAIN_CLOSE_WINDOW   — автозакрытие при дожде
     *   carControl@OP_UPPER_TAILGATE / OP_LOWER_TAILGATE / OP_SPLIT_TAILGATE — секции двери багажника
     *   carControl@OP_REAR_COMPARTMENT    — задний отсек/шторка
     *   carControl@OP_FRUNK_LOCK          — замок переднего багажника
     *   carControl@OP_CHARGING_PORT_COVER — лючок зарядки
     *   carControl@OP_FUEL_TANK_CAP / OP_FUEL_TANK_LOCK — лючок/замок бака
     *   carControl@OP_CHILD_SAFETY_LOCK   — детский замок
     *   carControl@OPEN_ALL_ONE_KEY / CLOSE_ALL_ONE_KEY — всё открыть/закрыть одной командой
     *   carControl@OP_APPROACH_UNLOCK / OP_LEAVE_LOCK — авто-замок по подходу/отходу
     *   carControl@OP_COMFORTABLE_ENTRY / OP_EASY_ACCESS — комфортный вход/выход
     *
     *  ---- КЛИМАТ-ХОЛОДИЛЬНИК ----
     *   carControl@OP_REFRIGERATOR / SET_REFRIGERATOR / SET_REFRIGERATOR_MODE / SET_REFRIGERATOR_DOOR
     *
     *  ---- РЕЖИМЫ ЕЗДЫ / ПОДВЕСКА / ЭНЕРГИЯ ----
     *   carControl@SET_DRIVING_MODE       — режим движения (эко/спорт/снег…)
     *   carControl@SET_SCENARIO_MODE      — сценарный режим
     *   carControl@SET_ENERGY_MODE / SET_ENERGY_RECOVERY / SET_POWER_TYPE — рекуперация/тип тяги
     *   carControl@SET_SUSP_HEIGHT / SET_SUSP_DAMPING — высота/жёсткость подвески
     *   carControl@ONE_CLICK_LEVELING     — выравнивание кузова
     *   carControl@CARRY_GOODS_EASILY     — режим погрузки (опустить подвеску)
     *
     *  ---- ЭКРАН / HUD (доп.) ----  (SET_DISPLAY_BRIGHTNESS/OP_HUD/SET_HUD_BRIGHTNESS/HEIGHT есть)
     *   carControl@SET_DISPLAY_MODE / SET_DISPLAY_ORIENTATION / SET_DISPLAY_COLOR_TEMPERATURE
     *   carControl@SET_DISPLAY_EYE_PROTECTION / SET_FONT_SIZE / OP_AUTO_DISPLAY_BRIGHTNESS
     *   carControl@OP_DISPLAY_POWER / OP_DISPLAY_CLEAN / DISPLAY_SLEEP / DISPLAY_UNSLEEP
     *   carControl@SET_PDISPLAY_ANGLE     — угол пассажирского экрана
     *   carControl@SET_HUD_ANGLE / SET_HUD_MODE / SWITCH_HUD_DISPLAY_MODE / SWITCH_HUD_COLOR_MODE
     *
     *  ---- ЗВУК (доп.) ----  (SET_VOLUME/MUTE/UNMUTE есть)
     *   carControl@SWITCH_SOUND_FIELD     — звуковое поле (водитель/весь салон)
     *   carControl@OP_QUALITY_ENHANCE     — улучшение качества звука
     *   carControl@SET_ALARM_TONE         — тон сигнала
     *   carControl@STOP_BROADCAST         — прекратить озвучку (barge-in)
     *
     *  ---- КАМЕРЫ / DVR / ОБЗОР ----
     *   carControl@TAKE_PHOTO / TAKE_VIDEO / TAKE_DVR_REC / CAPTUR_DVR — фото/видео/регистратор
     *   carControl@SET_SVM                — круговой обзор (360)
     *   carControl@OP_STREAM_MEDIA_REAR_VIEW / OP_REAR_VIEW_ASSIST — стрим-зеркало/помощь
     *   appPageControl@SWITCH_CAMERA_VIEW — переключить ракурс камеры
     *
     *  ---- СВЯЗЬ / ПИТАНИЕ / РЕЖИМЫ ----
     *   carControl@OP_BT / OP_WIFI / OP_HOTSPOT / OP_WIRELESS_CHARGING / OP_DISCHARGE_POWER (V2L)
     *   carControl@OP_SENTINEL_MODE       — режим часового
     *   carControl@OP_PRIVACY_MODE / OP_MOBILE_DND — приватность / не беспокоить
     *   carControl@OP_CAR_WASH / OP_PICKUP_MODE / OP_EXTENSION_MODE — мойка / подача / кемпинг
     *   carControl@OP_NO_MIC_KARAOKE      — караоке без микрофона
     *   carControl@SET_NAP_MODE_TIME / SET_NAP_MODE_CLOCK / EXTENDED_NAP_MODE_TIME — режим отдыха
     *
     *  ---- ОПОВЕЩЕНИЯ / БЕЗОПАСНОСТЬ ----
     *   carControl@OP_SEATBELT_UNFASTENED_ALERT / OP_FORGET_PHONE_ALERT / OP_LOW_SPEED_ALERT
     *   carControl@CONTROL_SCHEDULE_ALERTS — напоминания/расписание
     *
     *  ---- ПЕРСОНАЛИЗАЦИЯ АССИСТЕНТА / ТЕМЫ ----
     *   carControl@CHANGE_SPRITE / SET_NICKNAME / SET_WAKE_WORD — аватар/имя/слово пробуждения
     *   carControl@SET_VOICE_TONE / SWITCH_VOICE_TONE — голос/тон TTS
     *   carControl@SET_THEME / SWITCH_THEME / SET_WALLPAPER / SWITCH_WALLPAPER — темы/обои
     *   carControl@REGISTER_VOICEPRINT    — регистрация голосового отпечатка
     *   carControl@OP_VOICE_SUMMON        — призыв авто (парковка по запросу)
     *   carControl@OP_NON_WUW             — режим без слова пробуждения (free-talk)
     *   carControl@GENERATE_THEMED_PODCAST — генерация подкаста (вероятно облако/LLM)
     *   carControl@VEHICLE_UNDEFINED      — заглушка, не реализовывать
     *
     *  ---- МЕДИА (расширенное управление, mediaControl@) ----  (NEXT/PREVIOUS_MEDIA есть)
     *   PAUSE_PLAYBACK / RESUME_PLAYBACK / RESTART_PLAYBACK / END_PLAYBACK — пауза/продолжить/стоп
     *   ADJUST_PLAYBACK_PROGRESS / SET_PLAYBACK_TIME_POINT / SPEED_PLAY — перемотка/скорость
     *   SET_PLAYBACK_MODE (повтор/шафл) / SWITCH_MEDIA_AUDIO / SET_SOUND_QUALITY / SET_DEFINITION
     *   COLLECT_MEDIA / CANCEL_COLLECT_MEDIA / OP_MEDIA_FAVORITES_LIST — избранное
     *   CONTROL_PLAYLIST / LIST_SELECTION_MEDIA / ASK_CURRENT_MEDIA / OP_LYRICS / DOWNLOAD_SONG
     *   media@CONTROL_MEDIA_SOURCE / PLAY_AUDIO_PROGRAM / OP_RADIO_HARDWARE — источник/радио
     *
     *  ---- ТЕЛЕФОН (phone@, требует интеграции с диалером) ----
     *   CALL_REQUEST / ANSWER_CALL / HANG_UP / IGNORE_CALL / REDIAL / CALL_BACK / CANCEL_DIAL
     *   SEARCH_PHONEBOOK / SYNC_PHONE_CONTACT / RENDER_PHONE_CONTACT / RENDER_CALL_HISTORY / LIST_SELECTION_PHONE
     *
     *  ---- НАВИГАЦИЯ (navi@, нужна интеграция с картой; в РФ — Яндекс/OsmAnd, см. changan-navigation) ----
     *   OP_NAVIGATION / LBS_ROUTE / SEARCH_POI / SET_HOME_POI / SET_COMPANY_POI / COLLECT_ADDRESS
     *   ASK_LOCATION / ASK_DISTANCE_LEFT / ASK_TIME_LEFT / ASK_TRAFFIC_CONDITION — запросы (нужен ответ TTS)
     *   ZOOM_MAP_SIZE / SWITCH_VIEW_ORIENTATION / SWITCH_MAP_LAYER / SET_BROADCAST / VIEW_FULL_ROUTE …
     *
     *  ---- ОБЩЕЕ УПРАВЛЕНИЕ ДИАЛОГОМ (generalControl@, контекстное — нужен стейт диалога) ----
     *   CONFIRM / CONFIRM_NO / CANCEL / CONTINUE / BACK / NEXT / PREVIOUS / PAUSE / OPEN / CLOSE / CLOSE_ALL
     *   LIST_SELECTION / PAGE_SELECTION / CATEGORY_SELECT / DELETE_INDEX / TURN_PAGE / COLLECT — выбор из списка/карточки
     *   appPageControl@BACK_DESKTOP / GO_BACK_PAGE / CONTROL_APP / CONTROL_PAGE / EXIT_VOICE_ASSIST — навигация по UI
     *
     *  ---- ЗАПРОСЫ О МАШИНЕ (vehicleInfo@, read-back -> ответ голосом/карточкой) ----
     *   CURRENT_MILEAGE / REMAINING_POWER / TIRE_PRESSURE / IN_CAR_AIR_QUALITY / RESPONSE_TIME_INQUIRY
     *
     *  ---- АВТОПИЛОТ / ADAS (autoPilot@, БЕЗОПАСНОСТЬ — вероятно заблокировано/требует подтверждений) ----
     *   OP_ACC / OP_IACC / OP_NCA / OP_AUTO_DRIVE / OP_AUTO_PARKING / PARKING / OP_FOLLOW_CAR
     *   SET_CRUISE_CAR_SPEED / SET_CRUISE_FOLLOW_GAP / KEEP_LANE_DRIVE / SPEED_LIMIT_CONTROL …
     *
     *  ---- РАЗГОВОРНОЕ / ЗНАНИЯ (useCloud -> наш LLM-редирект, НЕ arbitration; см. doc 02/09) ----
     *   weather@* / carKnowledge@* / lifeService@* / xiaoAnWorldview@* / sceneArrangement@* /
     *   memorizeUserInfo@*  — сейчас всё, что вернуло mapCommand==null, уходит в sendToCloud().
     * ============================================================================================
     */

    /**
     * Russian phrase -> native arbitration JSON (carControl@INTENT + flat slots), or null for chat.
     * Slots are emitted FLAT on nluResults[0] (the contract NluManager.onArbitrationResult reads:
     * nlpBean.semantic.slots = nluResults[0]; adapters read slots.optString(<name>)). Zones/grades use
     * the normalized codes the stock handlers key on. SA only DISPATCHES; the perm-holding SDA apps
     * (airconditioner/chaircontrol/...) actuate — so no car permission is needed in SA's manifest.
     * Covers the everyday offline families (all useLocalRule). See stand/knowledge/09-offline-coverage.md.
     */
    static String mapCommand(String t) {
        String s = t.toLowerCase().replace('ё', 'е');
        String rid = "vosk-" + System.currentTimeMillis();
        boolean on = isOn(s), off = isOff(s);
        // zone: use the one spoken in the phrase; if none, fall back to the detected speaking seat.
        String zone = zoneOf(s);
        if (zone.isEmpty()) zone = zoneCode(wakeZone);
        String grade = gradeOf(s);
        java.util.HashMap<String,String> sl = new java.util.HashMap<>();

        // ---------- CLIMATE ----------
        // temperature (absolute number wins; else relative grade)
        if (s.contains("температур") || s.contains("градус")
            || ((s.contains("теплее") || s.contains("холодн")) && !s.contains("сиден") && !s.contains("руль") && !s.contains("зеркал"))) {
            int n = numIn(s);
            sl.clear();
            if (n >= 16 && n <= 33) { sl.put("temperature", String.valueOf(n)); }
            else if (!grade.isEmpty()) { sl.put("direction", grade); }
            else return null;
            if (!zone.isEmpty()) sl.put("target", zone);
            return arbFlat(rid, t, "carControl", "SET_AIR_CONDITIONER_TEMPERATURE", sl);
        }
        // fan speed / air volume (exclude windshield-defrost and seat-vent, handled elsewhere)
        boolean fanCtx = !s.contains("лобов") && !s.contains("стекл") && !s.contains("сиден") && !s.contains("кресл");
        if (fanCtx && (s.contains("обдув") || s.contains("вентилятор")
            || (s.contains("воздух") && (s.contains("больш")||s.contains("мень")||numIn(s)>0))
            || ((s.contains("скорост")||s.contains("сил")) && s.contains("вент")))) {
            sl.clear();
            int n = numIn(s);
            if (n >= 1 && n <= 7) sl.put("gear", String.valueOf(n));
            else if (!grade.isEmpty()) sl.put("direction", grade);
            else if (on) sl.put("action", "OPEN"); else if (off) sl.put("action", "CLOSE");
            if (!sl.isEmpty()) return arbFlat(rid, t, "carControl", "SET_AIR_CONDITIONER_FAN_SPEED", sl);
        }
        // defrost (windshield / rear glass)
        if (s.contains("обдув лоб") || s.contains("обдув стек") || s.contains("обогрев стек")
            || s.contains("обогрев лоб") || s.contains("разморозк") || s.contains("отпот")) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            if (s.contains("задн") || s.contains("заднего")) sl.put("target", "REAR");
            return arbFlat(rid, t, "carControl", "SET_DEFROST", sl);
        }
        // recirculation
        if (s.contains("рециркул") || (s.contains("забор") && s.contains("воздух")) || s.contains("циркуляц")) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_AIR_CIRCULATION", sl);
        }
        // AC mode: heating / cooling / auto
        if (s.contains("режим") && (s.contains("охлажд")||s.contains("обогрев")||s.contains("авто")||s.contains("эко"))) {
            String mode = s.contains("охлажд")||s.contains("холод") ? "COOLING"
                        : s.contains("обогрев")||s.contains("тепл") ? "HEATING"
                        : s.contains("эко") ? "ECO" : "AUTO";
            sl.clear(); sl.put("mode", mode);
            return arbFlat(rid, t, "carControl", "SET_AIR_CONDITIONER_MODE", sl);
        }
        // climate on/off
        if (s.contains("кондиционер") || s.contains("климат") || (s.contains("обдув") && (on||off))) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_AIR_CONDITIONER", sl);
        }

        // ---------- WINDOWS / ROOF / SHADE ----------
        if (s.contains("окн") || (s.contains("стекл") && (s.contains("опусти")||s.contains("подним")||s.contains("откр")||s.contains("закр")))) {
            sl.clear();
            int pct = numIn(s);
            boolean pctPhrase = s.contains("процент") || s.contains("%");
            if (s.contains("наполовину")) sl.put("value", "50");
            else if (pctPhrase && pct >= 0 && pct <= 100) sl.put("value", String.valueOf(pct));
            else {
                // "опусти/открой окно" => OPEN (lower glass); "подними/закрой" => CLOSE.
                // NB: don't use isOff() here — it treats "опусти" as off, but lowering a window is OPEN.
                boolean close = s.contains("подним") || s.contains("закр") || s.contains("выключ") || s.contains("отключ");
                sl.put("action", close ? "CLOSE" : "OPEN");
            }
            if (!zone.isEmpty()) sl.put("name", zone);
            return arbFlat(rid, t, "carControl", "SET_CAR_WINDOW", sl);
        }
        if (s.contains("люк") || s.contains("панорам") && s.contains("крыш")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_SUNROOF_WINDOW", sl);
        }
        if (s.contains("шторк") || s.contains("солнцезащит")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_SUN_SHADE", sl);
        }
        if (s.contains("блокир") && s.contains("окон") || s.contains("замок окон")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_WINDOW_LOCK", sl);
        }

        // ---------- DOORS / TRUNK ----------
        if (s.contains("багажник") || s.contains("дверь багаж")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_TRUNK", sl);
        }
        if (s.contains("капот") || s.contains("передний багаж") || s.contains("фрунк")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_FRUNK", sl);
        }
        if (s.contains("двер") && (s.contains("запр")||s.contains("отопр")||s.contains("заблок")||s.contains("разблок")||s.contains("замок")||s.contains("замки"))) {
            sl.clear(); sl.put("action", (s.contains("отопр")||s.contains("разблок")||s.contains("открой")) ? "UNLOCK" : "LOCK");
            return arbFlat(rid, t, "carControl", "OP_DOOR_LOCK", sl);
        }
        if (s.contains("двер") && (on||off)) {
            sl.clear(); sl.put("action", off ? "CLOSE" : "OPEN");
            if (!zone.isEmpty()) sl.put("name", zone);
            return arbFlat(rid, t, "carControl", "OP_CAR_DOOR", sl);
        }

        // ---------- SEATS ---------- ("массаж"/"вентиляция" imply the seat even without the word "сиденье")
        if (s.contains("сиден") || s.contains("кресл") || s.contains("массаж")) {
            if (s.contains("подогрев") || s.contains("обогрев") || s.contains("греть") || s.contains("тепл")) {
                sl.clear(); int n = numIn(s);
                if (n >= 0 && n <= 3) sl.put("gear", String.valueOf(n));
                else if (!grade.isEmpty()) sl.put("direction", grade);
                else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
                if (!zone.isEmpty()) sl.put("target", zone);
                return arbFlat(rid, t, "carControl", "SET_SEAT_HEAT", sl);
            }
            if (s.contains("вентил") || s.contains("обдув") || s.contains("продув")) {
                sl.clear(); int n = numIn(s);
                if (n >= 0 && n <= 3) sl.put("gear", String.valueOf(n));
                else if (!grade.isEmpty()) sl.put("direction", grade);
                else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
                if (!zone.isEmpty()) sl.put("target", zone);
                return arbFlat(rid, t, "carControl", "SET_SEAT_VENTILATE", sl);
            }
            if (s.contains("массаж")) {
                sl.clear(); int n = numIn(s);
                if (n >= 1 && n <= 3) sl.put("gear", String.valueOf(n));
                else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
                if (!zone.isEmpty()) sl.put("target", zone);
                return arbFlat(rid, t, "carControl", "SET_SEAT_MASSAGE", sl);
            }
        }

        // ---------- LIGHTS ----------
        if (s.contains("подсветк") || s.contains("атмосферн") || (s.contains("салон") && s.contains("свет"))) {
            if (s.contains("ярче") || s.contains("темнее") || (s.contains("яркост"))) {
                sl.clear(); if (!grade.isEmpty()) sl.put("direction", grade);
                int n = numIn(s); if (n>0 && n<=100) sl.put("value", String.valueOf(n));
                if (!sl.isEmpty()) return arbFlat(rid, t, "carControl", "SET_MOOD_LIGHTS_BRIGHTNESS", sl);
            }
            String c = colorOf(s);
            if (s.contains("цвет") || !c.isEmpty()) {
                sl.clear(); if (!c.isEmpty()) sl.put("color", c);
                return arbFlat(rid, t, "carControl", "SET_MOOD_LIGHTS_COLOR", sl);
            }
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_MOOD_LIGHTS", sl);
        }
        if (s.contains("ближн") && s.contains("свет") || s.contains("фары") && !s.contains("дальн")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_DIPPED_BEAM", sl);
        }
        if (s.contains("дальн") && s.contains("свет")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_HIGH_BEAM", sl);
        }
        if (s.contains("аварийк") || s.contains("аварийн")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_WARNING_LIGHT", sl);
        }
        if ((s.contains("плафон")||s.contains("свет салона")||s.contains("свет в салоне")||s.contains("лампоч")) ) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_READ_LIGHTS", sl);
        }

        // ---------- STEERING / MIRRORS / WIPERS ----------
        if (s.contains("руль") || s.contains("руля")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_STEER_WARM", sl);
        }
        if (s.contains("зеркал") && (s.contains("обогрев")||s.contains("подогрев")||s.contains("греть"))) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "REAR_MIRROR_WARM", sl);
        }
        if (s.contains("помой") && (s.contains("лобов")||s.contains("стекл")) || s.contains("омыват") || s.contains("брызни")) {
            return arbFlat(rid, t, "carControl", "WASH_WIPER", new java.util.HashMap<String,String>());
        }
        if (s.contains("дворник") || s.contains("щетк")) {
            sl.clear(); int n = numIn(s);
            if (n>=1 && n<=4) sl.put("gear", String.valueOf(n));
            else sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SET_WIPER_SPEED", sl);
        }

        // ---------- SOUND / MEDIA ----------
        if ((s.contains("звук")||s.contains("громкост")||s.contains("громче")||s.contains("тише")) && !s.contains("сигнал")) {
            if (s.contains("выключи звук")||s.contains("без звука")||s.contains("отключи звук")||s.contains("замолчи")) {
                return arbFlat(rid, t, "carControl", "MUTE", new java.util.HashMap<String,String>());
            }
            if (s.contains("включи звук")||s.contains("со звуком")) {
                return arbFlat(rid, t, "carControl", "UNMUTE", new java.util.HashMap<String,String>());
            }
            sl.clear(); int n = numIn(s);
            if (n>=0 && n<=40 && (s.contains("громкост")||s.contains("на "))) sl.put("volume", String.valueOf(n));
            else if (!grade.isEmpty()) sl.put("direction", grade);
            else return null;
            return arbFlat(rid, t, "carControl", "SET_VOLUME", sl);
        }
        if (s.contains("следующ") && (s.contains("трек")||s.contains("песн")||s.contains("композиц"))) {
            return arbFlat(rid, t, "mediaControl", "NEXT_MEDIA", new java.util.HashMap<String,String>());
        }
        if ((s.contains("предыдущ")||s.contains("прошл")) && (s.contains("трек")||s.contains("песн")||s.contains("композиц"))) {
            return arbFlat(rid, t, "mediaControl", "PREVIOUS_MEDIA", new java.util.HashMap<String,String>());
        }

        // ---------- HUD / DISPLAY / FRAGRANCE ----------
        if (s.contains("hud") || s.contains("проекц")) {
            if (s.contains("яркост")||s.contains("ярче")||s.contains("темнее")) {
                sl.clear(); if (!grade.isEmpty()) sl.put("direction", grade);
                return arbFlat(rid, t, "carControl", "SET_HUD_BRIGHTNESS", sl);
            }
            if (s.contains("выше")||s.contains("ниже")||s.contains("подним")||s.contains("опусти")||s.contains("высот")) {
                sl.clear(); sl.put("direction", (s.contains("ниже")||s.contains("опусти")||isOff(s)) ? "MINUS" : "PLUS");
                return arbFlat(rid, t, "carControl", "SET_HUD_HEIGHT", sl);
            }
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "OP_HUD", sl);
        }
        if ((s.contains("экран")||s.contains("дисплей")) && (s.contains("яркост")||s.contains("ярче")||s.contains("темнее"))) {
            sl.clear(); if (!grade.isEmpty()) sl.put("direction", grade);
            int n = numIn(s); if (n>0 && n<=100) sl.put("value", String.valueOf(n));
            if (!sl.isEmpty()) return arbFlat(rid, t, "carControl", "SET_DISPLAY_BRIGHTNESS", sl);
        }
        if (s.contains("ароматизат") || s.contains("аромат") || s.contains("парфюм")) {
            sl.clear(); sl.put("action", isOff(s) ? "CLOSE" : "OPEN");
            return arbFlat(rid, t, "carControl", "SWITCH_FRAGRANCE", sl);
        }

        return null; // not a known command -> chat
    }

    /** Russian color word -> normalized color token (best-effort). */
    private static String colorOf(String s) {
        if (s.contains("красн")) return "RED";
        if (s.contains("син")) return "BLUE";
        if (s.contains("зелен")) return "GREEN";
        if (s.contains("бел")) return "WHITE";
        if (s.contains("желт")) return "YELLOW";
        if (s.contains("фиолет")||s.contains("пурпур")) return "PURPLE";
        if (s.contains("оранж")) return "ORANGE";
        if (s.contains("розов")) return "PINK";
        return "";
    }

    /** Parse a Russian spoken number in the 16..33 range (tens + units). */
    static int ruNum(String s) {
        // «ноль/нуль» = 0 — ниже 0 считался бы «нет числа» (v > 0), и «холодильник на ноль градусов» включал холодильник
        if (s.contains("ноль") || s.contains("нуль") || s.contains("нулю")) return 0;
        // 10..19 first (a teen must not be split into tens+units)
        String[] teen = {"десят","одиннадцат","двенадцат","тринадцат","четырнадцат","пятнадцат","шестнадцат","семнадцат","восемнадцат","девятнадцат"};
        for (int i = teen.length - 1; i >= 1; i--) if (s.contains(teen[i])) return 10 + i; // longer/teen forms before "десят"
        // tens: strip the tens word so its letters ("двА" in "двАдцать", "трИ" in "трИдцать") don't leak into units
        int tens = 0;
        if (s.contains("тридцат"))      { tens = 30; s = s.replace("тридцат", " "); }
        else if (s.contains("двадцат")) { tens = 20; s = s.replace("двадцат", " "); }
        else if (s.contains("десят"))   return 10; // plain "десять"
        // units: scan high->low so "восемь"(8) is matched before "семь"(7) inside it
        int units = 0;
        String[] u = {"","один","два","три","четыр","пят","шест","сем","восем","девят"};
        for (int i = u.length - 1; i >= 1; i--) if (s.contains(u[i])) { units = i; break; }
        int v = tens + units;
        return v > 0 ? v : -1;
    }

    /**
     * Build the arbitration-result JSON that NluManager.onArbitrationResult consumes.
     * Slots are written FLAT onto nluResults[0] (key = slot name, value = normalized value) — the
     * shape the stock adapters read (nlpBean.semantic.slots = nluResults[0]; slots.optString(name)).
     * A redundant "slots" array is added too, so any GSON/DM consumer that expects the list form
     * still resolves. requestId starts with "vosk" so the patched onArbitrationResult keeps it.
     */
    private static String arbFlat(String rid, String query, String domain, String intent,
                                  java.util.Map<String,String> slots) {
        try {
            JSONObject nlu = new JSONObject();
            nlu.put("query", query).put("domain", domain).put("intent", intent);
            org.json.JSONArray arr = new org.json.JSONArray();
            for (java.util.Map.Entry<String,String> e : slots.entrySet()) {
                nlu.put(e.getKey(), e.getValue());                    // flat: adapters read this
                arr.put(new JSONObject().put("name", e.getKey())
                        .put("value", e.getValue()).put("normalizedValue", e.getValue()));
            }
            nlu.put("slots", arr);                                    // list form: GSON/DM read this
            return new JSONObject().put("query", query).put("requestId", rid).put("nluType", 0).put("zoneId", wakeZone)
                    .put("nluResults", new org.json.JSONArray().put(nlu)).toString();
        } catch (Throwable e) { return null; }
    }

    /** Execute a semantic result via the native pipeline (NluManager.onArbitrationResult).
     *  json must contain nluResults[{domain,intent,slots}] + requestId starting "vosk". */
    public static void execArbitration(final String json) {
        new Thread(new Runnable() { public void run() {
            try {
                Class<?> nm = Class.forName("com.incall.apps.speechassistant.nlu.NluManager");
                Object mgr = nm.getMethod("getInstance").invoke(null);
                java.lang.reflect.Method mth = nm.getDeclaredMethod("onArbitrationResult", String.class);
                mth.setAccessible(true);
                mth.invoke(mgr, json);
                Log.i(TAG, "execArbitration sent: " + json);
            } catch (Throwable t) { Log.e(TAG, "execArbitration", t); }
        }}).start();
    }

    /** True if this arbitration JSON is one of ours (so the patched onArbitrationResult
     *  can drop native Chinese results and keep only ours). */
    public static boolean isOurs(String json) {
        if (json == null) return false;
        try { return new JSONObject(json).optString("requestId", "").startsWith("vosk"); }
        catch (Throwable t) { return json.contains("\"requestId\":\"vosk"); }
    }

    /** Called from patched SrPgsManager.appendPgs: replace native (Mandarin) text with our RU text. */
    public static String swap(String original) {
        String v = lastText;
        if (v != null && !v.isEmpty()) return v;   // our RU text (Vosk partial, or the final once decoded)
        // lastText empty = mid-utterance with GigaAM (no streaming partials). Suppress the stock partial
        // ENTIRELY — whether Chinese (would flash CJK) or a Russian prefix from the RU-patched iFlytek SR
        // (it leaks e.g. "За" that then doubles with the final → "Зазапусти музыку"). Show only the final.
        return "";
    }

    /** Capitalize the first letter (for on-screen hints and the dictated-phrase echo). */
    static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** True if the string contains any CJK (Chinese) character. */
    static boolean hasCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '一' && c <= '鿿') return true;   // CJK Unified Ideographs
        }
        return false;
    }

    private VoskBridge() {}
}