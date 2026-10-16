package com.EISDiag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сравнение двух снимков машины (snapshot-….txt, {@link CarDiag#snapshot}): что изменилось,
 * появилось и пропало. Без зависимостей от Android, чтобы проверять отдельно.
 *
 * Снимок разбирается на пары «раздел, ключ → значение»: шапка (сборка, ключевые свойства
 * системы), «== System properties», «== Files», «== Settings.*», «== Car service» и
 * «== Properties», где ключ — свойство с зоной («0x… area 0x0»), а строка конфигурации
 * свойства — ключ «0x… config».
 */
final class SnapshotDiff {
    static final String HEADER = "header";

    /** Одно отличие: before == null — появилось, after == null — пропало. */
    static final class Change {
        final String section, key, before, after;

        Change(String section, String key, String before, String after) {
            this.section = section;
            this.key = key;
            this.before = before;
            this.after = after;
        }

        char mark() {
            return before == null ? '+' : after == null ? '-' : '~';
        }
    }

    static final class Result {
        final List<Change> changes = new ArrayList<>();
        int changed, added, removed;
    }

    private SnapshotDiff() {}

    /** Ключи снимка по разделам, в порядке файла. Ключ карты — «раздел\tключ». */
    static Map<String, String> parse(File f) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        } catch (Exception ignored) {
        }
        return parse(lines);
    }

    static Map<String, String> parse(List<String> lines) {
        Map<String, String> m = new LinkedHashMap<>();
        String section = HEADER;
        String prop = null;
        boolean media = false;
        for (String raw : lines) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("EISDiag car snapshot")) continue;
            if (line.startsWith("== ")) {
                section = line.substring(3);
                // «== Properties: 812» — число свойств идёт в значение, чтобы раздел совпадал.
                int colon = section.indexOf(':');
                if (colon >= 0) {
                    m.put(section.substring(0, colon) + "\tcount", section.substring(colon + 1).trim());
                    section = section.substring(0, colon);
                }
                prop = null;
                media = false;
                continue;
            }
            if (section.equals("Properties")) {
                if (line.startsWith("    area ") && prop != null) {
                    int eq = line.indexOf(" = ");
                    String area = eq < 0 ? line.trim() : line.substring(0, eq).trim();
                    m.put(section + "\t" + prop + " " + area, eq < 0 ? "" : line.substring(eq + 3));
                } else {
                    int bar = line.indexOf(" | ");
                    prop = bar < 0 ? line.trim() : line.substring(0, bar).trim();
                    m.put(section + "\t" + prop + " config", bar < 0 ? "" : line.substring(bar + 3));
                }
                continue;
            }
            if (section.equals("Files")) {
                if (line.endsWith(":")) {
                    media = true;
                    continue;
                }
                if (media && line.startsWith("  ")) {
                    String t = line.trim();
                    int sp = t.indexOf("  ");
                    m.put(section + "\t/system/media/" + (sp < 0 ? t : t.substring(0, sp)), sp < 0 ? "" : t.substring(sp + 2));
                    continue;
                }
                int sp = line.indexOf("  ");
                m.put(section + "\t" + (sp < 0 ? line : line.substring(0, sp)), sp < 0 ? "" : line.substring(sp + 2).trim());
                continue;
            }
            int eq = line.indexOf(" = ");
            int colon = section.equals(HEADER) && eq < 0 ? line.indexOf(": ") : -1;
            if (eq >= 0) m.put(section + "\t" + line.substring(0, eq), line.substring(eq + 3));
            else if (colon >= 0) m.put(section + "\t" + line.substring(0, colon), line.substring(colon + 2));
            else m.put(section + "\t" + line, "");
        }
        return m;
    }

    /** Отличия a → b: сначала по порядку ключей a (изменилось, пропало), затем новые ключи b. */
    static Result diff(Map<String, String> a, Map<String, String> b) {
        Result r = new Result();
        for (Map.Entry<String, String> e : a.entrySet()) {
            String after = b.get(e.getKey());
            if (after == null) {
                r.changes.add(change(e.getKey(), e.getValue(), null));
                r.removed++;
            } else if (!after.equals(e.getValue())) {
                r.changes.add(change(e.getKey(), e.getValue(), after));
                r.changed++;
            }
        }
        for (Map.Entry<String, String> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                r.changes.add(change(e.getKey(), null, e.getValue()));
                r.added++;
            }
        }
        // Внутри раздела порядок сохраняется, но разделы идут как в файле: собираем по разделам.
        List<String> order = new ArrayList<>();
        for (String k : a.keySet()) addSection(order, k);
        for (String k : b.keySet()) addSection(order, k);
        List<Change> sorted = new ArrayList<>();
        for (String s : order) for (Change c : r.changes) if (c.section.equals(s)) sorted.add(c);
        r.changes.clear();
        r.changes.addAll(sorted);
        return r;
    }

    private static void addSection(List<String> order, String key) {
        String s = key.substring(0, key.indexOf('\t'));
        if (!order.contains(s)) order.add(s);
    }

    private static Change change(String key, String before, String after) {
        int tab = key.indexOf('\t');
        return new Change(key.substring(0, tab), key.substring(tab + 1), before, after);
    }

    /** Строка отличия: «~ ключ: было → стало», «+ ключ = значение», «- ключ = значение». */
    static String line(Change c) {
        if (c.before == null) return "+ " + c.key + " = " + c.after;
        if (c.after == null) return "- " + c.key + " = " + c.before;
        return "~ " + c.key + ": " + c.before + "  →  " + c.after;
    }

    /** Отчёт для файла. */
    static String format(Result r, String nameA, String nameB) {
        StringBuilder sb = new StringBuilder();
        sb.append("EISDiag snapshot diff\n");
        sb.append("before ").append(nameA).append("\nafter  ").append(nameB).append('\n');
        sb.append("changed ").append(r.changed).append(", added ").append(r.added)
                .append(", removed ").append(r.removed).append("\n");
        String section = null;
        for (Change c : r.changes) {
            if (!c.section.equals(section)) {
                section = c.section;
                sb.append("\n== ").append(section).append('\n');
            }
            sb.append(line(c)).append('\n');
        }
        return sb.toString();
    }
}
