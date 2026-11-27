package io.local.accpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Keep every action reachable when a system dialog's button bar would exceed the window. */
final class CareDialog {
    private final Activity activity;
    private final AlertDialog.Builder builder;
    private final LinearLayout content;
    private AlertDialog dialog;

    static boolean needsScroll(Activity activity) {
        Configuration c = activity.getResources().getConfiguration();
        return c.screenHeightDp < 460 || c.screenWidthDp < 340 || c.fontScale >= 1.5f;
    }

    CareDialog(Activity activity) {
        this.activity = activity;
        builder = new AlertDialog.Builder(activity);
        if (needsScroll(activity)) {
            ScrollView scroll = new ScrollView(activity);
            content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(16 * activity.getResources().getDisplayMetrics().density);
            content.setPadding(pad, pad, pad, pad);
            scroll.addView(content);
            builder.setView(scroll);
        } else content = null;
    }

    CareDialog setTitle(String title) {
        if (content == null) builder.setTitle(title);
        else {
            TextView heading = new TextView(activity);
            heading.setText(title);
            heading.setTextSize(20);
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
            content.addView(heading);
        }
        return this;
    }

    CareDialog setMessage(String text) {
        if (content == null) builder.setMessage(text);
        else {
            TextView message = new TextView(activity);
            message.setText(text);
            message.setTextSize(16);
            int pad = Math.round(10 * activity.getResources().getDisplayMetrics().density);
            message.setPadding(0, pad, 0, pad);
            content.addView(message);
        }
        return this;
    }

    CareDialog setBody(View view) {
        if (content == null) {
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(view);
            builder.setView(scroll);
        } else content.addView(view);
        return this;
    }

    private void addAction(String label, int which, DialogInterface.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onClick(dialog, which);
        });
        content.addView(button);
    }

    CareDialog setItems(String[] labels, DialogInterface.OnClickListener listener) {
        if (content == null) builder.setItems(labels, listener);
        else for (int i = 0; i < labels.length; i++) addAction(labels[i], i, listener);
        return this;
    }

    CareDialog setPositiveButton(String label, DialogInterface.OnClickListener action) {
        if (content == null) builder.setPositiveButton(label, action);
        else addAction(label, DialogInterface.BUTTON_POSITIVE, action);
        return this;
    }

    CareDialog setNegativeButton(String label, DialogInterface.OnClickListener action) {
        if (content == null) builder.setNegativeButton(label, action);
        else addAction(label, DialogInterface.BUTTON_NEGATIVE, action);
        return this;
    }

    CareDialog setNeutralButton(String label, DialogInterface.OnClickListener action) {
        if (content == null) builder.setNeutralButton(label, action);
        else addAction(label, DialogInterface.BUTTON_NEUTRAL, action);
        return this;
    }

    AlertDialog create() { return dialog = builder.create(); }
    void show() { create().show(); }
}
