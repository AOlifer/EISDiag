package com.EISDiag;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Файловые операции и форматирование, общие для экрана диагностики и экрана выбора папки. */
final class FileUtils {
    static final File INTERNAL_ROOT = new File("/storage/emulated/0");

    private FileUtils() {}

    static void sortByName(File[] files) {
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
    }

    /** Память устройства и подключённые внешние накопители (USB, SD). */
    static List<File> storageRoots() {
        List<File> roots = new ArrayList<>();
        roots.add(INTERNAL_ROOT);
        File[] external = new File("/storage").listFiles(f -> f.isDirectory()
                && !f.getName().equals("emulated") && !f.getName().equals("self") && f.canRead());
        if (external != null) {
            sortByName(external);
            roots.addAll(Arrays.asList(external));
        }
        return roots;
    }

    static String rootLabel(Context c, File root) {
        return root.equals(INTERNAL_ROOT)
                ? c.getString(R.string.storage_internal)
                : c.getString(R.string.storage_usb, root.getName());
    }

    /** @return накопитель, внутри которого лежит dir, или null. */
    static File findRoot(File dir, List<File> roots) {
        String path = dir.getAbsolutePath();
        for (File root : roots) {
            String rootPath = root.getAbsolutePath();
            if (path.equals(rootPath) || path.startsWith(rootPath + "/")) return root;
        }
        return null;
    }

    /** Копирование через временный файл: при ошибке существующий файл не портится. */
    static boolean copyFileQuiet(File src, File dst) {
        File tmp = new File(dst.getParentFile(), "." + dst.getName() + ".tmp");
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(dst)) return true;
        // Некоторые ФС (FAT на USB) не заменяют файл при rename.
        if (dst.delete() && tmp.renameTo(dst)) return true;
        tmp.delete();
        return false;
    }

    /** Размер файла; единицы и десятичный разделитель — по языку системы (getString форматирует по нему). */
    static String formatSize(Context c, long b) {
        if (b < 1024) return c.getString(R.string.size_b, (int) b);
        if (b < 1048576) return c.getString(R.string.size_kb, b / 1024.0);
        return c.getString(R.string.size_mb, b / 1048576.0);
    }
}
