# Кастомный AAOS-образ с вшитым `/resources` (РАБОТАЕТ)

Проблема: приложения Changan читают ассеты по жёсткому пути `/resources/...`, а корень `/` эмулятора —
read-only dm-verity, туда не смонтировать в рантайме. Решение: **добавить новый логический раздел
`resources` в `super` эмулятора** (в его свободное место), точку монтирования `/resources` в `system`,
запись в fstab, отключить AVB. Всё офлайн в Linux-контейнере (OrbStack) — на macOS нет loop для ext4.

## Результат
`/resources` авто-монтируется при загрузке со всем содержимым (iflytek, SceneBrain, custormer/тема, Launcher).
Движки iFlytek **находят свои файлы** (старой ошибки `AINluEngine init state -1` нет). Осталось: нет
микрофона у эмулятора (`PcmRecorder未初始化`) — это отдельно (эмулятор-аудио), не про образ.

## Как собрано (воспроизводимо)
Базовый образ: `…/system-images/android-34-ext9/android-automotive/arm64-v8a/system.img` (GPT: part1 `vbmeta`,
part2 `super`). Super — Android dynamic partitions (`gDla` LP-метаданные), внутри system/vendor/product/…,
**~3.1G свободно**. Инструменты `lpunpack`/`lpmake`/`lpdump` собраны из `github.com/LonelyFool/lpunpack_and_lpmake`.

Контейнер: `docker run -d --name aaosbuild --privileged -v <sysimg_dir>:/imgbase:ro -v <car_resources>:/carres:ro -v <img_build>:/work ubuntu:24.04 sleep infinity`
Пакеты: `android-sdk-libsparse-utils e2fsprogs gdisk build-essential cmake clang zlib1g-dev xxd lz4 cpio python3-pip`, `pip install pycryptodome`, avbtool из AOSP.

Шаги (в `/work`):
1. `cp /imgbase/system.img system_base.img`
2. Вырезать super: `dd if=system_base.img of=super.img bs=512 skip=4096 count=11438080` (part2, offset сектор 4096).
3. `lpunpack super.img lpout` → system.img, vendor.img, product.img, system_ext.img, system_dlkm.img (raw ext4).
4. Точка монтирования: `debugfs -w -R "mkdir /resources" lpout/system.img` (system — shared_blocks, но добавление нового inode проходит; e2fsck чистый).
5. fstab: `debugfs -R "cat /etc/fstab.ranchu" lpout/vendor.img > fstab.ranchu`; дописать строку
   `resources  /resources  ext4  ro,barrier=1  wait,logical`; вписать назад `debugfs -w -R "rm /etc/fstab.ranchu"` + `"write fstab.ranchu /etc/fstab.ranchu"`.
6. Собрать раздел: стейдж `resroot/{iflytek,SceneBrain,Launcher,custormer/ivi/theme}` из `/carres`;
   `mke2fs -q -t ext4 -b 4096 -O ^has_journal -d resroot -L resources resources.img 640000` (≈2.5G, 3200 файлов).
7. Пересобрать super (тот же размер!): `lpmake --metadata-size 65536 --metadata-slots 2 --device super:5856296960
   --group emulator_dynamic_partitions:5855248384 --partition <name>:readonly:<size>:<group> --image <name>=<file>`
   для system/system_dlkm/system_ext/product/vendor + новый `resources` (readonly, size=размер resources.img). → `super_new.img`.
8. Отключить AVB (system/vendor изменены): `avbtool make_vbmeta_image --flags 2 --padding_size 4096 --output vbmeta_dis.img`; `truncate -s 1048576 vbmeta_dis.img`.
9. Собрать образ: `dd if=vbmeta_dis.img of=system_base.img bs=512 seek=2048 conv=notrunc` (part1);
   `dd if=super_new.img of=system_base.img bs=512 seek=4096 conv=notrunc` (part2). Размер = как оригинал.
10. Развернуть: бэкап `system.img.orig`, подменить `system.img` в system-images dir; удалить `~/.android/avd/changan_aaos.avd/system.img.qcow2` и `vendor.img.qcow2` (пересоздадутся из новой базы).

## Запуск
`stand/ui_stand.sh boot` — грузит с **`-selinux permissive`** (обязательно: точка `/resources` unlabeled,
enforcing блокирует fstab-монтирование). `/resources` появляется сам. Артефакт: `img_build/system_base.img`
(и активный `system.img`; откат — `system.img.orig`).

## Что это даёт / границы
- iFlytek-движки находят ресурсы (SR/NLU/TTS/VPR/wake) — путь к рабочему голосу; блокер теперь только микрофон эмулятора.
- Темы/обои/сцены (`custormer`, `SceneBrain`, `Launcher`) доступны приложениям.
- Аватар BD (облачный/native/account-bound) — по-прежнему не воспроизводится (не в `/resources`).
- Не влезли (super free ~3.1G): `vehicleSetting`(395M), `LightLanguage`(183M), `lvds` — добавить можно, вырастив super/GPT (сложнее).
