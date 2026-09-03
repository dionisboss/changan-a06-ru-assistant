# Тест чат-пайплайна на стенде: редирект на mock + инъекция текста

Чат/семантика (LLM) живёт в **SpeechAssistant** (`com.incall.apps.speechassistant`), НЕ в aiassist.

## Протокол чата (разобран из кода)
- Точка запроса: `CloudNlu.m1081x6c8fb7a8(zoneId, requestId, query)` (classes6.dex).
- URL: `CommonConfig.getDialogUrl()` = `PROTOCOL + DOMAIN + "/dubhe/dubhe-gateway/dialog-new"`.
  Через `GateWayUtils.replaceHostVar` маппится в `.../tu-apigw/cai/api/v1/tu/speech/dialog-new`.
- Тело POST (JSON): `{carModel, zoneId, vin, requestId, tuid, lat, lng, query, speechVersion, isWakeFree, userId}`.
- Ответ: `{"data": {...}}` → CloudNlu берёт `.getJSONObject("data")`.
- Другие эндпоинты: `/event`, `/buried`, `/text-check`, `/dubhe/dubhe-tts-gateway/tts`, WSS `/iflytek/v2/autoCar` (ASR).

## Точки инъекции текста (без голоса)
- **`SpeechTestManager.testCloudNlu(zoneId, sn, query)`** — штатный тест-ввод, прямо зовёт CloudNlu.
- `SpeechTestManager.processTestAction(Message)` — IPC/Messenger тест-интерфейс.
- Также: `NluManager`, `SrBaseSession` (путь ASR→NLU), `ClientMsgHandler` (IPC от других приложений).

## Редирект на mock (2 способа)
**A. Патч (надёжно, без сети/подписи):**
- `CommonConfig.getDialogUrl()` (и `getEventUrl/getTextCheckUrl`) → вернуть `http://10.0.2.2:8080/...`.
  (10.0.2.2 = Mac-хост со стороны эмулятора.)
- `GateWayUtils.isEnableGateway()` (=`SettingsUtil.getGatewaySwitch()`) → `false`:
  тогда `buildJsonRequest` шлёт на СЫРОЙ url без VCS-HMAC подписи и без https-форсинга.

**B. dev-окружение + hosts (без патча URL):**
- Перевести приложение в enviNet=2 (dev) → URL становятся `http://dev-edc.sda.changan.com.cn/...`,
  а этот хост в `GateWayUtils.DOMAIN_WHITELIST` → замена хоста пропускается, остаётся plain http.
- `./stand.sh hosts dev-edc.sda.changan.com.cn` → маппит домен на 10.0.2.2 в /system/etc/hosts эмулятора.
- Подпись всё равно добавится (заголовки VCS-HMAC), но mock их игнорирует.

## Прогон
```
./stand.sh mock 8080                 # терминал 1: mock-бэкенд (логирует запросы)
# редирект способом A (патч) или B (hosts)
./stand.sh hosts dev-edc.sda.changan.com.cn
# инъекция запроса через SpeechTestManager (IPC) или прямой вызов CloudNlu
```
`mock/requests.log` покажет РЕАЛЬНЫЙ формат запроса → по нему уточнить схему ответа `data`.
Для интеллектуальных русских ответов: `ANSWER_CMD='<команда-LLM>' ./stand.sh mock` (query на stdin, ответ на stdout).

## РЕАЛИЗОВАНО и ПРОВЕРЕНО (2026-08-31) — сквозной прогон работает
Все патчи в `classes6.dex` (через `dexpatch`), артефакт `stand/out/sa_chat.apk`:
1. `CommonConfig.getDialogUrl()` → const `http://10.0.2.2:8080/dubhe/dubhe-gateway/dialog-new`.
2. `GateWayUtils.isEnableGateway()` → `false` (без VCS-HMAC, без host-replace).
3. Новый `StandNluReceiver` (dynamic, RECEIVER_EXPORTED) регистрируется в `VoiceApp.onCreate`;
   `onReceive` → `SpeechTestManager.getInstance().testCloudNlu(1,"stand", q)`.

Прогон:
```
./stand.sh mock 8080 &
adb install -r out/sa_chat.apk ; adb shell am start -n com.incall.apps.speechassistant/.ui.MainActivity
adb shell "am broadcast -a com.stand.NLU -p com.incall.apps.speechassistant --es q 'включи музыку и позвони маме'"
```
Результат: `SA_STAND_TRIGGER` → `[GateWayUtils] buildJsonRequest: http://10.0.2.2:8080/...`
→ mock принял, `[CloudNlu] get nlu success: {"data":{...}}` (приложение приняло ответ).

**Реальный формат запроса (наконец пойман, `mock/requests.log`):** okhttp/4.12.0, POST JSON
`{"carModel":0,"zoneId":1,"requestId":"stand","tuid":"","query":"<текст>","speechVersion":"","isWakeFree":true,"userId":""}`
(tuid/speechVersion/userId пусты — нет конфига/сервисов машины; для чата не мешает).

Для умных русских ответов: `ANSWER_CMD='<команда-LLM>' ./stand.sh mock` (query→stdin, ответ→stdout).

## (устарело) БЛОКЕР следующего шага
Всё это требует, чтобы **SpeechAssistant загрузился на эмуляторе**. Он тяжёлый (319 МБ,
нативные iflytek-либы, нужен `/resources/iflytek`, car-сервисы). Ожидаемо потребует серии
crash-патчей (как car-stub для aiassist) + заглушек ресурсов. Это следующая веха.
Для https-mock (если не отключать gateway): `./stand.sh ca-install <ca.pem>` (эмулятор пишем в систему).
```
