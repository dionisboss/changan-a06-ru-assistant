# Карта UI виджета ассистента — что умеет и чем драйвится

Виджет в оболочке (лаунчер) = **текстовая строка** (`PgsSwitcherView`/`AutoScrollTextView`, id `vpa_pgsswitcherview`)
+ **персонаж/аватар** (`VpaView` → Tuanjie/BD) + **детальная область** (`VpaDetailFrameLayout`/`StreamHelpView` — карточки/картинки).

## Для UI-работы: освободить виджет
SA держит залипшее FORCE-сообщение «Resource loading…» (он застрял в init и **сам перезапускается**).
`stand/ui_stand.sh wreset` — **отключает SA** (`pm disable`) + перезапускает лаунчер → строка чистая, ей можно рулить.
`ui_stand.sh wrestore` — включить SA обратно.

## 1. Текстовая строка — ПРЯМО драйвится broadcast'ом (надёжно) ✅
`com.incall.action.UPDATE_TEXT` (extra на `--user 10`). Команды:
- `ui_stand.sh text "..."` / `ui_stand.sh msg "..."` (длинное — скроллится марком).
- `ui_stand.sh state {guide|pgs|nlp|feedback|clear}` — состояния (type 3/1/2/4/9).
- Extras: `type`(1 надиктовка/2 команда/3 подсказки/4 ответ/5 форс/6 immediate/9 очистка), `level`(приоритет 1..9),
  `time`(автоскрытие мс; FORCE с time=MAX залипает — бить более высоким приоритетом или `wreset`),
  `textList`+`colorList`(несколько строк/цвета сегментов), `scroll`, `direct`(позиция).
- Субтитр озвучки: `com.incall.action.TTS_CONTENT` → `ui_stand.sh tts "..."`.
- Видимость: `com.incall.action.VIEW_VISIBLE` → `ui_stand.sh hide|show`.

## 2. Состояния ассистента / индикаторы — broadcast ✅ (частично)
- `com.incall.action.SPEECH_WAKEUP`/`SPEECH_SLEEP` → `ui_stand.sh wake|sleep` (индикатор микрофона вкл/выкл).
- `VW_STATE`, `SPEECH_STATE`(=`Settings.Global.speech_state` битовый), `NLU_STATE` — драйвят состояние аватара,
  НО видимый эффект требует поднятой VPA-подсистемы (см. §3).

## 3. Персонаж/аватар — НЕ полностью локально ⚠️
- 3D-сцена авто (не аватар) рендерится **Tuanjie (Unity, `com.ca.tuanjie`)** — установлено, работает (кнопка Transform, вращение).
- Сам аватар-персонаж: `VpaView`→BDCarSDK/DuMixAR(`libdumixar.so`)→Tuanjie. Фигура и эмоции (`AI-idle/great/heart/dancing/…`,
  `type:FramePicture`) — **облачные/привязаны к аккаунту** (`preprod.changan.com.cn`, `digital-buddy-binding`).
  `AI-idle` покадрово вшита в assets лаунчера, остальные тянутся с CDN. Эмоции офлайн полностью не воспроизводимы.
- Драйвер эмоций: `AvatarManager.updateAvatar/doAction(propertyId)` через skill/scene-канал.

## 4. Картинки / карточки — от SKILL-сервера ⚠️
`iv_vpa_image`/`StreamHelpView`/`VpaDetailFrameLayout` — богатые ответы голосовых скиллов (картинка+текст+кнопки).
Приходят через `SkillClientManager` (в лаунчере колбэк `VpsControl$4.onCompled` сейчас падает — нужен рабочий skill-backend).
Не драйвится простым broadcast'ом; нужен скилл-ответ или прямая инъекция в `StreamHelpView`.

## Итог для локального UI-теста
- **Полностью управляемо сейчас:** вся текстовая строка (короткие/длинные/состояния/цвета/приоритеты), субтитр, видимость, индикатор мика. → `ui_stand.sh`.
- **Требует пайплайна:** эмоции аватара (BD-облако), картинки/карточки (skill-сервер). Следующий шаг для них — поднять skill-backend и/или прямая инъекция во `VpaDetailFrameLayout`/`AvatarManager`.
