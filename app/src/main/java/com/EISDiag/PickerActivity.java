package com.EISDiag;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * Выбор папки в памяти устройства и на внешних накопителях — куда сохранить файлы диагностики.
 * Три колонки: накопители слева, подпапки по центру, что будет сохранено и кнопка действия справа.
 */
public class PickerActivity extends BaseActivity {
    static final String EXTRA_TITLE = "title";
    /** Подпись основной кнопки. */
    static final String EXTRA_ACTION = "action";
    /** Имя папки, которая будет сохранена, — показывается в правой колонке. */
    static final String EXTRA_SUBJECT = "subject";
    /** Результат: путь к выбранной папке. */
    static final String EXTRA_FOLDER = "folder";

    private List<File> roots;
    private File root, dir;

    private LinearLayout rootsBar, crumbs, list, sideList;
    private HorizontalScrollView crumbsScroll;
    private TextView status;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_picker);
        prefs = Prefs.get(this);

        Intent in = getIntent();
        ((TextView) findViewById(R.id.pickerTitle)).setText(in.getStringExtra(EXTRA_TITLE));
        rootsBar = findViewById(R.id.rootsBar);
        crumbs = findViewById(R.id.crumbs);
        crumbsScroll = findViewById(R.id.crumbsScroll);
        list = findViewById(R.id.pickerList);
        sideList = findViewById(R.id.sideList);
        status = findViewById(R.id.pickerStatus);
        TextView btnAction = findViewById(R.id.btnAction);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnRefresh).setOnClickListener(v -> refreshRoots());
        btnAction.setOnClickListener(v -> finishWithResult());
        String action = in.getStringExtra(EXTRA_ACTION);
        btnAction.setText(action != null ? action : getString(R.string.picker_choose_folder));
        String subject = in.getStringExtra(EXTRA_SUBJECT);
        if (subject != null) sideList.addView(sideText(subject));

        roots = FileUtils.storageRoots();
        openStartDir();
    }

    /** Системная кнопка «Назад» сначала поднимается по папкам, потом закрывает экран. */
    @Override public void onBackPressed() {
        if (dir != null && !dir.equals(root) && dir.getParentFile() != null) open(dir.getParentFile());
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- Навигация

    /** Открывается последняя выбранная папка, если она ещё доступна, иначе память устройства. */
    private void openStartDir() {
        String last = prefs.getString(Prefs.PICKER_LAST_DIR, null);
        if (last == null || !openIfAvailable(new File(last))) openInternal();
    }

    private void refreshRoots() {
        roots = FileUtils.storageRoots();
        if (dir != null && openIfAvailable(dir)) return;
        if (root != null && !roots.contains(root)) toast(getString(R.string.picker_storage_gone, FileUtils.rootLabel(this, root)));
        openInternal();
    }

    /** Открыть папку, если она есть и лежит на одном из накопителей. */
    private boolean openIfAvailable(File d) {
        File r = FileUtils.findRoot(d, roots);
        if (r == null || !d.isDirectory()) return false;
        root = r;
        open(d);
        return true;
    }

    private void openInternal() {
        root = FileUtils.INTERNAL_ROOT;
        open(root);
    }

    private void open(File d) {
        dir = d;
        prefs.edit().putString(Prefs.PICKER_LAST_DIR, d.getAbsolutePath()).apply();
        buildRootsBar();
        buildCrumbs();
        loadList();
    }

    private void buildRootsBar() {
        rootsBar.removeAllViews();
        for (File r : roots) {
            boolean internal = r.equals(FileUtils.INTERNAL_ROOT);
            TextView item = Ui.railItem(this, internal ? R.drawable.ic_storage_internal : R.drawable.ic_storage_usb,
                    FileUtils.rootLabel(this, r));
            Ui.setSelected(item, r.equals(root));
            item.setOnClickListener(v -> {
                root = r;
                open(r);
            });
            rootsBar.addView(item);
        }
        if (roots.size() == 1) {
            TextView hint = Ui.text(this, getString(R.string.picker_no_usb), 15, R.color.text_secondary);
            hint.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), 0);
            rootsBar.addView(hint);
        }
    }

    private void buildCrumbs() {
        crumbs.removeAllViews();
        List<File> chain = new ArrayList<>();
        for (File f = dir; f != null; f = f.getParentFile()) {
            chain.add(0, f);
            if (f.equals(root)) break;
        }
        for (int i = 0; i < chain.size(); i++) {
            final File f = chain.get(i);
            boolean last = i == chain.size() - 1;
            if (i > 0) crumbs.addView(Ui.text(this, "›", 20, R.color.text_disabled));
            TextView part = Ui.text(this, i == 0 ? FileUtils.rootLabel(this, root) : f.getName(), 18,
                    last ? R.color.text_primary : R.color.accent);
            part.setGravity(Gravity.CENTER_VERTICAL);
            part.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            if (last) {
                part.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                part.setBackgroundResource(Ui.selectableBackground(this));
                part.setOnClickListener(v -> open(f));
            }
            crumbs.addView(part, new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT));
        }
        crumbsScroll.post(() -> crumbsScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void loadList() {
        list.removeAllViews();
        File[] dirs = dir.listFiles(f -> f.isDirectory() && !f.isHidden());
        if (dirs == null) {
            Ui.emptyState(list, getString(R.string.picker_folder_unavailable), dir.getAbsolutePath());
        } else if (dirs.length == 0) {
            Ui.emptyState(list, getString(R.string.picker_no_folders), null);
        } else {
            FileUtils.sortByName(dirs);
            for (File d : dirs) addFolderRow(d);
        }
        status.setText(getString(R.string.picker_save_into, dir.equals(root) ? FileUtils.rootLabel(this, root) : dir.getName()));
    }

    private void addFolderRow(File d) {
        LinearLayout row = Ui.row(this);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_folder);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 32)));
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1);
        nameLp.setMarginStart(Ui.dp(this, 8));
        row.addView(Ui.title(this, d.getName()), nameLp);
        TextView chevron = Ui.text(this, "›", 28, R.color.text_disabled);
        chevron.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        row.addView(chevron);
        row.setBackgroundResource(Ui.selectableBackground(this));
        row.setOnClickListener(v -> open(d));
        list.addView(row, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        Ui.divider(list);
    }

    private TextView sideText(String text) {
        TextView v = Ui.text(this, text, 18, R.color.text_primary);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        return v;
    }

    private void finishWithResult() {
        if (!FileUtils.canWrite(dir)) { toast(getString(R.string.picker_not_writable)); return; }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_FOLDER, dir.getAbsolutePath()));
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
