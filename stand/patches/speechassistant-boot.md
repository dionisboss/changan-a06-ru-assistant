# Запуск SpeechAssistant на эмуляторе

Приложение системное (uid 1000, `android.uid.system`), 2 процесса: main + `:tts`.
Куча init-зависимостей отсутствует на чистом AOSP: БД TTS-ролей, `/resources/iflytek/*`,
`uses-library android.car` (+CarService), нативка iflytek. В Android любой uncaught (в ЛЮБОМ
потоке) валит процесс.

## Фикс 1 — БД TTS-ролей (main-thread краш)
`VoiceApp.onCreate` → `TtsRoleStore.init` → провайдер `content://com.incall.SpeechData/role`
→ `SQLiteException: no such table: role`. Причина: `onCreate` провайдера создаёт таблицу, но
`initDatabase` сидит из `/resources/iflytek` (нет) → падает в транзакции → откат → таблицы нет.
**Решение (без пересборки):** создать БД с таблицей на эмуляторе:
```
DB=/data/data/com.incall.apps.speechassistant/databases/voice_role.db
adb shell "mkdir -p $(dirname $DB); sqlite3 $DB 'create table role(id integer primary key autoincrement,virtual_id char(5) unique,name varchar(30),role_id varchar(10),role_name varchar(40),resource Text,mode char(2),sample_rate integer default 0,language varchar(30),supplier integer,category integer,emotion_type char(10),relation vachar(10),age integer,gender integer,personality varchar(30),country varchar(30),extra Text ,state integer default 0,unique(role_id,supplier,resource)); PRAGMA user_version=14;'"
adb shell "chown 1000:1000 $DB; chmod 660 $DB"
```

## Фикс 2 — глушитель uncaught (фоновые крашы: car/iflytek)
Десятки крашей в фоновых потоках (`android.car.hardware.property.CarPropertyManager$...`,
`CarPowerManager$...`, и т.д. — android.car на не-automotive образе не резолвится).
**Решение — 1 патч, снимает ВЕСЬ класс фоновых крашей:** в начало `VoiceApp.onCreate`
поставить свой `Thread.setDefaultUncaughtExceptionHandler`, который только логирует.
- Новый класс `smali6/com/incall/apps/speechassistant/application/CrashSwallow.smali`
  (implements `Thread$UncaughtExceptionHandler`, `uncaughtException` → Log.e + return).
- `VoiceApp.onCreate` (classes6.dex) после `invoke-super`: `new CrashSwallow` +
  `Thread->setDefaultUncaughtExceptionHandler`.
Сборка per-dex (быстро): `./dexpatch.sh extract ../aiassist/SpeechAssistant.apk classes6.dex`
→ правка → `./dexpatch.sh build ../aiassist/SpeechAssistant.apk classes6.dex out/sa_swallow.apk`.

## Результат (проверено)
Главный процесс ЖИВ (главный поток не падает), 35 фоновых крашей проглочено, подняты сервисы
`SpeechClientService`, `AIMemoryService`, `SksAccessService`. `:tts`-процесс циклически падает
(main) — для чата не нужен. MainActivity на передний план не выходит (не критично для инъекции).

## Осталось для чат-запроса
1. Конфиг пуст (`CommonConfigManager config file count: 0`, т.к. нет
   `/resources/iflytek/speech/changan_common_config`) → `CommonConfig` DOMAIN пуст.
   → патч `CommonConfig.getDialogUrl()`→`http://10.0.2.2:8080/...` + `GateWayUtils.isEnableGateway()`→false
   (см. `chat-redirect-and-inject.md`), либо запушить минимальный changan_common_config.
2. Триггер: `SpeechTestManager.testCloudNlu(zone,sn,query)` через тест-IPC, либо SrService.
3. `./stand.sh mock 8080` — принять запрос, увидеть реальный формат, ответить `{"data":{...}}`.
