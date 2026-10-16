package com.EISDiag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Разбор журнала events.log для экрана «Анализ»: строки с временем и типом, опорные события
 * и окно вокруг события. Без зависимостей от Android, чтобы проверять отдельно.
 *
 * Метка времени: «ГГГГ-ММ-ДД чч:мм:сс.ммм», в журналах до появления года — «ММ-ДД чч:мм:сс.ммм».
 * Для старых строк год восстанавливается с конца: от года более поздней строки или года
 * изменения файла, а переход назад через Новый год (январь → декабрь) уменьшает год на 1.
 */
final class LogAnalysis {
    /** Типы строк, вокруг которых строится окно. */
    static final String MARK = "MARK", POWER = "POWER", BROADCAST = "BROADCAST", START = "START";
    /** Изменения состояния, которые считаются в сводке окна. */
    static final String[] CHANGE_TYPES = {"PROP", "SETTING", "SYSPROP"};
    /** Не больше стольких строк по каждую сторону от события: непрерывные свойства шумят. */
    static final int MAX_SIDE_LINES = 3000;

    /** Строка журнала. */
    static final class Entry {
        /** Номер строки по порядку в обоих файлах (events.1.log, затем events.log). */
        final int index;
        /** Время в мс; для строк без метки — время предыдущей строки. */
        long time;
        /** Метка времени, как в файле. */
        final String stamp;
        /** Первое слово после метки: MARK, PROP, POWER…; «===» для служебных строк. */
        final String type;
        /** Текст после метки. */
        final String text;
        /** Месяц, день и время старой метки без года; -1 у новых строк. */
        final int month, day;
        final String clock;

        Entry(int index, String stamp, int month, int day, String clock, String text) {
            this.index = index;
            this.stamp = stamp;
            this.month = month;
            this.day = day;
            this.clock = clock;
            this.text = text;
            int sp = text.indexOf(' ');
            this.type = sp < 0 ? text : text.substring(0, sp);
        }

        boolean isAnchor() {
            return MARK.equals(type) || POWER.equals(type) || BROADCAST.equals(type)
                    || START.equals(type) || "===".equals(type);
        }
    }

    /** Окно вокруг события: строки до и после в пределах ±seconds. */
    static final class Window {
        final Entry anchor;
        final int seconds;
        final List<Entry> lines = new ArrayList<>();
        /** Сколько строк не вошло из-за MAX_SIDE_LINES. */
        int truncated;

        Window(Entry anchor, int seconds) {
            this.anchor = anchor;
            this.seconds = seconds;
        }

        /** Число изменений по типам (PROP, SETTING, SYSPROP), без нулевых. */
        Map<String, Integer> changeCounts() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (String t : CHANGE_TYPES) {
                int n = 0;
                for (Entry e : lines) if (t.equals(e.type)) n++;
                if (n > 0) m.put(t, n);
            }
            return m;
        }

        /** Сколько разных свойств, ключей Settings и свойств системы менялось. */
        int changedKeys() {
            List<String> keys = new ArrayList<>();
            for (Entry e : lines) {
                String k = changeKey(e);
                if (k != null && !keys.contains(k)) keys.add(k);
            }
            return keys.size();
        }
    }

    private final List<Entry> entries;

    private LogAnalysis(List<Entry> entries) {
        this.entries = entries;
    }

    List<Entry> entries() {
        return entries;
    }

    /** Опорные события, новые сверху. */
    List<Entry> anchors() {
        List<Entry> a = new ArrayList<>();
        for (Entry e : entries) if (e.isAnchor()) a.add(e);
        Collections.reverse(a);
        return a;
    }

    /**
     * Строки в пределах ±seconds от события. Идём от события в обе стороны, пока время
     * не выйдет за окно, а не отбираем по времени весь журнал: при скачке часов машины
     * (синхронизация времени) в окно не попадут строки из другого сеанса.
     */
    Window window(Entry anchor, int seconds) {
        Window w = new Window(anchor, seconds);
        long span = seconds * 1000L;
        int i = anchor.index;
        List<Entry> before = new ArrayList<>();
        for (int j = i - 1; j >= 0; j--) {
            Entry e = entries.get(j);
            if (Math.abs(anchor.time - e.time) > span) break;
            if (before.size() >= MAX_SIDE_LINES) {
                w.truncated++;
                continue;
            }
            before.add(e);
        }
        Collections.reverse(before);
        w.lines.addAll(before);
        w.lines.add(anchor);
        int after = 0;
        for (int j = i + 1; j < entries.size(); j++) {
            Entry e = entries.get(j);
            if (Math.abs(e.time - anchor.time) > span) break;
            if (after >= MAX_SIDE_LINES) {
                w.truncated++;
                continue;
            }
            w.lines.add(e);
            after++;
        }
        return w;
    }

    /**
     * Ключ изменения: свойство с зоной, ключ Settings или свойство системы — всё до « = ».
     * null, если строка не изменение.
     */
    static String changeKey(Entry e) {
        boolean change = false;
        for (String t : CHANGE_TYPES) if (t.equals(e.type)) change = true;
        if (!change) return null;
        int eq = e.text.indexOf(" = ");
        return eq < 0 ? e.text : e.text.substring(0, eq);
    }

    /** Смещение от события: «−3.2 s», «+0.0 s». */
    static String offset(Entry anchor, Entry e) {
        long d = e.time - anchor.time;
        return String.format(Locale.US, "%s%d.%d s", d < 0 ? "-" : "+", Math.abs(d) / 1000, Math.abs(d) % 1000 / 100);
    }

    /** Текст окна для файла: заголовок и строки со смещением от события. */
    static String format(Window w) {
        StringBuilder sb = new StringBuilder();
        sb.append("EISDiag log window ±").append(w.seconds).append(" s around:\n");
        sb.append(w.anchor.stamp).append("  ").append(w.anchor.text).append("\n");
        Map<String, Integer> counts = w.changeCounts();
        sb.append("changes:");
        if (counts.isEmpty()) sb.append(" none");
        for (Map.Entry<String, Integer> c : counts.entrySet()) sb.append(' ').append(c.getKey()).append(' ').append(c.getValue());
        sb.append(", keys ").append(w.changedKeys());
        if (w.truncated > 0) sb.append(", not shown ").append(w.truncated);
        sb.append("\n\n");
        for (Entry e : w.lines) {
            sb.append(e == w.anchor ? ">> " : "   ");
            sb.append(String.format(Locale.US, "%9s  ", offset(w.anchor, e)));
            sb.append(e.stamp).append("  ").append(e.text).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- Статистика свойств

    /** Сколько раз менялось одно свойство, ключ Settings или свойство системы и какие были значения. */
    static final class KeyStats {
        final String key;
        int changes;
        /** Сумма «(+N skipped)»: изменения, которые сервис не записал из-за ограничения частоты. */
        int skipped;
        /** Значение → сколько раз, в порядке появления. */
        final Map<String, Integer> values = new LinkedHashMap<>();
        Entry first, last;
        /** Все значения — числа: тогда есть диапазон. */
        boolean numeric = true;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        /** Последние изменения (не больше MAX_RECENT) — для экрана. */
        final List<Entry> recent = new ArrayList<>();

        KeyStats(String key) {
            this.key = key;
        }

        String type() {
            int sp = key.indexOf(' ');
            return sp < 0 ? key : key.substring(0, sp);
        }

        /** Значения по убыванию числа появлений. */
        List<Map.Entry<String, Integer>> topValues() {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(values.entrySet());
            Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
            return l;
        }

        /** Диапазон «min … max» для числовых значений, иначе null. */
        String range() {
            if (!numeric || changes == 0) return null;
            return number(min) + " … " + number(max);
        }

        private static String number(double d) {
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
    }

    static final int MAX_RECENT = 50;

    /** Значение изменения: всё после « = » без « status=N» и «(+N skipped)». */
    static String changeValue(Entry e) {
        int eq = e.text.indexOf(" = ");
        if (eq < 0) return "";
        String v = e.text.substring(eq + 3).replaceFirst("\\s+\\(\\+\\d+ skipped\\)$", "");
        return v.replaceFirst(" status=-?\\d+$", "");
    }

    private static final java.util.regex.Pattern SKIPPED = java.util.regex.Pattern.compile("\\(\\+(\\d+) skipped\\)$");

    private static int skippedCount(Entry e) {
        java.util.regex.Matcher m = SKIPPED.matcher(e.text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** Статистика по всем ключам, которые менялись, — самые частые сверху. */
    List<KeyStats> keyStats() {
        Map<String, KeyStats> m = new LinkedHashMap<>();
        for (Entry e : entries) {
            String k = changeKey(e);
            if (k == null) continue;
            KeyStats s = m.get(k);
            if (s == null) m.put(k, s = new KeyStats(k));
            String v = changeValue(e);
            s.changes++;
            s.skipped += skippedCount(e);
            Integer n = s.values.get(v);
            s.values.put(v, n == null ? 1 : n + 1);
            if (s.first == null) s.first = e;
            s.last = e;
            if (s.numeric) {
                try {
                    double d = Double.parseDouble(v);
                    s.min = Math.min(s.min, d);
                    s.max = Math.max(s.max, d);
                } catch (NumberFormatException ex) {
                    s.numeric = false;
                }
            }
            s.recent.add(e);
            if (s.recent.size() > MAX_RECENT) s.recent.remove(0);
        }
        List<KeyStats> list = new ArrayList<>(m.values());
        Collections.sort(list, (a, b) -> b.changes != a.changes ? b.changes - a.changes : a.key.compareTo(b.key));
        return list;
    }

    /** Строка таблицы: «изменений  пропущено  значений  ключ  диапазон или частое значение». */
    static String statsLine(KeyStats s) {
        String r = s.range();
        String extra = r != null ? "range " + r : "top " + s.topValues().get(0).getKey();
        return String.format(Locale.US, "%7d %8d %7d  %s  %s", s.changes, s.skipped, s.values.size(), s.key, extra);
    }

    /** Таблица всех ключей для файла. */
    static String formatStats(List<KeyStats> stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("EISDiag key statistics: ").append(stats.size()).append(" keys changed\n");
        sb.append("changes  skipped  values  key  range or most common value\n\n");
        for (KeyStats s : stats) sb.append(statsLine(s)).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- Сценарий

    /**
     * Вид опорного события, по которому совмещаются циклы: «MARK», «POWER 2 DEEP_SLEEP_ENTER»,
     * «BROADCAST <action>», «START <причина>», «=== recording started». null — не опорное.
     */
    static String kind(Entry e) {
        if (!e.isAnchor()) return null;
        if (MARK.equals(e.type)) return MARK;
        if (BROADCAST.equals(e.type)) {
            String[] w = e.text.split(" ");
            return w.length > 1 ? BROADCAST + " " + w[1] : BROADCAST;
        }
        int comma = e.text.indexOf(", process age");
        if (comma < 0 && "===".equals(e.type)) comma = e.text.indexOf(',');
        return comma >= 0 ? e.text.substring(0, comma) : e.text;
    }

    /** Ключ строки в сценарии: изменение (до « = ») или вид опорного события; null — пропустить. */
    static String scenarioKey(Entry e) {
        String k = changeKey(e);
        return k != null ? k : kind(e);
    }

    /** Виды опорных событий, которые встречаются хотя бы дважды, по убыванию числа. */
    Map<String, Integer> kinds() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Entry e : entries) {
            String k = kind(e);
            if (k == null) continue;
            Integer n = m.get(k);
            m.put(k, n == null ? 1 : n + 1);
        }
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> sorted = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : l) if (e.getValue() >= 2) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }

    /** Строка сценария: что происходит рядом с событием и в скольких циклах. */
    static final class ScenarioRow {
        final String key;
        /** В скольких циклах встретилось. */
        int cycles;
        /** Смещение от события в каждом цикле, мс (ближайшее к событию вхождение). */
        final List<Long> offsets = new ArrayList<>();
        /** Значение изменения в каждом цикле («1», «true»); null у событий. */
        final List<String> values = new ArrayList<>();

        ScenarioRow(String key) {
            this.key = key;
        }

        long median() {
            List<Long> o = new ArrayList<>(offsets);
            Collections.sort(o);
            return o.get(o.size() / 2);
        }

        long min() {
            return Collections.min(offsets);
        }

        long max() {
            return Collections.max(offsets);
        }

        /** Самое частое значение и в скольких циклах оно было; null, если значений нет. */
        String topValue() {
            String best = null;
            int bestN = 0;
            for (String v : values) {
                if (v == null) continue;
                int n = Collections.frequency(values, v);
                if (n > bestN) {
                    best = v;
                    bestN = n;
                }
            }
            return best == null ? null : best + " (" + bestN + "/" + cycles + ")";
        }
    }

    /** Повторяющийся сценарий: что есть рядом с событием данного вида в нескольких циклах. */
    static final class Scenario {
        final String kind;
        final int seconds;
        int cycles;
        final List<ScenarioRow> rows = new ArrayList<>();

        Scenario(String kind, int seconds) {
            this.kind = kind;
            this.seconds = seconds;
        }
    }

    /**
     * Совмещает все события вида kind и собирает, что меняется или происходит в окне ±seconds
     * в каждом из них. В сценарий попадает то, что было хотя бы в двух циклах; сверху — то,
     * что было в большем числе циклов, при равенстве — ближе к событию.
     */
    Scenario scenario(String kind, int seconds) {
        Scenario sc = new Scenario(kind, seconds);
        Map<String, ScenarioRow> rows = new LinkedHashMap<>();
        for (Entry a : entries) {
            if (!kind.equals(kind(a))) continue;
            sc.cycles++;
            Map<String, Entry> nearest = new LinkedHashMap<>();
            for (Entry e : window(a, seconds).lines) {
                if (e == a) continue;
                String k = scenarioKey(e);
                if (k == null) continue;
                Entry prev = nearest.get(k);
                if (prev == null || Math.abs(e.time - a.time) < Math.abs(prev.time - a.time)) nearest.put(k, e);
            }
            for (Map.Entry<String, Entry> n : nearest.entrySet()) {
                ScenarioRow r = rows.get(n.getKey());
                if (r == null) rows.put(n.getKey(), r = new ScenarioRow(n.getKey()));
                Entry e = n.getValue();
                r.cycles++;
                r.offsets.add(e.time - a.time);
                int eq = e.text.indexOf(" = ");
                r.values.add(changeKey(e) != null && eq >= 0 ? e.text.substring(eq + 3) : null);
            }
        }
        for (ScenarioRow r : rows.values()) if (r.cycles >= 2) sc.rows.add(r);
        Collections.sort(sc.rows, (x, y) -> x.cycles != y.cycles ? y.cycles - x.cycles
                : Long.compare(Math.abs(x.median()), Math.abs(y.median())));
        return sc;
    }

    /** Строка сценария: «5/5  +0.4 s (+0.1…+0.9)  ключ = значение (5/5)». */
    static String scenarioLine(Scenario sc, ScenarioRow r) {
        String spread = r.cycles > 1 ? " (" + seconds(r.min()) + "…" + seconds(r.max()) + ")" : "";
        String v = r.topValue();
        return String.format(Locale.US, "%5s  %8s%-20s  %s", r.cycles + "/" + sc.cycles, seconds(r.median()), spread,
                r.key + (v != null ? " = " + v : ""));
    }

    private static String seconds(long ms) {
        return String.format(Locale.US, "%s%d.%d", ms < 0 ? "-" : "+", Math.abs(ms) / 1000, Math.abs(ms) % 1000 / 100);
    }

    /** Сценарий для файла. */
    static String format(Scenario sc) {
        StringBuilder sb = new StringBuilder();
        sb.append("EISDiag recurring scenario: ").append(sc.kind).append('\n');
        sb.append("cycles ").append(sc.cycles).append(", window ±").append(sc.seconds).append(" s")
                .append(", rows ").append(sc.rows.size()).append("\n");
        sb.append("cycles  median offset s (min…max)  key = most common value (cycles)\n\n");
        for (ScenarioRow r : sc.rows) sb.append(scenarioLine(sc, r)).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- Сеансы

    /** Тишина в журнале дольше этого внутри сеанса — пауза (сон без перезапуска процесса). */
    static final long PAUSE_MS = 2 * 60 * 1000L;
    /** Строки с этими словами — сбои записи: ошибки свойств, отвалы сервиса машины. */
    private static final String[] PROBLEMS = {
            "PROP ERROR", "CAR service disconnected", "CAR connect failed", "CAR property list failed",
            "POWER listener failed", "CAR bw.car.proxy library is not available"};

    /**
     * Сеанс записи: от «=== recording started» (новый процесс сервиса — после загрузки или
     * глубокого сна машины) до следующего такого же или до «=== recording stopped».
     */
    static final class Session {
        final List<Entry> lines = new ArrayList<>();
        /** Время от конца прошлого сеанса до начала этого, мс; -1 у первого. */
        long gapBefore = -1;
        /** Остановлен кнопкой («=== recording stopped»), а не оборвался сном или выключением. */
        boolean stopped;

        Entry first() {
            return lines.get(0);
        }

        Entry last() {
            return lines.get(lines.size() - 1);
        }

        long duration() {
            return last().time - first().time;
        }

        /** Причина запуска из первой строки START: кнопка, загрузка, броадкаст пробуждения. */
        String reason() {
            for (Entry e : lines) {
                if (!START.equals(e.type)) continue;
                String r = e.text.substring(START.length()).trim();
                int age = r.indexOf(", process age");
                return age >= 0 ? r.substring(0, age) : r;
            }
            return "";
        }

        /** Последнее состояние питания в сеансе («2 DEEP_SLEEP_ENTER»), пустое — если смен не было. */
        String lastPower() {
            for (int i = lines.size() - 1; i >= 0; i--) {
                Entry e = lines.get(i);
                if (POWER.equals(e.type) && !e.text.contains("failed")) return e.text.substring(POWER.length()).trim();
            }
            return "";
        }

        /** Строки-сбои: ошибки свойств, отвалы и неудачные подключения к сервису машины. */
        List<Entry> problems() {
            List<Entry> p = new ArrayList<>();
            for (Entry e : lines) {
                for (String s : PROBLEMS) {
                    if (e.text.startsWith(s)) {
                        p.add(e);
                        break;
                    }
                }
            }
            return p;
        }

        /** Число строк по типам, по убыванию. */
        Map<String, Integer> typeCounts() {
            Map<String, Integer> m = new LinkedHashMap<>();
            for (Entry e : lines) {
                Integer n = m.get(e.type);
                m.put(e.type, n == null ? 1 : n + 1);
            }
            List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
            Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
            Map<String, Integer> sorted = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> e : l) sorted.put(e.getKey(), e.getValue());
            return sorted;
        }

        /**
         * Ход сеанса: питание, отметки, броадкасты, запуски, подключение к машине и сбои.
         * Паузы дольше PAUSE_MS отмечаются строкой с пустой меткой, текстом «pause …».
         */
        List<Entry> timeline() {
            List<Entry> t = new ArrayList<>();
            Entry prev = null;
            for (Entry e : lines) {
                if (prev != null && e.time - prev.time > PAUSE_MS) {
                    t.add(new Entry(-1, "", -1, -1, null, "pause " + LogAnalysis.duration(e.time - prev.time)));
                }
                prev = e;
                if (e.isAnchor() || "CAR".equals(e.type) || "SNAPSHOT".equals(e.type) || e.text.startsWith("PROP ERROR")) {
                    t.add(e);
                }
            }
            return t;
        }
    }

    /** Сеансы записи, новые сверху. */
    List<Session> sessions() {
        List<Session> list = new ArrayList<>();
        Session cur = null;
        for (Entry e : entries) {
            boolean begin = e.text.startsWith("=== recording started");
            if (cur == null || begin || cur.stopped) {
                Session s = new Session();
                if (cur != null) s.gapBefore = Math.max(0, e.time - cur.last().time);
                list.add(s);
                cur = s;
            }
            cur.lines.add(e);
            if (e.text.startsWith("=== recording stopped")) cur.stopped = true;
        }
        Collections.reverse(list);
        return list;
    }

    /** Длительность «ч:мм:сс», для суток и больше — «N d ч:мм:сс». */
    static String duration(long ms) {
        long s = Math.max(0, ms) / 1000;
        long d = s / 86400;
        String hms = String.format(Locale.US, "%d:%02d:%02d", s % 86400 / 3600, s % 3600 / 60, s % 60);
        return d > 0 ? d + " d " + hms : hms;
    }

    /** Отчёт о сеансе для файла. */
    static String format(Session s) {
        StringBuilder sb = new StringBuilder();
        sb.append("EISDiag recording session\n");
        sb.append(String.format(Locale.US, "%-12s", "start")).append(s.first().stamp).append('\n');
        sb.append(String.format(Locale.US, "%-12s", "end")).append(s.last().stamp).append(s.stopped ? "  (stopped)" : "").append('\n');
        sb.append(String.format(Locale.US, "%-12s", "duration")).append(duration(s.duration())).append('\n');
        if (s.gapBefore >= 0) sb.append(String.format(Locale.US, "%-12s", "off before")).append(duration(s.gapBefore)).append('\n');
        sb.append(String.format(Locale.US, "%-12s", "reason")).append(s.reason()).append('\n');
        sb.append(String.format(Locale.US, "%-12s", "last power")).append(s.lastPower().isEmpty() ? "-" : s.lastPower()).append('\n');
        sb.append(String.format(Locale.US, "%-11s", "lines"));
        for (Map.Entry<String, Integer> c : s.typeCounts().entrySet()) sb.append(' ').append(c.getKey()).append(' ').append(c.getValue());
        sb.append('\n');
        sb.append(String.format(Locale.US, "%-12s", "problems")).append(s.problems().size()).append("\n\n");
        for (Entry e : s.timeline()) sb.append(timelineLine(s, e)).append('\n');
        return sb.toString();
    }

    /** Строка хода сеанса: время от начала сеанса, метка и текст; пауза — отдельной строкой. */
    static String timelineLine(Session s, Entry e) {
        if (e.index < 0) return String.format(Locale.US, "%10s  --- %s ---", "", e.text);
        return String.format(Locale.US, "%10s  %s  %s", "+" + duration(e.time - s.first().time), e.stamp, e.text);
    }

    // ---------------------------------------------------------------- Чтение

    /** Читает старый журнал (если есть) и текущий, по порядку. */
    static LogAnalysis load(File old, File current) {
        List<Entry> list = new ArrayList<>();
        long modified = 0;
        for (File f : new File[]{old, current}) {
            if (f == null || !f.isFile()) continue;
            modified = Math.max(modified, f.lastModified());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Entry e = parse(list.size(), line);
                    if (e != null) list.add(e);
                }
            } catch (Exception ignored) {
            }
        }
        resolveTimes(list, modified > 0 ? modified : System.currentTimeMillis(), TimeZone.getDefault());
        return new LogAnalysis(list);
    }

    /** Для проверки без файлов. */
    static LogAnalysis of(List<String> lines, long lastModified, TimeZone tz) {
        List<Entry> list = new ArrayList<>();
        for (String line : lines) {
            Entry e = parse(list.size(), line);
            if (e != null) list.add(e);
        }
        resolveTimes(list, lastModified, tz);
        return new LogAnalysis(list);
    }

    /**
     * Строка журнала: «2026-10-15 13:12:08.123  TEXT» или старая «10-15 13:12:08.123  TEXT».
     * Пустые строки пропускаются, строки без метки остаются с пустой меткой.
     */
    static Entry parse(int index, String line) {
        if (line.trim().isEmpty()) return null;
        if (line.length() > 25 && isStamp(line, true) && line.startsWith("  ", 23)) {
            return new Entry(index, line.substring(0, 23), -1, -1, null, line.substring(25));
        }
        if (line.length() > 20 && isStamp(line, false) && line.startsWith("  ", 18)) {
            int month = Integer.parseInt(line.substring(0, 2));
            int day = Integer.parseInt(line.substring(3, 5));
            return new Entry(index, line.substring(0, 18), month, day, line.substring(6, 18), line.substring(20));
        }
        return new Entry(index, "", -1, -1, null, line);
    }

    /** Шаблон метки: цифры на местах «ГГГГ-ММ-ДД чч:мм:сс.ммм» (или без «ГГГГ-»). */
    private static boolean isStamp(String s, boolean withYear) {
        String p = withYear ? "dddd-dd-dd dd:dd:dd.ddd" : "dd-dd dd:dd:dd.ddd";
        for (int i = 0; i < p.length(); i++) {
            char c = s.charAt(i), want = p.charAt(i);
            if (want == 'd' ? c < '0' || c > '9' : c != want) return false;
        }
        return true;
    }

    /** Время каждой строки в мс; год старых строк восстанавливается с конца журнала. */
    private static void resolveTimes(List<Entry> list, long lastModified, TimeZone tz) {
        Calendar cal = Calendar.getInstance(tz, Locale.US);
        cal.setTimeInMillis(lastModified);
        int year = cal.get(Calendar.YEAR);
        int laterMonth = -1;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if (e.stamp.length() == 23) {
                year = Integer.parseInt(e.stamp.substring(0, 4));
                laterMonth = Integer.parseInt(e.stamp.substring(5, 7));
                e.time = millis(cal, year, laterMonth, Integer.parseInt(e.stamp.substring(8, 10)), e.stamp.substring(11));
            } else if (e.month > 0) {
                // Идём назад во времени: месяц вырос сильно (январь → декабрь) — это прошлый год.
                if (laterMonth > 0 && e.month > laterMonth + 6) year--;
                laterMonth = e.month;
                e.time = millis(cal, year, e.month, e.day, e.clock);
            } else {
                e.time = Long.MIN_VALUE;
            }
        }
        // Строки без метки получают время предыдущей.
        long prev = 0;
        for (Entry e : list) {
            if (e.time == Long.MIN_VALUE) e.time = prev;
            prev = e.time;
        }
    }

    private static long millis(Calendar cal, int year, int month, int day, String clock) {
        cal.clear();
        cal.set(year, month - 1, day,
                Integer.parseInt(clock.substring(0, 2)),
                Integer.parseInt(clock.substring(3, 5)),
                Integer.parseInt(clock.substring(6, 8)));
        cal.set(Calendar.MILLISECOND, Integer.parseInt(clock.substring(9, 12)));
        return cal.getTimeInMillis();
    }
}
