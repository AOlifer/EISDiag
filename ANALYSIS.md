# EISDiag — технический разбор

Как устроено приложение, откуда оно берёт данные машины и как его собрать и выпустить.
Для пользователя — [README](README.md) и [инструкция](docs/INSTRUCTION.md).

## Происхождение

EISDiag выделен из [EISWM](https://github.com/AOlifer/EISWM) («Менеджер приветствия»), где
диагностика была скрытым экраном. Её задача там — понять, какие свойства и события машины
сопровождают приветствие, прощание, сон и пробуждение головного устройства, чтобы EISWM
мог на них опираться. Отдельное приложение (`applicationId com.EISDiag`) ставится рядом
с EISWM и не мешает ему; из EISWM перенесены общие решения: тема (авто, светлая, тёмная),
окно Помощи, отказ от ответственности при первом запуске и самообновление.

## Платформа

- Головное устройство Evolute i-Space, прошивка Yato1, Android 9 (API 28).
- Штатный сервис машины — `BwCarService` (пакет `com.bw.car`). Клиентская библиотека к нему
  лежит в прошивке: `bw.car.proxy`, файл `/system/framework/BwCarProxy.jar`.
- Сервис отдаёт свойства только приложениям с system uid, поэтому в манифесте
  `android:sharedUserId="android.uid.system"`, а APK подписывается платформенным ключом прошивки.
  System uid заодно даёт `INSTALL_PACKAGES` (тихая установка обновлений) и доступ к флешке.

## Архитектура

| Класс | Назначение |
|---|---|
| `DiagnosticsActivity` | Главный экран: верхняя панель со значками (отметка, снимок, сохранение, очистка журнала, Помощь, тема, выход), панель записи справа, хвост журнала. |
| `CarApi` | Обёртка над `bw.car.*` через reflection: подключение, список свойств, чтение значений, подписки. Методов записи нет намеренно. |
| `CarDiag` | Журнал `events.log` и снимок состояния; фильтр секретных значений. |
| `CarDiagService` | Foreground-сервис записи событий в фоне (`START_STICKY`), свой `HandlerThread`. |
| `WakeReceiver` | Продолжает запись после загрузки и пробуждения машины, если она была включена. |
| `PickerActivity`, `FileUtils` | Выбор папки на флешке и копирование файлов. |
| `Updater`, `UpdateController` | Проверка, загрузка и установка обновлений; строка в шапке Помощи. |
| `BaseActivity`, `Prefs`, `Ui` | Тема, настройки (`SharedPreferences`), общие элементы интерфейса. |

Файлы диагностики лежат во внутренней папке приложения `files/diag/`: `events.log` (при 4 МБ
старый переименовывается в `events.1.log`) и `snapshot-ГГГГММДД-ччммсс.txt`. Содержимое —
технический текст на английском, не переводится.

## API машины

Библиотеки `bw.car.proxy` нет ни в SDK, ни в репозитории, поэтому она подключается через
`<uses-library android:required="false">`, а классы берутся через reflection
(`CarApi.java`). Если класса `bw.car.Car` нет (эмулятор, другая прошивка), приложение
запускается и пишет, что API недоступен.

Что используется:

- `bw.car.Car.createCar(Context, ServiceConnection, Handler)` и `connect()` — подключение к
  `BwCarService`; после падения сервиса `Car` переподключается сам.
- `getCarManager(name)` — менеджеры `property`, `power`, `car_setting`.
- **`property`** (`CarPropertyManager`):
  - `getPropertyList()` — конфигурации свойств: id, тип, доступ, `changeMode`
    (0 — статическое, 1 — при изменении, 2 — непрерывное), зоны, частоты опроса;
  - `getProperty(id, area)` — текущее значение и статус;
  - `registerListener(CarPropertyEventListener, id, rate)` — подписка; слушатель создаётся
    через `java.lang.reflect.Proxy`, события `onChangeEvent` и `onErrorEvent`.
- **`power`** (`CarPowerManager`): `getPowerState`, `getPrePowerState`, `getBootReason`,
  подписка `registerPowerStateListener`. Состояния: 0 `APU_STANDBY`, 1 `APU_RUNNING`,
  2 `DEEP_SLEEP_ENTER`, 3 `DEEP_SLEEP_EXIT`, 4 `SHUTDOWN_ENTER`, 5 `SHUTDOWN_CANCELLED`.
- **`car_setting`**: геттеры без аргументов — `isLocalAccOn`, `isIllumOn`, `isReverseActive`,
  `isSpeedHigh`, `isScreenOn`, `getNightMode`, `getBrightness`, `getStandbyMode`,
  `getPowerOffTime`, `getScreenSaverTimeout`, `getApuStatus`, `getRemoteStatus`.

Имена свойств подставляются из `assets/diag/property_names.txt` (строки `hex-id<TAB>имя`).
Сейчас этого файла в репозитории нет, поэтому свойства в журнале и снимке обозначены только
шестнадцатеричным id.

## Как работает диагностика

### Снимок (`CarDiag.snapshot`)

Одним файлом, в фоновом потоке (сотни запросов к машине):

1. Сборка прошивки (`Build.DISPLAY`, `Build.FINGERPRINT`) и ключевые свойства системы
   (`persist.bw.car.type`, `persist.bw.car.device`, `ro.vendor.bw.car_config_id` и др.).
2. Свойства системы, связанные с приветствием, прощанием и питанием: `service.bootanim.*`,
   `init.svc.banim_welcome`, `init.svc.banim_leave`, `runtime.backcar.*`,
   `vendor.bw.foreground.package` и др.
3. Файлы анимаций `/system/media/welcomeanimation.zip`, `leaveanimation.zip`,
   `bootanimation.zip` и список `/system/media`.
4. Все ключи `Settings.global`, `system`, `secure`.
5. Состояние питания, геттеры `car_setting` и все свойства машины по всем зонам с текущими
   значениями.

### Запись событий (`CarDiagService`)

При включении записи сразу делается снимок, затем сервис пишет в журнал:

- `POWER` — смены состояния питания;
- `PROP` — изменения всех нестатических свойств машины. Повтор того же значения
  пропускается; одно свойство и зона пишутся не чаще раза в секунду, непрерывные — раз в 30 с,
  кроме перехода через ноль; пропущенное отмечается `(+N skipped)`;
- `BROADCAST` — экран, сон, выключение, Bluetooth-подключения, а также броадкасты прошивки:
  `autochips.intent.action.QB_POWERON/OFF`, `PREQB_POWERON/OFF`, `ACTION_BOOT_IPO`,
  `com.bw.intent.action.WAKEUP`, `REBOOT_SOON`, `REST_TIME_SLEEP`,
  `com.bw.action.LAUNCHER_BOOT_COMPLETED` и др.;
- `SETTING` — любое изменение ключей Settings (через `ContentObserver`);
- `SYSPROP` — изменения отслеживаемых свойств системы (опрос раз в секунду);
- `MARK` — отметки пользователя, `SNAPSHOT` — сделанные снимки;
- `START`, `CAR` — служебные строки с причиной запуска, возрастом процесса и итогом
  подключения к машине.

Глубокий сон машины — фактически выключение: процесс приложения запускается заново.
Поэтому флаг записи хранится в настройках, а `WakeReceiver` ловит `BOOT_COMPLETED`,
`com.bw.intent.action.BOOT_COMPLETED`, `WAKEUP`, `LAUNCHER_BOOT_COMPLETED`,
`QB_POWERON`, `PREQB_POWERON`, `ACTION_BOOT_IPO` и снова запускает сервис.

### Секретные значения

В файлы не попадают значения ключей Settings, в имени которых есть слово вроде `token`,
`password`, `vin`, `uuid`, `imei`, `iccid`, `number`, `id`, `mac`, `address`, `ssid`,
`location`, `home` (остаётся только длина), и свойства машины `INFO_VIN`,
`SYSTEM_FCT_SETTING_VIN`, `…_UUID`, `…_TUID`, `…_ICCID`, `…_SERIAL_NUMBER`, `…_TBOX_SK_DATA`.

### Сохранение на флешку

System uid видит флешку в `/storage/XXXX-XXXX` только для чтения. Запись идёт через ту же
точку монтирования в `/mnt/media_rw/XXXX-XXXX` (разрешение `WRITE_MEDIA_STORAGE`), а при
сбое ФС флешки файл пишется напрямую; причина ошибки показывается в сообщении.

## Сборка

- Android Studio, Android Gradle Plugin 9, JDK 17.
- Флейворы:
  - `car` — APK для машины: system uid, подпись платформенным ключом прошивки;
  - `emulator` — для проверки интерфейса в эмуляторе: без system uid, debug-подпись,
    `applicationId` с суффиксом `.emulator`.
- `gradlew assembleCarRelease` → `app/build/outputs/apk/car/release/app-car-release.apk`.

### Ключи

Ключи в репозиторий не кладутся. Нужны `platform.x509.pem` и `platform.pk8`
(или `platform_binary.pk8`) — те же, что для EISWM. Где ищутся (первое найденное):

1. `-Peisdiag.keysDir=<путь>` или `eisdiag.keysDir=<путь>` в `%USERPROFILE%\.gradle\gradle.properties`;
2. переменная окружения `EISDIAG_KEYS_DIR`;
3. папка `keys/` в корне проекта (в `.gitignore`).

PKCS#12 для Gradle собирается из них автоматически в `build/signing/`. Без ключей упаковка
варианта `car` останавливается с понятным сообщением.

## Обновления

Приложение проверяет обновления само (при запуске не чаще раза в сутки) и по кнопке
«Проверить обновления» в шапке Помощи. Описание последней версии — JSON по адресу
`https://aolifer.github.io/EISDiag/updates/latest` (файл `docs/updates/latest`, GitHub Pages
из ветки `master`, папка `/docs`):

```json
{"versionCode": 6, "versionName": "0.2", "date": "2026-10-05",
 "apk": "https://github.com/AOlifer/EISDiag/releases/download/0.2/EISDiag-0.2.apk",
 "size": 1234567, "sha256": "…",
 "changes": {"ru": "…", "en": "…", "de": "…", "zh": "…"}}
```

Обязательны `versionCode`, `versionName` и `apk`. APK скачивается, сверяется с `sha256`,
именем пакета и номером версии и ставится через `PackageInstaller` (system uid, без окна
установщика). Вариант `emulator` обновление только скачивает и проверяет.

## Ветки

| Ветка | Назначение |
|---|---|
| `develop` | Основная разработка (ветка по умолчанию). Все изменения сливаются сюда. |
| `master` | Только релизы. Сюда сливается `develop` (`git merge --no-ff develop`), когда готова новая версия. |

Каждое изменение сразу записывается в [CHANGELOG.md](CHANGELOG.md) в раздел «Не выпущено»;
при выпуске раздел переименовывается в номер версии с датой.
