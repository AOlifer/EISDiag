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

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
