# Патч: запуск IncallAIassist на чистом AOSP-эмуляторе

**Проблема:** приложение вкомпилировало классы `android.car.*` внутрь APK, но зависимый
скрытый платформенный класс `android.car.builtin.os.*` на не-automotive образе отсутствует.
`AiAssistApp.onCreate` и `CarServiceManager.init` в фоновых потоках зовут `Car.createCar()`
→ `NoClassDefFoundError` → собственный CrashHandler убивает процесс (`SIG 9`).

**Цель:** приложение должно полноценно грузиться на эмуляторе для теста пайплайна чата.
CarService (управление климатом/стёклами и т.п.) для чата не нужен → вырезаем его инициализацию.

## Изменения (в `work/aia/`, apktool decode IncallAIassist.apk)

1. **`smali/android/car/builtin/os/BuildHelper.smali`** — НОВЫЙ файл (заглушка).
   `isUserBuild()Z`→0, `isEngBuild()Z`→0, `isUserDebugBuild()Z`→1. (Проходит `Car.<clinit>`.)

2. **`smali/com/changan/AiAssistApp.smali`** → метод `lambda$onCreate$1()V`:
   тело заменено на `.locals 0` + `return-void` (пропуск `Car.createCar`).

3. **`smali_classes2/com/changan/service/CarServiceManager.smali`** → `init$lambda$2(Landroid/content/Context;)V`:
   вместо `Car.createCar` — вызов `onConnectState(-1)` (сообщить FAIL, разблокировать `waitForInit`).
   `.locals 1`, `invoke-direct` (метод private).

## Сборка/установка
```
./stand.sh build aia          # -> out/aia.apk (подписан платформенным ключом)
./stand.sh install out/aia.apk
./stand.sh run com.changan.aiassist/.MainActivity
```

## Результат (проверено)
Краш ушёл. `BootInitManager` отрабатывает, сеть доступна, `MainActivity` Displayed +372ms,
вход в «精灵中心» (assistant center), процесс стабилен. Окружение — 预生产 (pre-prod, pre-sds.sda.changan.com.cn).

## Что дальше для ПОЛНОГО теста чата
- UI ассистента поднимается по триггеру/запросу (пассивно экран пуст).
- Реальный Dubhe требует облачную подпись VCS-HMAC (на эмуляторе нет) → для сквозного теста
  направить LLM-эндпоинт на СВОЙ mock/proxy и подать текстовый query в обход ASR.
- Если позже другой код дёрнет `android.car` — добавить заглушки остальных `builtin`-хелперов
  (ParcelHelper, Slogf, ServiceManagerHelper, ...) или так же вырезать вызовы.
