# Приватность: запросы НЕ уходят на серверы Changan

Цель: ни текст запросов, ни аудио, ни телеметрия/история не уходят в `*.sda.changan.com.cn`.
Все патчи встроены в `build_sa.sh` (оба профиля). Проверено: **0 коннектов на changan.com.cn**.

## Что перекрыто

1. **Все dubhe-эндпоинты** (classes6, `CommonConfig.getProtocol()`→`http://`, `getDomain()`→HOST).
   Так как всё строится из `PROTOCOL+DOMAIN`, один патч перенаправляет на HOST:
   - `dialog-new` — чат (текст запроса) → наш LLM.
   - `event`, `buried` — телеметрия/аналитика диалога.
   - `text-check` — **модерация** (туда уходил текст запроса!).
   - `aimemory` (`sync_users`) — «память» ассистента.
   - `va-config` — конфиг.
   - `DialogHistoryUpload` (URL=`getEventUrl()`) — **история диалогов**.
2. **Cloud-ASR + AIUI** (classes6, `SrEngineProxy.getHostUrl()` → `ws://HOST`/`http://HOST`).
   Иначе туда уходило **АУДИО** (WSS `/iflytek/v2/autoCar`) и `/aiui/event`,`/aiui/personal/upload`.
3. **SpeechTracer** (classes5, `UploadManager.uploadSync(SingleTracePoint|SingleTraceEvent)`→`return true`).
   Отдельный аналитический SDK (`SpeechTracer-0.0.5`) слал tracePoints (`nluEnd` и т.п.) прямо на
   `https://sds.sda.changan.com.cn/dubhe/dubhe-gateway/buried` в обход CommonConfig — заглушён (no-op).
4. `isEnableGateway()`→false — сырой POST на HOST, без VCS-HMAC подписи и host-replace.

## Важные следствия
- **Онлайн-русский ASR (getLanguage=4) и приватность конфликтуют:** штатный облачный iFlytek-ASR
  шлёт аудио в облако Changan. Мы перенаправили cloud-ASR на HOST, поэтому облачное распознавание
  работать НЕ будет (наш HOST не говорит на протоколе iFlytek ASR). Для приватного распознавания —
  **офлайн Vosk** (`vosk-offline-asr.md`). Т.е. с приватностью правильный путь ASR = офлайн.
- Наш mock отвечает 200 на event/buried/text-check/aimemory → приложение довольно, без ретраев,
  и мы ВИДИМ в `mock/requests.log`, что оно пыталось отправить (прозрачность).

## Проверка отсутствия утечки
```
adb logcat -c
# ... прогнать запрос ...
adb logcat -d | grep -ci "changan.com.cn"     # должно быть 0
```
