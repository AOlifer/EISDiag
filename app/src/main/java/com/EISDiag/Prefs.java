package com.EISDiag;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения: один файл SharedPreferences и все его ключи в одном месте.
 */
final class Prefs {
    private static final String FILE = "eisdiag";

    /** Последняя открытая папка на экране выбора папки. */
    static final String PICKER_LAST_DIR = "picker_last_dir";
    /** Идёт запись событий ({@link CarDiagService}); после перезагрузки и сна машины запись продолжается. */
    static final String DIAG_RECORDING = "diag_recording";

    /** Отказ от ответственности уже показан при первом запуске и принят. */
    static final String DISCLAIMER_SHOWN = "disclaimer_shown";

    /** Тема: {@link BaseActivity#THEME_AUTO}, THEME_LIGHT или THEME_DARK. */
    static final String THEME = "theme";

    // Обновления
    static final String UPDATE_LAST_CHECK = "update_last_check";
    /** Последняя найденная на сервере версия: строка «Доступна версия N» видна до установки. */
    static final String UPDATE_CODE = "update_available_code";
    static final String UPDATE_NAME = "update_available_name";
    /** Открыть приложение после установки обновления. */
    static final String UPDATE_REOPEN = "update_reopen";

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
