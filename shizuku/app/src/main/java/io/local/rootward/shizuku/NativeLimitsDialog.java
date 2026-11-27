package io.local.rootward.shizuku;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class NativeLimitsDialog {
  static void open(Activity activity, ShellLink shell) {
    request(activity, shell, new Bundle(), NativeLimits.READ, 0, false);
  }

  private static void request(
      Activity a, ShellLink shell, Bundle before, int side, int value, boolean undo) {
    shell.nativeLimit(
        before,
        side,
        value,
        undo,
        state -> {
          if (a.isFinishing() || a.isDestroyed()) return;
          if (state == null) {
            new AlertDialog.Builder(a)
                .setTitle("系统原生限充")
                .setMessage(
                    side == NativeLimits.READ
                        ? "请先在 Shizuku 驱动诊断中授权并读取驱动，然后再查看系统阈值。"
                        : "本次设置未获确认。请连接 Shizuku、刷新驱动后核对实际阈值。")
                .setPositiveButton("检查 Shizuku", (d, w) -> shell.act())
                .setNegativeButton("关闭", null)
                .show();
            return;
          }
          String id = state.getString("name", ""), note = state.getString("note", "");
          String msg =
              id.isEmpty()
                  ? ""
                  : id
                      + "\n起充下限  "
                      + display(state.getInt("start", -1))
                      + "\n停充上限  "
                      + display(state.getInt("end", -1));
          if (!note.isEmpty()) msg += (msg.isEmpty() ? "" : "\n\n") + note;
          int writable = state.getInt("writable", 0);
          if (!id.isEmpty()) {
            msg += "\n\n驱动会回报已设置的数值。部分机型仅在特定系统充电模式下执行限充，实际边界、插拔和重启行为取决于机型。";
            if (ObserverApp.prefs(a).getBoolean("cruise", false)) {
              writable = 0;
              msg += " 关闭前台硬件巡航后可修改可写的端点。";
            } else if (writable == 0) msg += " 当前没有可修改的端点。";
          }
          String undoDetail = state.getString("undoDetail", "");
          if (!undoDetail.isEmpty()) msg += "\n\n" + undoDetail;
          AlertDialog.Builder b =
              new AlertDialog.Builder(a)
                  .setTitle("系统原生限充")
                  .setMessage(msg)
                  .setNegativeButton("关闭", null);
          if ((writable & NativeLimits.START) != 0)
            b.setNeutralButton("改下限", (d, w) -> edit(a, shell, state, NativeLimits.START));
          if ((writable & NativeLimits.END) != 0)
            b.setPositiveButton("改上限", (d, w) -> edit(a, shell, state, NativeLimits.END));
          int undoSide = state.getInt("undoSide", 0);
          if ((writable & undoSide) != 0) {
            android.widget.Button restore = new android.widget.Button(a);
            restore.setText("撤销上一次修改");
            LinearLayout body = details(a, msg);
            body.addView(restore);
            b.setMessage(null).setView(DisplayLayout.scrollForm(body));
            AlertDialog info = b.show();
            restore.setOnClickListener(
                v -> {
                  info.dismiss();
                  int old = state.getInt("undoValue");
                  new AlertDialog.Builder(a)
                      .setTitle("撤销上一次修改？")
                      .setMessage(
                          "将"
                              + (undoSide == NativeLimits.START ? "起充下限" : "停充上限")
                              + "恢复为 "
                              + old
                              + "%，随后回读上下限。")
                      .setNegativeButton("取消", null)
                      .setPositiveButton(
                          "撤销并回读", (d, w) -> request(a, shell, state, undoSide, old, true))
                      .show();
                });
          } else b.show();
        });
  }

  private static LinearLayout details(Activity activity, String message) {
    LinearLayout body = new LinearLayout(activity);
    body.setOrientation(LinearLayout.VERTICAL);
    int pad = Math.round(22 * activity.getResources().getDisplayMetrics().density);
    body.setPadding(pad, 0, pad, 0);
    TextView description = new TextView(activity);
    description.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
    description.setText(message);
    body.addView(description);
    return body;
  }

  private static String display(int number) {
    if (number == NativeLimits.MISSING) return "未提供";
    if (number < 0) return "无法读取";
    return number + "%";
  }

  private static void edit(Activity a, ShellLink shell, Bundle state, int side) {
    boolean start = side == NativeLimits.START;
    int old = state.getInt(start ? "start" : "end", -1);
    int other = state.getInt(start ? "end" : "start", -1);
    int min = start || other < 0 ? 0 : other + 1;
    int max = !start || other < 0 ? 100 : other - 1;
    String endpoint = start ? "起充下限" : "停充上限";
    EditText input = new EditText(a);
    input.setInputType(InputType.TYPE_CLASS_NUMBER);
    input.setSingleLine(true);
    input.setText(Integer.toString(old));
    input.selectAll();
    LinearLayout body = details(a, "当前值 " + old + "%。输入 " + min + "–" + max + "%，驱动可能取整。");
    body.addView(input, new LinearLayout.LayoutParams(-1, -2));
    AlertDialog dialog =
        new AlertDialog.Builder(a)
            .setTitle("更改系统" + endpoint)
            .setView(DisplayLayout.scrollForm(body))
            .setPositiveButton("写入并回读", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.show();
    dialog
        .getButton(-1)
        .setOnClickListener(
            v -> {
              try {
                int n = Integer.parseInt(input.getText().toString());
                if (n < min || n > max) throw new NumberFormatException();
                dialog.dismiss();
                request(a, shell, state, side, n, false);
              } catch (NumberFormatException ex) {
                input.setError("请输入范围内的整数百分比");
              }
            });
  }
}
