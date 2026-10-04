package com.EISDiag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Загрузка и пробуждение машины. Глубокий сон машины — фактически выключение: процесс
 * приложения запускается заново, и запись нужно продолжить ({@link CarDiagService}).
 */
public class WakeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Prefs.get(c).getBoolean(Prefs.DIAG_RECORDING, false)) {
            CarDiagService.start(c, "broadcast " + i.getAction());
        }
    }
}
