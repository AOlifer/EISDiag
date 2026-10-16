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
 * «Сценарий»: все события одного вида (например, каждое пробуждение) совмещаются, справа —
 * что меняется или происходит рядом в нескольких циклах, сначала то, что было в каждом.
 * «Свойства»: какие свойства машины, ключи Settings и свойства системы менялись, сколько раз,
 * с какими значениями; самые шумные сверху.
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
    private static final int MODE_EVENTS = 0, MODE_SESSIONS = 1, MODE_SCENARIO = 2, MODE_STATS = 3;
    /** Статистика по ключам считается вместе с чтением журнала; выбранный ключ. */
    private List<LogAnalysis.KeyStats> stats;
    private String statKey;
    /** Не больше стольких ключей в списке и значений справа. */
    private static final int MAX_KEYS = 500, MAX_VALUES = 30;
    private int mode = MODE_EVENTS;
    private LogAnalysis.Session session;
    /** Выбранный вид события в режиме «Сценарий» и посчитанный сценарий. */
    private String kind;
    private LogAnalysis.Scenario scenario;
    /** Номер расчёта сценария: результат устаревшего расчёта (сменили вид или окно) не показывается. */
    private int scenarioRun;
    /** Не больше стольких строк сценария на экране; в файл попадают все. */
    private static final int MAX_SCENARIO_ROWS = 400;

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
            List<LogAnalysis.KeyStats> st = a.keyStats();
            ui.post(() -> {
                if (destroyed) return;
                analysis = a;
                stats = st;
                showList();
            });
        }, "eisdiag-analysis").start();
    }

    // ---------------------------------------------------------------- Режим

    private void buildModes() {
        modes.removeAllViews();
        int[] labels = {R.string.analysis_mode_events, R.string.analysis_mode_sessions,
                R.string.analysis_mode_scenario, R.string.analysis_mode_stats};
        for (int i = 0; i < labels.length; i++) {
            int m = i;
            TextView chip = chip(getString(labels[i]), m == mode);
            chip.setOnClickListener(v -> {
                if (mode == m) return;
                mode = m;
                buildModes();
                showList();
            });
            modes.addView(chip);
        }
        filterScroll.setVisibility(mode == MODE_EVENTS ? android.view.View.VISIBLE : android.view.View.GONE);
        sizes.setVisibility(mode == MODE_EVENTS || mode == MODE_SCENARIO ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void showList() {
        if (mode == MODE_SESSIONS) showSessions();
        else if (mode == MODE_SCENARIO) showKinds();
        else if (mode == MODE_STATS) showKeys();
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

    // ---------------------------------------------------------------- Свойства

    /** Ключи, которые менялись: самые частые сверху. */
    private void showKeys() {
        anchorList.removeAllViews();
        selectedRow = null;
        window = null;
        if (analysis == null || stats == null) return;
        if (stats.isEmpty()) {
            Ui.emptyState(anchorList, getString(R.string.analysis_no_keys), getString(R.string.analysis_no_events_hint));
            anchorCount.setText(null);
            statKey = null;
            showKey(null);
            return;
        }
        List<LogAnalysis.KeyStats> shown = stats.size() > MAX_KEYS ? stats.subList(0, MAX_KEYS) : stats;
        anchorCount.setText(stats.size() > shown.size()
                ? getString(R.string.analysis_keys_limited, shown.size(), stats.size())
                : getString(R.string.analysis_keys, stats.size()));
        LogAnalysis.KeyStats keep = shown.get(0);
        for (LogAnalysis.KeyStats k : shown) if (k.key.equals(statKey)) keep = k;
        for (LogAnalysis.KeyStats k : shown) {
            TextView row = listRow(getString(R.string.analysis_key_row, k.changes, k.values.size()), k.key);
            row.setOnClickListener(v -> {
                highlight((TextView) v);
                showKey(k);
            });
            anchorList.addView(row);
            if (k == keep) {
                highlight(row);
                showKey(k);
            }
        }
        TextView first = selectedRow;
        if (first != null) anchorScroll.post(() -> anchorScroll.scrollTo(0, Math.max(0, first.getTop() - Ui.dp(this, 40))));
    }

    /** Справа: сводка по ключу, частые значения и последние изменения. */
    private void showKey(LogAnalysis.KeyStats k) {
        if (k == null) {
            windowTitle.setText(analysis == null ? getString(R.string.analysis_loading) : null);
            windowSummary.setText(null);
            windowText.setText(null);
            return;
        }
        statKey = k.key;
        windowTitle.setText(k.key);
        List<String> parts = new ArrayList<>();
        parts.add(getString(R.string.analysis_key_changes, k.changes));
        if (k.skipped > 0) parts.add(getString(R.string.analysis_key_skipped, k.skipped));
        parts.add(getString(R.string.analysis_key_values, k.values.size()));
        if (k.range() != null) parts.add(getString(R.string.analysis_key_range, k.range()));
        windowSummary.setText(TextUtils.join("  ·  ", parts) + "\n"
                + getString(R.string.analysis_key_period, k.first.stamp, k.last.stamp));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        int secondary = getColor(R.color.text_secondary);
        heading(sb, getString(R.string.analysis_key_top_values));
        int n = 0;
        for (Map.Entry<String, Integer> v : k.topValues()) {
            if (n++ >= MAX_VALUES) {
                sb.append(getString(R.string.analysis_truncated, k.values.size() - MAX_VALUES)).append('\n');
                break;
            }
            sb.append(String.format(Locale.US, "%7d  %s\n", v.getValue(), v.getKey()));
        }
        sb.append('\n');
        heading(sb, getString(R.string.analysis_key_recent, k.recent.size()));
        for (int i = k.recent.size() - 1; i >= 0; i--) {
            LogAnalysis.Entry e = k.recent.get(i);
            int start = sb.length();
            sb.append(e.stamp);
            sb.setSpan(new ForegroundColorSpan(secondary), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ").append(LogAnalysis.changeValue(e)).append('\n');
        }
        windowText.setText(sb);
        windowScroll.post(() -> windowScroll.scrollTo(0, 0));
    }

    private static void heading(SpannableStringBuilder sb, String text) {
        int start = sb.length();
        sb.append(text).append('\n');
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------------------------------------------------------- Сценарий

    /** Виды событий, которые повторялись хотя бы дважды: каждый — набор совмещаемых циклов. */
    private void showKinds() {
        anchorList.removeAllViews();
        selectedRow = null;
        if (analysis == null) return;
        Map<String, Integer> kinds = analysis.kinds();
        if (kinds.isEmpty()) {
            Ui.emptyState(anchorList, getString(R.string.analysis_no_kinds), getString(R.string.analysis_no_kinds_hint));
            anchorCount.setText(null);
            kind = null;
            showScenario();
            return;
        }
        anchorCount.setText(getString(R.string.analysis_kinds, kinds.size()));
        String keep = kinds.containsKey(kind) ? kind : kinds.keySet().iterator().next();
        for (Map.Entry<String, Integer> k : kinds.entrySet()) {
            TextView row = listRow(getString(R.string.analysis_kind_count, k.getValue()), k.getKey());
            row.setOnClickListener(v -> {
                highlight((TextView) v);
                kind = k.getKey();
                showScenario();
            });
            anchorList.addView(row);
            if (k.getKey().equals(keep)) {
                highlight(row);
                kind = keep;
                showScenario();
            }
        }
        TextView first = selectedRow;
        if (first != null) anchorScroll.post(() -> anchorScroll.scrollTo(0, Math.max(0, first.getTop() - Ui.dp(this, 40))));
    }

    /** Сценарий считается в фоне: каждый цикл — своё окно по журналу. */
    private void showScenario() {
        window = null;
        scenario = null;
        int run = ++scenarioRun;
        if (analysis == null || kind == null) {
            windowTitle.setText(analysis == null ? getString(R.string.analysis_loading) : null);
            windowSummary.setText(null);
            windowText.setText(null);
            return;
        }
        String k = kind;
        int sec = seconds;
        LogAnalysis a = analysis;
        windowTitle.setText(k);
        windowSummary.setText(R.string.analysis_scenario_busy);
        windowText.setText(null);
        new Thread(() -> {
            LogAnalysis.Scenario sc = a.scenario(k, sec);
            ui.post(() -> {
                if (destroyed || run != scenarioRun || mode != MODE_SCENARIO) return;
                scenario = sc;
                renderScenario(sc);
            });
        }, "eisdiag-scenario").start();
    }

    /** Строки сценария: в скольких циклах, смещение (медиана и разброс), ключ и частое значение. */
    private void renderScenario(LogAnalysis.Scenario sc) {
        windowSummary.setText(getString(R.string.analysis_scenario_summary, sc.cycles, sc.rows.size(), sc.seconds)
                + (sc.rows.size() > MAX_SCENARIO_ROWS ? "  " + getString(R.string.analysis_truncated, sc.rows.size() - MAX_SCENARIO_ROWS) : ""));
        if (sc.rows.isEmpty()) {
            windowText.setText(R.string.analysis_scenario_empty);
            return;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int secondary = getColor(R.color.text_secondary);
        int accent = getColor(R.color.accent);
        int n = 0;
        for (LogAnalysis.ScenarioRow r : sc.rows) {
            if (n++ >= MAX_SCENARIO_ROWS) break;
            int start = sb.length();
            sb.append(LogAnalysis.scenarioLine(sc, r));
            // Было в каждом цикле — выделено: это кандидаты в «подпись» события.
            if (r.cycles == sc.cycles) {
                sb.setSpan(new ForegroundColorSpan(accent), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (r.cycles * 2 < sc.cycles) {
                sb.setSpan(new ForegroundColorSpan(secondary), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                if (mode == MODE_SCENARIO) showScenario();
                else showWindow();
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
     * Сохраняет окно (window-….txt), отчёт о сеансе (session-….txt) или сценарий
     * (scenario-….txt) или таблицу всех ключей (stats-….txt) в папку диагностики;
     * на флешку файл уходит вместе с журналом.
     */
    private void save() {
        String text = mode == MODE_SESSIONS ? (session != null ? LogAnalysis.format(session) : null)
                : mode == MODE_SCENARIO ? (scenario != null ? LogAnalysis.format(scenario) : null)
                : mode == MODE_STATS ? (stats != null && !stats.isEmpty() ? LogAnalysis.formatStats(stats) : null)
                : (window != null ? LogAnalysis.format(window) : null);
        if (text == null) {
            toast(getString(R.string.analysis_nothing_to_save));
            return;
        }
        String name = (mode == MODE_SESSIONS ? "session-" : mode == MODE_SCENARIO ? "scenario-"
                : mode == MODE_STATS ? "stats-" : "window-")
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
