package com.EISDiag;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/** Общие элементы интерфейса, которые создаются из кода: строки списков, пункты накопителей. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    /** Строка списка высотой не меньше 68 dp — удобно нажимать пальцем в машине. */
    static LinearLayout row(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 68));
        row.setPadding(dp(c, 12), dp(c, 4), dp(c, 8), dp(c, 4));
        return row;
    }

    static TextView title(Context c, String text) {
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextSize(19);
        v.setTextColor(c.getColor(R.color.text_primary));
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return v;
    }

    static void divider(LinearLayout parent) {
        Context c = parent.getContext();
        View v = new View(c);
        v.setBackgroundColor(c.getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, 1);
        lp.setMarginStart(dp(c, 16));
        parent.addView(v, lp);
    }

    /** Сообщение на месте пустого списка: крупный заголовок и пояснение. */
    static void emptyState(LinearLayout parent, String headline, String details) {
        Context c = parent.getContext();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 32), dp(c, 48), dp(c, 32), dp(c, 48));
        TextView h = new TextView(c);
        h.setText(headline);
        h.setTextSize(20);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(c.getColor(R.color.text_primary));
        h.setGravity(Gravity.CENTER);
        box.addView(h);
        if (details != null) {
            TextView d = new TextView(c);
            d.setText(details);
            d.setTextSize(17);
            d.setTextColor(c.getColor(R.color.text_secondary));
            d.setGravity(Gravity.CENTER);
            d.setPadding(0, dp(c, 8), 0, 0);
            box.addView(d);
        }
        parent.addView(box, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    /** Пункт боковой колонки (накопитель): значок слева и подпись, выбранный подсвечивается. */
    static TextView railItem(Context c, int iconRes, String label) {
        TextView v = new TextView(c, null, 0, R.style.EISDiag_RailItem);
        v.setText(label);
        Drawable icon = c.getDrawable(iconRes).mutate();
        int size = dp(c, 26);
        icon.setBounds(0, 0, size, size);
        v.setCompoundDrawables(icon, null, null, null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        lp.bottomMargin = dp(c, 6);
        v.setLayoutParams(lp);
        return v;
    }

    static void setSelected(TextView item, boolean selected) {
        Context c = item.getContext();
        int color = c.getColor(selected ? R.color.accent : R.color.text_secondary);
        item.setTextColor(color);
        item.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        if (selected) {
            item.setBackgroundResource(R.drawable.tab_selected);
            item.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            item.setBackgroundResource(selectableBackground(c));
            item.setTypeface(Typeface.DEFAULT);
        }
    }

    static int selectableBackground(Context c) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }
}
