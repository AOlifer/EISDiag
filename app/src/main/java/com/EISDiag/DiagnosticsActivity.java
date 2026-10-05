package com.EISDiag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Диагностика машины — главный экран приложения.
 * Снимок свойств машины, запись событий в фоне ({@link CarDiagService}), отметки в журнале
 * и сохранение файлов на флешку. В машину ничего не пишет.
 * Действия с журналом — значки на верхней панели, запись — на панели справа.
 */
public class DiagnosticsActivity extends BaseActivity {
    private static final int REQUEST_SAVE = 1;
    private static final long REFRESH_MS = 2000;
    private static final int TAIL_BYTES = 48 * 1024;

    private SharedPreferences prefs;
    private TextView status, log, files;
    private ScrollView scroll;
    private Button btnRecord;
    private View btnSnapshot, btnSave, btnClear;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler io;
    private CarApi car;
    private boolean busy = false;
    private boolean destroyed = false;
    private int marks = 0;
    private UpdateController updates;
    /** Одна ссылка на метод: removeCallbacks находит задачу только по тому же объекту. */
    private final Runnable refreshTask = this::refresh;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_diagnostics);
        prefs = Prefs.get(this);
        status = findViewById(R.id.diagStatus);
        log = findViewById(R.id.diagLog);
        files = findViewById(R.id.diagFiles);
        scroll = findViewById(R.id.diagScroll);
        btnRecord = findViewById(R.id.btnRecord);
        btnSnapshot = findViewById(R.id.btnSnapshot);
        btnSave = findViewById(R.id.btnSave);
        btnClear = findViewById(R.id.btnClearLog);

        btnRecord.setOnClickListener(v -> toggleRecording());
        findViewById(R.id.btnMark).setOnClickListener(v -> mark());
        btnSnapshot.setOnClickListener(v -> snapshot());
        btnSave.setOnClickListener(v -> openSaveFolderPicker());
        btnClear.setOnClickListener(v -> confirmClearLog());
        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.btnExit).setOnClickListener(v -> exitApp());
        View btnTheme = findViewById(R.id.btnTheme);
        String theme = getString(R.string.theme_desc, themeName(themeMode(this)));
        btnTheme.setContentDescription(theme);
        btnTheme.setTooltipText(theme);
        btnTheme.setOnClickListener(v -> switchTheme());

        thread = new HandlerThread("eisdiag-ui");
        thread.start();
        io = new Handler(thread.getLooper());
        io.post(this::connectCar);
        updateUi();
        updates = new UpdateController(this, prefs);
        if (b == null && !prefs.getBoolean(Prefs.DISCLAIMER_SHOWN, false)) showDisclaimer(true);
        if (b == null) updates.autoCheckForUpdates();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        updates.destroy();
        ui.removeCallbacksAndMessages(null);
        io.post(() -> {
            if (car != null) car.disconnect();
            thread.quitSafely();
        });
        super.onDestroy();
    }

    private void connectCar() {
        if (!CarApi.isAvailable()) {
            ui.post(this::updateUi);
            return;
        }
        car = new CarApi(this);
        try {
            car.connect(io, () -> ui.post(this::updateUi), () -> ui.post(this::updateUi));
        } catch (Throwable e) {
            car = null;
        }
    }

    private boolean recording() {
        return prefs.getBoolean(Prefs.DIAG_RECORDING, false);
    }

    private void toggleRecording() {
        boolean on = !recording();
        prefs.edit().putBoolean(Prefs.DIAG_RECORDING, on).apply();
        if (on) CarDiagService.start(this, "button");
        else CarDiagService.stop(this);
        updateUi();
        // Снимок при включении записи: без него в журнале нет полного списка свойств.
        if (on && !busy) snapshot();
        else ui.postDelayed(refreshTask, 500);
    }

    /** Отметка в журнале: пользователь нажимает её перед действием с машиной. */
    private void mark() {
        marks++;
        CarDiag.log(this, "MARK " + marks + " ------------------------------");
        toast(getString(R.string.diag_marked, marks));
        refresh();
    }

    private void snapshot() {
        setBusy(true);
        status.setText(R.string.diag_snapshot_busy);
        io.post(() -> {
            String msg;
            try {
                File f = CarDiag.snapshot(this, car);
                CarDiag.log(this, "SNAPSHOT " + f.getName());
                msg = getString(R.string.diag_snapshot_done, f.getName());
            } catch (Throwable e) {
                msg = getString(R.string.diag_error, String.valueOf(e.getMessage()));
            }
            final String m = msg;
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false);
                toast(m);
                refresh();
            });
        });
    }

    private void openSaveFolderPicker() {
        if (busy) return;
        Intent i = new Intent(this, PickerActivity.class)
                .putExtra(PickerActivity.EXTRA_TITLE, getString(R.string.save_where_title))
                .putExtra(PickerActivity.EXTRA_SUBJECT, folderName())
                .putExtra(PickerActivity.EXTRA_ACTION, getString(R.string.save_here));
        startActivityForResult(i, REQUEST_SAVE);
    }

    private static String folderName() {
        return "EISDiag-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE || resultCode != Activity.RESULT_OK || data == null) return;
        String folder = data.getStringExtra(PickerActivity.EXTRA_FOLDER);
        if (folder == null) return;
        String name = folderName();
        setBusy(true);
        io.post(() -> {
            String msg = saveFiles(new File(folder), name);
            ui.post(() -> {
                if (destroyed) return;
                setBusy(false);
                toast(msg);
            });
        });
    }

    /**
     * Копирует журнал и снимки в подпапку name выбранной папки. Если на флешке не создаётся
     * подпапка или не пишутся файлы через /storage, пробует /mnt/media_rw, затем саму папку.
     * @return текст для пользователя: куда сохранено или почему не получилось.
     */
    private String saveFiles(File folder, String name) {
        File[] src = CarDiag.files(this);
        List<File> targets = new ArrayList<>();
        targets.add(new File(folder, name));
        File rw = FileUtils.mediaRwPath(folder);
        if (rw != null) targets.add(new File(rw, name));
        targets.add(folder);
        String error = getString(R.string.diag_log_empty);
        for (File dst : targets) {
            //noinspection ResultOfMethodCallIgnored
            dst.mkdirs();
            if (!dst.isDirectory()) {
                error = "mkdir " + dst.getAbsolutePath();
                continue;
            }
            int ok = 0;
            String failed = null;
            for (File f : src) {
                if (!f.isFile()) continue;
                // В саму выбранную папку — с префиксом, чтобы не смешивать с чужими файлами.
                String n = dst.equals(folder) ? name + "-" + f.getName() : f.getName();
                failed = FileUtils.copyForExport(f, new File(dst, n));
                if (failed != null) break;
                ok++;
            }
            if (failed == null && ok > 0) return getString(R.string.saved, dst.getAbsolutePath());
            if (failed != null) error = failed;
        }
        return getString(R.string.save_failed, new File(folder, name).getAbsolutePath()) + "\n" + error;
    }

    /** Значок корзины легко задеть, поэтому журнал очищается только после подтверждения. */
    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.diag_clear)
                .setMessage(R.string.diag_clear_confirm)
                .setPositiveButton(R.string.diag_clear_yes, (d, w) -> clearLog())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearLog() {
        CarDiag.clearLog(this);
        if (recording()) CarDiag.log(this, "=== log cleared");
        refresh();
    }

    // ---------------------------------------------------------------- Помощь

    /** Описание программы, версия, разработчик и лицензия; оттуда же — отказ от ответственности. */
    private void showHelp() {
        TextView update = Ui.text(this, null, 16, R.color.accent);
        update.setBackgroundResource(Ui.selectableBackground(this));
        updates.bind(update);
        new AlertDialog.Builder(this)
                .setCustomTitle(helpTitle(update))
                .setMessage(getString(R.string.help_text) + "\n\n" + getString(R.string.help_developer)
                        + "\n\n" + getString(R.string.help_disclaimer_short))
                .setPositiveButton(R.string.got_it, null)
                .setNeutralButton(R.string.disclaimer_title, (d, w) -> showDisclaimer(false))
                .setOnDismissListener(d -> updates.bind(null))
                .show();
    }

    /** Шапка окна Помощи: название, под ним версия и «Проверить обновления». */
    private View helpTitle(TextView update) {
        int pad = Ui.dp(this, 24);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, Ui.dp(this, 4));
        TextView title = Ui.text(this, getString(R.string.diag_title), 22, R.color.text_primary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.text(this, getString(R.string.help_version, versionName()), 16, R.color.text_secondary));
        update.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        row.addView(update);
        box.addView(row);
        return box;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Отказ от ответственности (полный текст — DISCLAIMER.md в репозитории).
     * @param firstRun показывается сам при первом запуске; после «Понятно» больше не появляется.
     */
    private void showDisclaimer(boolean firstRun) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage(R.string.disclaimer_text)
                .setCancelable(!firstRun)
                .setPositiveButton(R.string.got_it, (d, w) ->
                        prefs.edit().putBoolean(Prefs.DISCLAIMER_SHOWN, true).apply())
                .show();
    }

    // ---------------------------------------------------------------- Экран

    private void refresh() {
        ui.removeCallbacks(refreshTask);
        if (destroyed) return;
        boolean atBottom = !scroll.canScrollVertically(1);
        String text = CarDiag.tail(this, TAIL_BYTES);
        log.setText(text.isEmpty() ? getString(R.string.diag_log_empty) : text);
        if (atBottom) scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        updateUi();
        ui.postDelayed(refreshTask, REFRESH_MS);
    }

    private void updateUi() {
        if (destroyed) return;
        boolean recording = recording();
        if (!busy) {
            int carState = !CarApi.isAvailable() ? R.string.diag_car_missing
                    : car != null && car.isConnected() ? R.string.diag_car_connected
                    : R.string.diag_car_connecting;
            status.setText(getString(recording ? R.string.diag_status_recording : R.string.diag_status_idle)
                    + "\n" + getString(carState));
        }
        btnRecord.setText(recording ? R.string.diag_record_stop : R.string.diag_record_start);
        long total = 0;
        File[] all = CarDiag.files(this);
        for (File f : all) total += f.length();
        files.setText(getString(R.string.diag_files, all.length, FileUtils.formatSize(this, total)));
        btnSave.setEnabled(!busy && all.length > 0);
        btnClear.setEnabled(!busy);
        btnSnapshot.setEnabled(!busy);
    }

    // ---------------------------------------------------------------- Тема и выход

    /** Авто → светлая → тёмная → авто; экран пересоздаётся с новой темой. */
    private void switchTheme() {
        int next = (themeMode(this) + 1) % THEME_COUNT;
        prefs.edit().putInt(Prefs.THEME, next).apply();
        toast(getString(R.string.theme_toast, themeName(next)));
        recreate();
    }

    /** Системная «Назад» на главном экране работает так же, как кнопка «Выход». */
    @Override public void onBackPressed() {
        exitApp();
    }

    /**
     * Закрыть приложение и убрать его из недавних; запись событий в фоне продолжается.
     * Пока делается снимок или сохраняются файлы, сначала спросить.
     */
    private void exitApp() {
        if (!busy) {
            finishAndRemoveTask();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.exit_busy_title)
                .setMessage(R.string.exit_busy_message)
                .setPositiveButton(R.string.exit_wait, null)
                .setNegativeButton(R.string.exit_now, (d, w) -> finishAndRemoveTask())
                .show();
    }

    private void setBusy(boolean value) {
        busy = value;
        updateUi();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
