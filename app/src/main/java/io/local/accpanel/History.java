package io.local.accpanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-memory foreground samples. No service or storage permissions are needed. */
public final class History {
    private static final long MINUTE = 60_000;
    private static final long WINDOW = 30 * MINUTE;
    private static final long MAX_GAP = 90_000;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private WeakReference<Panel> openPanel = new WeakReference<>(null);

    private static final class Sample {
        final long time;
        final float[] values;

        Sample(long time, int percent, float temperature, float current) {
            this.time = time;
            values = new float[] {
                percent >= 0 && percent <= 100 ? percent : Float.NaN, temperature, current
            };
        }
    }

    public synchronized void add(long timeMillis, int percent, float celsius, float currentMa) {
        Sample last = samples.peekLast();
        // A wall-clock correction starts a new timeline. Repeated readings in one second coalesce.
        if (last != null && timeMillis < last.time) samples.clear();
        else if (last != null && last.time / 1000 == timeMillis / 1000) samples.removeLast();
        prune(timeMillis);
        samples.addLast(new Sample(timeMillis, percent, celsius, currentMa));
        Panel panel = openPanel.get();
        if (panel != null) panel.post(panel::refresh);
    }

    private void prune(long now) {
        while (!samples.isEmpty() && samples.peekFirst().time < now - WINDOW) {
            samples.removeFirst();
        }
    }

    private synchronized List<Sample> recent(long now) {
        prune(now);
        return new ArrayList<>(samples);
    }

    public void show(Activity activity) {
        Panel panel = new Panel(activity);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("电池趋势").setView(scroll).setPositiveButton("关闭", null).create();
        dialog.setOnDismissListener(d -> {
            synchronized (History.this) {
                if (openPanel.get() == panel) openPanel.clear();
            }
        });
        synchronized (this) { openPanel = new WeakReference<>(panel); }
        panel.refresh();
        dialog.show();
    }

    private static int dp(Context context, float n) {
        return Math.round(context.getResources().getDisplayMetrics().density * n);
    }

    private static final String[] NAMES = {"电量", "电池温度", "电池电流"};
    private static final String[] UNITS = {"%", "°C", "mA"};

    private static String number(float value, int metric) {
        if (metric == 1) return String.format(Locale.ROOT, "%.1f", value);
        return String.valueOf(Math.round(value));
    }

    private final class Panel extends LinearLayout {
        final TextView[] readings = new TextView[3];
        final Plot[] plots = new Plot[3];

        Panel(Activity context) {
            super(context);
            setOrientation(VERTICAL);
            int side = dp(context, 18);
            setPadding(side, dp(context, 12), side, dp(context, 4));
            boolean dark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int text = dark ? 0xffedf5ed : 0xff192b23;
            int subtle = dark ? 0xffb4c7bb : 0xff51645a;
            int grid = dark ? 0xff39453d : 0xffdee7df;
            int[] accents = dark ? new int[] {0xffb7eac2, 0xffffbc85, 0xff9bd4eb}
                    : new int[] {0xff255f44, 0xff925017, 0xff236581};
            setBackgroundColor(dark ? 0xff1e2723 : 0xffffffff);
            TextView note = new TextView(context);
            note.setText("最近 30 分钟的前台读数，采样中断处留空。");
            note.setTextSize(13);
            note.setTextColor(subtle);
            addView(note);
            for (int i = 0; i < plots.length; i++) {
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(context, 12), 0, 0);
                TextView name = new TextView(context);
                name.setText(NAMES[i] + " (" + UNITS[i] + ")");
                name.setTextSize(15);
                name.setTextColor(text);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
                readings[i] = new TextView(context);
                readings[i].setTextColor(accents[i]);
                readings[i].setTextSize(14);
                row.addView(readings[i]);
                addView(row);
                plots[i] = new Plot(context, i, subtle, grid, accents[i]);
                addView(plots[i], new LayoutParams(LayoutParams.MATCH_PARENT, dp(context, 92)));
            }
        }

        void refresh() {
            long end = System.currentTimeMillis();
            List<Sample> rows = recent(end);
            long span = rows.isEmpty() ? MINUTE : end - rows.get(0).time;
            // Use the same time domain for all three plots, even when a sensor is missing.
            span = Math.min(WINDOW, Math.max(MINUTE, ((span + MINUTE - 1) / MINUTE) * MINUTE));
            for (int i = 0; i < plots.length; i++) {
                float value = rows.isEmpty() ? Float.NaN : rows.get(rows.size() - 1).values[i];
                readings[i].setText(Float.isFinite(value) ? number(value, i) + " " + UNITS[i] : "—");
                plots[i].update(rows, end - span, end);
            }
        }
    }

    private static final class Plot extends View {
        final int metric, text, grid, stroke;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
        List<Sample> rows = new ArrayList<>();
        long start, end;
        float low, high;
        int count;

        Plot(Context context, int metric, int text, int grid, int stroke) {
            super(context);
            this.metric = metric;
            this.text = text;
            this.grid = grid;
            this.stroke = stroke;
            paint.setTextSize(dp(context, 10));
        }

        void update(List<Sample> rows, long start, long end) {
            this.rows = rows;
            this.start = start;
            this.end = end;
            count = 0;
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (Sample row : rows) {
                float value = row.values[metric];
                if (!Float.isFinite(value)) continue;
                count++;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            if (count > 0) {
                float margin = Math.max(metric == 2 ? 10 : 1, (max - min) * 0.12f);
                low = (float) Math.floor(min - margin);
                high = (float) Math.ceil(max + margin);
                if (metric == 0) {
                    low = Math.max(0, low);
                    high = Math.min(100, high);
                } else if (metric == 2) {
                    low = Math.min(0, low);
                    high = Math.max(0, high);
                }
            }
            setContentDescription(count == 0 ? NAMES[metric] + "暂无数据"
                    : NAMES[metric] + "，" + count + " 个读数，最低 " + number(min, metric)
                    + "，最高 " + number(max, metric) + " " + UNITS[metric]);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Context ctx = getContext();
            float labelHeight = paint.getTextSize();
            float left = count == 0 ? 0 : dp(ctx, 8) + Math.max(
                    paint.measureText(number(high, metric)), paint.measureText(number(low, metric)));
            float right = getWidth() - dp(ctx, 5);
            float top = dp(ctx, 11), bottom = getHeight() - dp(ctx, 20);
            if (bottom <= top || right <= left) return;
            paint.setStrokeWidth(dp(ctx, 1));
            for (int tick = 0; tick <= 2; tick++) {
                float y = top + (bottom - top) * tick / 2f;
                paint.setColor(grid);
                canvas.drawLine(left, y, right, y, paint);
                if (count > 0 && tick != 1) {
                    paint.setColor(text);
                    canvas.drawText(number(tick == 0 ? high : low, metric), 0, y + labelHeight / 3, paint);
                }
            }

            paint.setColor(text);
            canvas.drawText(clock.format(new Date(start)), left, getHeight() - dp(ctx, 3), paint);
            String endText = clock.format(new Date(end));
            canvas.drawText(endText, right - paint.measureText(endText), getHeight() - dp(ctx, 3), paint);
            if (count == 0) {
                String empty = rows.isEmpty() ? "等待采样" : "暂无此项读数";
                canvas.drawText(empty, (left + right - paint.measureText(empty)) / 2,
                        (top + bottom - labelHeight) / 2, paint);
                return;
            }

            float prevX = 0, prevY = 0;
            long prevTime = Long.MIN_VALUE;
            paint.setColor(stroke);
            paint.setStrokeWidth(dp(ctx, 2));
            for (Sample row : rows) {
                float value = row.values[metric];
                if (!Float.isFinite(value)) {
                    prevTime = Long.MIN_VALUE;
                    continue;
                }
                float x = left + (right - left) * (row.time - start) / (end - start);
                float y = bottom - (bottom - top) * (value - low) / (high - low);
                if (prevTime != Long.MIN_VALUE && row.time - prevTime <= MAX_GAP) {
                    canvas.drawLine(prevX, prevY, x, y, paint);
                }
                canvas.drawCircle(x, y, dp(ctx, 1.7f), paint);
                prevTime = row.time;
                prevX = x;
                prevY = y;
            }
        }
    }
}
