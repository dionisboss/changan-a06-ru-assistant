# Прямая инъекция контента в виджет ассистента (РАБОТАЕТ)

Доказано: можно **локально управлять виджетом** (состояния персонажа + кастомные картинки/карточки),
минуя сломанный skill/VPA/облачный пайплайн — инъекцией своего кода в лаунчер.

## Как устроено
Виджет держит `VpsControl` (в лаунчере), у него поля `vpaImage` (ImageView аватара, локальные
`AnimationDrawable`) и `vpaDetailView` (`VpaDetailFrameLayout` для контента). Мы добавили свой класс
**`com.stand.vpa.VpaInjector`** (dex `classes8` в лаунчере) + однострочный патч в `VpsControl.addVpaImageView`:
`invoke-static {p0}, Lcom/stand/vpa/VpaInjector;->register(Ljava/lang/Object;)V` — он сохраняет инстанс
`VpsControl` и регистрирует ресивер `com.stand.VPA`. По broadcast'у рефлексией дёргает нужные вьюхи.

Исходник: `stand/vpa-inject/src/com/stand/vpa/VpaInjector.java`. Артефакт: `stand/out/launcher_inject.apk`.

## Сборка (воспроизводимо)
1. `javac -classpath android.jar` + `d8 --min-api 29` → `classes8.dex` (см. команды в истории/скрипте).
2. Патч `VpsControl.smali` (в `classes5_smali`), пере-`smali a` → `classes5.dex`.
3. `stand/swapdex.sh stand/out/launcher_norm.apk launcher_inject classes5.dex classes8.dex` (подмена dex + пересигновка).
4. `adb install -r -d launcher_inject.apk` + `pm install-existing --user 10 com.incall.apps.sdalauncher`, рестарт лаунчера.
   Лог `VpaInjector: receiver registered; VpsControl=...` = готово.

## Команды (`stand/ui_stand.sh`)
- **Аватар (состояние персонажа):** `ui_stand.sh avatar <state>`
  где state = `vpa_ai_idle` | `vpa_ai_listen_left` | `vpa_ai_listen_right` | `vpa_ai_tts_center`.
  Проверено: аватар реально меняет анимацию (`inject_avatar.png`).
- **НАТИВНАЯ карточка ответа:** `ui_stand.sh card <картинка> [текст] [заголовок]` — инфлейтит **родной
  layout `vpa_layout_streamhelp`** (поля `tv_vpa_title`/`tv_vpa_content`(markdown)/`iv_vpa_image`), заполняет
  заголовок+текст+картинку → выглядит как настоящая карточка виджета (скруглённая панель, типографика лаунчера),
  а не наш бокс поверх. Проверено: `inject_native2.png` («Погода» + текст + пейзаж). Картинку кладёт в
  files-каталог лаунчера (scoped storage, user 10). **РОДНОЙ СЛОТ (без координат):** карточка добавляется в
  `VpsControl.binding.vpaGroupTwo` — это `BottomFrameLayout` в контейнере виджета (`vpa_frame_container`),
  штатное место карточки-ответа под/на виджете. `slot.addView(card)` + `setVisibility(VISIBLE)` → виджет сам
  разворачивается, карточка встаёт на родное место и авто-размер по контенту (`inject_slot.png`). Не нужны
  ни координаты, ни рабочий skill-пайплайн. (Штатный полный путь показа — `showDetailLayout`→observer
  `lambda$obser$2`→`setVpaMode`/`tryChangeLayout`, но он завязан на SkillServer; мы кладём прямо в слот.)
  Контейнер виджета (`VpaFrameContainerBinding`): `vpaGroupOne`(VpaShapeLinearLayout — бар), **`vpaGroupTwo`
  (BottomFrameLayout — слот детали)**, `vpaGroupVvOne`(VpaLayout), `vpaSurfacegroupOne/Two`(аватар), `vpaTargetView`.
- **Убрать карточку:** `ui_stand.sh card-clear`.

Broadcast напрямую:
`am broadcast --user 10 -a com.stand.VPA --es mode avatar --es anim vpa_ai_listen_left`
`am broadcast --user 10 -a com.stand.VPA --es mode card --es path <path в data-dir лаунчера>`

## Заметки / развитие
- Карточка сейчас добавляется в `android.R.id.content` лаунчера с фикс. позицией (760×460 @ 40,300) — для
  наглядности. Чтобы попасть в «правильный» слот, добавлять в `vpaDetailView` и триггерить `showDetailLayout`
  (LiveData) — но detail-вью не attached, пока VPA-пайплайн не поднят; можно attach вручную (`attachDetail`).
- Аватар — локальные drawable `vpa_ai_*` в лаунчере; можно подсунуть свои кадры/эмоции (свой AnimationDrawable).
- Путь картинки: из-за scoped storage класть в data-dir лаунчера (`/data/user/10/…/files/`, chown на его uid) —
  это делает команда `card` автоматически.

## Родные карточки виджета (все инфлейтятся в слот) + заполнение полей
`ui_stand.sh ncard <vpa_layout_...> [текст]` — рендерит любой родной layout с **автозаполнением**
(все пустые TextView/ImageView → контент, чтобы не были пустыми/сломанными). Поля карточки логируются:
`adb logcat | grep 'VpaInjector: DUMP'` (id + тип). Точечно: `ncard-fields <layout> '{"id":"текст",...}'`.
Пример погоды: `ncard-fields vpa_layout_dialog_query_weather '{"tv_temper":"22","tv_weather_type":"Облачно","tv_temper_addr":"Москва","tv_temper_range":"18°~24°","tv_air_quality_num":"45","tv_air_grade":"Хорошо"}'`.

Показанные (~26): погода `query_weather`/`multiple_weather`/`query_air_weather`, дата `query_date_time`/`ordinary_festivalsdate`,
климат `air_temperature`(ползунок ❄→☀)/`air_fan_speed`/`chair_hot`/`chair_wind`/`fragrance`, экран `screen_brightness`/`main_screen_angle`,
авто `skylight_address`(люк)/`sunshade_address`(шторка)/`wiper`/`wiper_sensitivity`, инфо `phone_item`/`electric`(батарея)/`mileage`,
ответы `car_answer`/`car_answer_detail`(с картинкой)/`streamhelp`(LLM), `music_search`(USB/Online), `voice_nav`, `large_model_image`.
Монтажи: `stand/logs/shots/cards_montage1.png`, `cards_montage2.png`, `card_weather_full.png`.
