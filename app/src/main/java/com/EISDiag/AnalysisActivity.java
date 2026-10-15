package com.EISDiag;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Анализ журнала, два режима.
 * «События»: слева опорные события (отметки, питание, броадкасты, запуски записи), справа всё,
 * что записано за ±N секунд до и после выбранного, со сводкой изменений.
 * «Сеансы»: слева сеансы записи (от запуска процесса сервиса до сна, выключения или остановки),
 * справа их ход: питание, отметки, броадкасты, паузы и сбои.
 * Окно или отчёт о сеансе можно сохранить файлом рядом с журналом — он уйдёт на флешку вместе с ним.
 */
public class AnalysisActivity extends BaseActivity {
    /** Ширина окна в секундах (± от события). */
    private static final int[] WINDOW_SIZES = {5, 10, 30, 60};
    /** Не больше стольких событий в списке: строки списка — отдельные View. */
    private static final int MAX_ANCHORS = 500;

    /** Фильтры списка: подпись и типы строк журнала. */
    private static final int[] FILTER_LABELS = {
            R.string.analysis_type_marks, R.string.analysis_type_power,
            R.string.analysis_type_broadcasts, R.string.analysis_type_starts};
    private static final String[][] FILTER_TYPES = {
            {LogAnalysis.MARK}, {LogAnalysis.POWER}, {LogAnalysis.BROADCAST}, {LogAnalysis.START, "==="}};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;

    private LinearLayout modes, filters, anchorList, sizes;
    private android.view.View filterScroll;
    private ScrollView anchorScroll, windowScroll;
    private TextView anchorCount, windowTitle, windowSummary, windowText;

    private LogAnalysis analysis;
    private final Set<String> types = new HashSet<>();
    private LogAnalysis.Entry selected;
    private TextView selectedRow;
    private int seconds = 10;
    private LogAnalysis.Window window;
    /** Режим «Сеансы» вместо «События». */
    private boolean sessionsMode;
    private LogAnalysis.Session session;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_analysis);
        modes = findViewById(R.id.modeTabs);
        filters = findViewById(R.id.typeFilters);
        filterScroll = findViewById(R.id.filterScroll);
        anchorList = findViewById(R.id.anchorList);
        anchorScroll = findViewById(R.id.anchorScroll);
        anchorCount = findViewById(R.id.anchorCount);
        sizes = findViewById(R.id.windowSizes);
        windowTitle = findViewById(R.id.windowTitle);
        windowSummary = findViewById(R.id.windowSummary);
        windowText = findViewById(R.id.windowText);
        windowScroll = findViewById(R.id.windowScroll);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> load());
        findViewById(R.id.btnSaveWindow).setOnClickListener(v -> save());

        for (String[] t : FILTER_TYPES) types.addAll(Arrays.asList(t));
        buildModes();
        buildFilters();
        buildSizes();
        load();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Журнал читается в фоне: в нём может быть до двух файлов по 4 МБ. */
    private void load() {
        windowTitle.setText(R.string.analysis_loading);
        windowSummary.setText(null);
        windowText.setText(null);
        File dir = CarDiag.dir(this);
        new Thread(() -> {
            LogAnalysis a = LogAnalysis.load(new File(dir, CarDiag.LOG_OLD_NAME), new File(dir, CarDiag.LOG_NAME));
            ui.post(() -> {
                if (destroyed) return;
                analysis = a;
                showList();
            });
        }, "eisdiag-analysis").start();
    }

    // ---------------------------------------------------------------- Режим

    private void buildModes() {
        modes.removeAllViews();
        int[] labels = {R.string.analysis_mode_events, R.string.analysis_mode_sessions};
        for (int i = 0; i < labels.length; i++) {
            boolean sessions = i == 1;
            TextView chip = chip(getString(labels[i]), sessions == sessionsMode);
            chip.setOnClickListener(v -> {
                if (sessionsMode == sessions) return;
                sessionsMode = sessions;
                buildModes();
                showList();
            });
            modes.addView(chip);
        }
        int events = sessionsMode ? android.view.View.GONE : android.view.View.VISIBLE;
        filterScroll.setVisibility(events);
        sizes.setVisibility(events);
    }

    private void showList() {
        if (sessionsMode) showSessions();
        else showAnchors();
    }

    // ---------------------------------------------------------------- Список событий

    private void buildFilters() {
        filters.removeAllViews();
        for (int i = 0; i < FILTER_LABELS.length; i++) {
            String[] t = FILTER_TYPES[i];
            TextView chip = chip(getString(FILTER_LABELS[i]), types.contains(t[0]));
            chip.setOnClickListener(v -> {
                boolean on = !types.contains(t[0]);
                for (String s : t) {
                    if (on) types.add(s);
                    else types.remove(s);
                }
                setChip((TextView) v, on);
                showAnchors();
            });
            filters.addView(chip);
        }
    }

    private void showAnchors() {
        anchorList.removeAllViews();
        selectedRow = null;
        if (analysis == null) return;
        List<LogAnalysis.Entry> all = new ArrayList<>();
        for (LogAnalysis.Entry e : analysis.anchors()) if (types.contains(e.type)) all.add(e);
        if (all.isEmpty()) {
            Ui.emptyState(anchorList, getString(R.string.analysis_no_events), getString(R.string.analysis_no_events_hint));
            anchorCount.setText(null);
            selected = null;
            showWindow();
            return;
        }
        List<LogAnalysis.Entry> shown = all.size() > MAX_ANCHORS ? all.subList(0, MAX_ANCHORS) : all;
        anchorCount.setText(all.size() > shown.size()
                ? getString(R.string.analysis_events_limited, shown.size(), all.size())
                : getString(R.string.analysis_events, all.size()));
        // Выбранное событие остаётся выбранным после смены фильтра и обновления, если оно ещё в списке.
        LogAnalysis.Entry keep = null;
        for (LogAnalysis.Entry e : shown) {
            if (selected != null && e.index == selected.index && e.stamp.equals(selected.stamp)) keep = e;
        }
        if (keep == null) keep = shown.get(0);
        for (LogAnalysis.Entry e : shown) {
            TextView row = anchorRow(e);
            row.setOnClickListener(v -> select(e, (TextView) v));
            anchorList.addView(row);
            if (e == keep) select(e, row);
        }
        TextView first = selectedRow;
        if (first != null) anchorScroll.post(() -> anchorScroll.scrollTo(0, Math.max(0, first.getTop() - Ui.dp(this, 40))));
    }

    /** Строка списка событий: время события и его текст. */
    private TextView anchorRow(LogAnalysis.Entry e) {
        return listRow(e.stamp, e.text);
    }

    /** Строка списка: серая верхняя строка (время) и основная под ней. */
    private TextView listRow(String top, String main) {
        TextView v = new TextView(this);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(top);
        sb.setSpan(new ForegroundColorSpan(getColor(R.color.text_secondary)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append('\n').append(main);
        v.setText(sb);
        v.setTextSize(16);
        v.setTextColor(getColor(R.color.text_primary));
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setMinHeight(Ui.dp(this, 56));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        v.setBackgroundResource(Ui.selectableBackground(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 2);
        v.setLayoutParams(lp);
        return v;
    }

    private void select(LogAnalysis.Entry e, TextView row) {
        highlight(row);
        selected = e;
        showWindow();
    }

    private void highlight(TextView row) {
        if (selectedRow != null) selectedRow.setBackgroundResource(Ui.selectableBackground(this));
        selectedRow = row;
        row.setBackgroundResource(R.drawable.tab_selected);
    }

    // ---------------------------------------------------------------- Сеансы

    private void showSessions() {
        anchorList.removeAllViews();
        selectedRow = null;
        if (analysis == null) return;
        List<LogAnalysis.Session> all = analysis.sessions();
        if (all.isEmpty()) {
            Ui.emptyState(anchorList, getString(R.string.analysis_no_sessions), getString(R.string.analysis_no_events_hint));
            anchorCount.setText(null);
            session = null;
            showSession();
            return;
        }
        anchorCount.setText(getString(R.string.analysis_sessions, all.size()));
        // Выбранный сеанс узнаётся по первой строке: после обновления журнала это новые объекты.
        LogAnalysis.Session keep = all.get(0);
        for (LogAnalysis.Session s : all) {
            if (session != null && s.first().index == session.first().index && s.first().stamp.equals(session.first().stamp)) keep = s;
        }
        for (LogAnalysis.Session s : all) {
            String end = s.last().stamp.length() >= 12 ? s.last().stamp.substring(s.last().stamp.length() - 12, s.last().stamp.length() - 4) : "";
            String reason = s.reason();
            TextView row = listRow(s.first().stamp.replaceAll("\\.\\d+$", "") + "  →  " + end,
                    LogAnalysis.duration(s.duration()) + (reason.isEmpty() ? "" : "  ·  " + reason));
            row.setOnClickListener(v -> {
                highlight((TextView) v);
                session = s;
                showSession();
            });
            anchorList.addView(row);
            if (s == keep) {
                highlight(row);
                session = s;
                showSession();
            }
        }
        TextView first = selectedRow;
        if (first != null) anchorScroll.post(() -> anchorScroll.scrollTo(0, Math.max(0, first.getTop() - Ui.dp(this, 40))));
    }

    /** Ход сеанса справа: сводка сверху, затем питание, отметки, броадкасты, паузы и сбои. */
    private void showSession() {
        window = null;
        if (analysis == null || session == null) {
            windowTitle.setText(analysis == null ? getString(R.string.analysis_loading) : null);
            windowSummary.setText(null);
            windowText.setText(null);
            return;
        }
        LogAnalysis.Session s = session;
        windowTitle.setText(getString(R.string.analysis_session_title, s.first().stamp, s.last().stamp));
        List<String> parts = new ArrayList<>();
        parts.add(getString(R.string.analysis_session_duration, LogAnalysis.duration(s.duration())));
        if (s.gapBefore >= 0) parts.add(getString(R.string.analysis_session_off, LogAnalysis.duration(s.gapBefore)));
        if (!s.reason().isEmpty()) parts.add(getString(R.string.analysis_session_reason, s.reason()));
        parts.add(s.stopped ? getString(R.string.analysis_session_stopped)
                : getString(R.string.analysis_session_power, s.lastPower().isEmpty() ? "—" : s.lastPower()));
        parts.add(getString(R.string.analysis_session_problems, s.problems().size()));
        StringBuilder counts = new StringBuilder();
        for (Map.Entry<String, Integer> c : s.typeCounts().entrySet()) {
            if (counts.length() > 0) counts.append(", ");
            counts.append(c.getKey()).append(' ').append(c.getValue());
        }
        windowSummary.setText(TextUtils.join("  ·  ", parts) + "\n" + getString(R.string.analysis_session_lines, counts));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int secondary = getColor(R.color.text_secondary);
        int accent = getColor(R.color.accent);
        for (LogAnalysis.Entry e : s.timeline()) {
            int start = sb.length();
            sb.append(LogAnalysis.timelineLine(s, e));
            if (e.index < 0) {
                sb.setSpan(new ForegroundColorSpan(secondary), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (LogAnalysis.MARK.equals(e.type) || e.text.startsWith("PROP ERROR")) {
                sb.setSpan(new ForegroundColorSpan(accent), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (LogAnalysis.POWER.equals(e.type)) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append('\n');
        }
        windowText.setText(sb);
        windowScroll.post(() -> windowScroll.scrollTo(0, 0));
    }

    // ---------------------------------------------------------------- Окно

    private void buildSizes() {
        sizes.removeAllViews();
        for (int s : WINDOW_SIZES) {
            TextView chip = chip(getString(R.string.analysis_window_size, s), s == seconds);
            chip.setOnClickListener(v -> {
                seconds = s;
                buildSizes();
                showWindow();
            });
            sizes.addView(chip);
        }
    }

    private void showWindow() {
        window = null;
        if (analysis == null || selected == null) {
            windowTitle.setText(analysis == null ? getString(R.string.analysis_loading) : null);
            windowSummary.setText(null);
            windowText.setText(null);
            return;
        }
        window = analysis.window(selected, seconds);
        windowTitle.setText(selected.stamp + "  " + selected.text);
        windowSummary.setText(summary(window));
        windowText.setText(render(window));
        // Событие — в верхней трети, чтобы сразу было видно и что до, и что после.
        int anchorLine = window.lines.indexOf(selected);
        windowScroll.post(() -> {
            if (windowText.getLayout() == null) return;
            int y = windowText.getLayout().getLineTop(windowText.getLayout().getLineForOffset(offsetOfLine(anchorLine)));
            windowScroll.scrollTo(0, Math.max(0, y - windowScroll.getHeight() / 3));
        });
    }

    /** Смещение начала строки n в тексте окна (каждая строка окна заканчивается \n). */
    private int offsetOfLine(int n) {
        CharSequence t = windowText.getText();
        int line = 0;
        for (int i = 0; i < t.length(); i++) {
            if (line == n) return i;
            if (t.charAt(i) == '\n') line++;
        }
        return 0;
    }

    private String summary(LogAnalysis.Window w) {
        Map<String, Integer> counts = w.changeCounts();
        if (counts.isEmpty()) return getString(R.string.analysis_no_changes, w.lines.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.getKey()).append(' ').append(c.getValue());
        }
        String s = getString(R.string.analysis_changes, sb.toString(), w.changedKeys());
        if (w.truncated > 0) s += "  " + getString(R.string.analysis_truncated, w.truncated);
        return s;
    }

    /**
     * Строки окна: смещение от события, время и текст. Событие выделено цветом, другие
     * опорные события — жирным, чтобы видеть соседние отметки и смены питания.
     */
    private CharSequence render(LogAnalysis.Window w) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int secondary = getColor(R.color.text_secondary);
        int accent = getColor(R.color.accent);
        for (LogAnalysis.Entry e : w.lines) {
            int start = sb.length();
            String off = String.format(Locale.US, "%8s  ", LogAnalysis.offset(w.anchor, e));
            sb.append(off);
            sb.setSpan(new ForegroundColorSpan(secondary), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // Без даты: в окне в пределах минуты она одна и та же.
            String clock = e.stamp.length() >= 12 ? e.stamp.substring(e.stamp.length() - 12) : e.stamp;
            int textStart = sb.length();
            sb.append(clock).append("  ").append(e.text);
            if (e == w.anchor) {
                sb.setSpan(new ForegroundColorSpan(accent), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (e.isAnchor()) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), textStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            sb.append('\n');
        }
        return sb;
    }

    /**
     * Сохраняет окно (window-….txt) или отчёт о сеансе (session-….txt) в папку диагностики;
     * на флешку файл уходит вместе с журналом.
     */
    private void save() {
        String text = sessionsMode ? (session != null ? LogAnalysis.format(session) : null)
                : (window != null ? LogAnalysis.format(window) : null);
        if (text == null) {
            toast(getString(R.string.analysis_nothing_to_save));
            return;
        }
        String name = (sessionsMode ? "session-" : "window-")
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        File f = new File(CarDiag.dir(this), name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(text);
            toast(getString(R.string.analysis_window_saved, name));
        } catch (Exception e) {
            toast(getString(R.string.diag_error, String.valueOf(e.getMessage())));
        }
    }

    // ---------------------------------------------------------------- Общее

    /** Переключатель-«таблетка»: выбранный подсвечен, как пункт боковой колонки. */
    private TextView chip(String label, boolean on) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(16);
        v.setSingleLine(true);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(Ui.dp(this, 48));
        v.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        lp.setMarginEnd(Ui.dp(this, 4));
        v.setLayoutParams(lp);
        setChip(v, on);
        return v;
    }

    private void setChip(TextView v, boolean on) {
        Ui.setSelected(v, on);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
