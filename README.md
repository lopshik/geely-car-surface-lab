# CarVideo — experimental local video playback on Geely EX5 EM-i

[Русский](#русский) · [English](#english)

> **Research project. Not an official Geely or Google product.**
> Video uses an experimental Android Auto NavigationTemplate Surface, **not** an officially supported video-app category on Android Auto. **Use only while safely parked.** The application cannot reliably detect vehicle movement or automatically guarantee a stationary-only display. No public production APK is claimed.

## Русский

**CarVideo** — независимый исследовательский проект: локальные фильмы с телефона Android отображаются на экране Geely EX5 EM-i через проводное Android Auto, со звуком через автомобильные динамики. Для проверенной конфигурации не потребовались root, Shizuku или изменения прошивки ГУ.

**Реально проверено:** Geely EX5 EM-i + Xiaomi 15 (Android 16, HyperOS 3), экран Android Auto Surface 1802×1052, локальные файлы с видео 1920×1080 и 1916×800, звук через `remote-submix` с полученным Audio Focus, воспроизведение и пауза касанием, выбор фильма прямо на ГУ, скорость 1.25×, переключение «Заполнить/Целиком», восстановление позиции после закрытия штатной камерой заднего вида. Последняя подробно проверенная сборка — **0.8.4 Preview**.

**Версия 1.0 RC1** устраняет навигационную информационную плашку поверх фильма и избыточное обновление шаблона каждые четыре секунды. **Работа этого исправления на реальном Geely ещё не подтверждена**. Не путайте успешную сборку GitHub Actions с испытанием автомобиля.

### Возможности
- Одна выбранная папка с фильмами на телефоне (Android Storage Access Framework), обновление списка по кнопке.
- Компактный интерфейс телефона и простая видеотека списком на ГУ; без поиска и избранного.
- Касание видео — показать кнопки; повторное касание — пауза/пуск.
- Перемотка на 10 секунд, скорость 0,75× / 1× / 1,25× / 1,5× / 2×.
- Изображение: **Заполнить** — с возможной обрезкой краёв, **Целиком** — с чёрными полосами при несовпадении пропорций.
- Запоминание позиции отдельно для каждого файла; при прерывании Android Auto экраном камеры сохраняется позиция, после возвращения фильм стоит на паузе.
- Отдельная диагностика на телефоне: «Лог» → «Скопировать».

### Сборка из исходников

Требуются JDK 17, Android SDK 35, Gradle 8.11.1 и Android Gradle Plugin 8.9.2. В корне проекта выполните:

```sh
gradle --no-daemon :app:assembleDebug
```

Результат: `app/build/outputs/apk/debug/app-debug.apk`. В GitHub Actions используется та же команда; скачиваемые оттуда тестовые APK подписываются временным debug-ключом среды сборки. **Такая подпись не подходит для стабильных обновлений.** Публичного подписанного production APK пока нет.

При первом запуске выберите обычную подпапку с фильмами; Android 11+ может запрещать предоставление доступа к корню Download. Приложение запрашивает доступ только к выбранному каталогу.

### Ограничения и безопасность

- **Только на стоянке.** Приложение не содержит надёжного контроля скорости/передачи и не реализует безопасность уровня штатных автомобильных систем. Не включайте видео во время движения.
- В Android Auto категория видеоплееров официально не поддерживается так же, как видео на Android Automotive OS: [поддерживаемые категории](https://developer.android.com/docs/quality-guidelines/car-app-quality).
- Использование `NavigationTemplate` и `SurfaceCallback` для видео экспериментально; его доступность может измениться после обновлений Android Auto или на других автомобилях.
- Реальная совместимость, энергопотребление, длительные испытания, сертификация и безопасность публикации для других автомобилей **не подтверждены**.
- Камера заднего вида всегда должна иметь приоритет. CarVideo реагирует на прерывание экрана Android Auto; оно **не считывает передачу R**.
- Не собирайте и не публикуйте приватные ключи подписи, персональные диагностические логи, фотографии пассажиров, видеозаписи или чужие фильмы.

### История проекта

| Этап | Результат |
|---|---|
| CSL 0.3–0.5 | От сетки до подтверждённого видео и звука через Geely |
| CarVideo 0.6 | Выбор фильма с ГУ и сохранение позиции после камеры |
| 0.7.1 | Простой каталог; проверен просмотр примерно 21 минуты |
| 0.8.1–0.8.2 | Одна папка, скорость, режимы изображения, компактный интерфейс |
| 0.8.3–0.8.4 | Видеотека списком, двухэтапное касание, исправление повторных запусков при перемотке |
| 1.0 RC1 | Убрана верхняя информационная плашка; **требуется проверка на машине** |

Проверенная ветка для отката: [CarVideo 0.8.4](https://github.com/lopshik/geely-car-surface-lab/tree/archive/verified-carvideo084-20261112); более ранние версии также архивированы.

### Благодарности

- **Android Open Source Project / Google** — Android SDK, `MediaPlayer`, `MediaSession`, `SurfaceTexture`, `Storage Access Framework`, `EGL` и документация.
- **AndroidX Car App Library** — `NavigationTemplate`, `ListTemplate`, `SurfaceCallback`; библиотека распространяется по лицензии Apache 2.0.
- **GitHub Actions** — инфраструктура повторяемых сборок.
- **ChatGPT (OpenAI)** — помощь в проектировании, коде, диагностике и подготовке документации.
- **Владельцу автомобиля и автору испытаний** — практические проверки на Geely EX5 EM-i, журналирование и обратная связь.

Мы не заявляем, что копировали исходный код из какого-либо стороннего проекта. Если обнаружатся заимствованные фрагменты, их лицензии и авторство должны быть указаны дополнительно. См. [NOTICE](NOTICE.md).

**Лицензия собственного кода:** [Apache 2.0](LICENSE).

---

## English

CarVideo is an **independent, experimental** Android phone application that sends local video and audio to the projected Android Auto display in a Geely EX5 EM-i. It does not require head-unit firmware changes, root, or Shizuku on the successfully tested phone/vehicle combination.

**Owner-tested hardware:** Geely EX5 EM-i head unit, Xiaomi 15 running Android 16 / HyperOS 3. Verified features include car-screen movie selection, audio through the car speakers, different local video resolutions, 1.25× speed, touch play/pause, and paused position restoration after OEM camera interruption. These results are **specific to this combination**.

Version **1.0 RC1** removes the large navigation info card displayed over playback. That change is **compiled but not yet vehicle-tested**.

Build with JDK 17 / Android SDK 35 / Gradle 8.11.1:

```sh
gradle --no-daemon :app:assembleDebug
```

This produces a **debug-signed experimental build**, not a production-signed release. A stable app identity, preserved signing key, and regression tests are required before version 1.0 can be distributed as a supported update.

**Safety and limitations:** For use only while safely parked. This project has no reliable gear/speed interlock, and Android Auto does not list video as a supported app category. It uses a navigation Surface experimentally. Do not use it while driving; compatibility may break with host updates. This project is not affiliated with or endorsed by Geely, Google or OpenAI.

Source license: [Apache License 2.0](LICENSE). Third-party acknowledgments: [NOTICE.md](NOTICE.md).


## 1.0.0 RC2 — owner-requested minimal finishing changes

- On the phone's movie list, a separate red **✕** button beside each film requests deletion of exactly that file. A native confirmation dialog explicitly warns that this is permanent storage deletion, not just removal from the library. Cancel is the default safe choice. No automatic deletion.
- Document deletion uses the official SAF `DocumentsContract.deleteDocument` API on the file returned from the user-selected folder, only if the app holds the persisted write grant; denied/unsupported operations show an error and leave the library intact. If earlier folder permission was read-only, the user must choose the folder again and authorize write. The app never requests broad/all-files access. No deletion of other entries, folders, or file sets.
- Deletion of the currently projected file is blocked while the car Surface is connected. On confirmed success, only the deleted movie's bookmark/private thumbnail are cleared and the single chosen folder is rescanned on a worker thread; no UI thread file IO.
- Car controls hide after 9 seconds instead of 12. Speed/aspect in the header and +/-10/play in the map strip are removed in the **same template refresh**. Google/Geely's host-managed system bars and the mandatory NavigationTemplate header ActionStrip may still auto-hide on their own schedule (~10s): pixel-perfect synchronization is not controlled by the app.
- No modifications to MediaPlayer, audio focus, decoder, FIT renderer or reverse-camera lifecycle.
- RC2 remains a separate CI debug-signed preview package `dev.geely.carvideo.rc2`. Until signed and tested, do not call it a published final production release.

## 1.0 RC3: diagnostic USB vs Wi-Fi experiments

- On Xiaomi choose Log → Test USB or Test Wi-Fi, run the same film for 20–30 minutes while parked, Copy log.
- Transport is a MANUAL LABEL, not automatically detected by CarVideo. The phone/vehicle may or may not support wireless Android Auto.
- Every 20 seconds while audio service is active: battery %, battery °C, source of battery charging, instantaneous net battery current and approximate total battery power, thermal Android status, process CPU% of ONE core, process PSS / Java / native heap, video play-time/position/size and error/interruption counts.
- Summaries and bounded 14K-char sample history. No additional storage permissions or continuous background task after service stops.
- Metrics from the Google Android Auto host, real FPS, frame drop rates, GPU% and projected link bitrate are NOT available using ordinary APIs. No claim of measuring pure CarVideo power use; phone charging changes energy measurement substantially.
- The owner-tested release candidate remains unchanged in main; RC3 is a distinct debug-signed install for evaluation.
