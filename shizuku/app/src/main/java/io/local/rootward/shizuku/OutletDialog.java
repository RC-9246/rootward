package io.local.rootward.shizuku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;

final class OutletDialog {
  static void show(Activity a, Runnable refresh) {
    if (a.isFinishing() || a.isDestroyed()) return;
    SharedPreferences p = SmartOutlet.prefs(a);
    boolean active = p.getBoolean("active", false);
    boolean configured = p.contains("secret");
    String entity = p.getString("entity", "");
    long checked = p.getLong("checked", 0);
    String message = configured ? p.getString("origin", "") + " · " + entity + "\n" : "";
    if (checked > 0)
      message +=
          DateFormat.getTimeInstance().format(new Date(checked))
              + "　"
              + p.getString("status", "")
              + "\n";
    message +=
        "只选择给这台手机充电的专用插座。归根按电量和温控请求供电，并核对 Home Assistant 上报的开关状态。手机离线或关机时若需保底通电，请在插座或家庭控制器设置超时。";
    final String description = message;
    if (OutletNetwork.missing(a))
      message += "\n局域网服务器需要允许「附近的设备」。公网 HTTPS 地址可直接使用。";
    AlertDialog.Builder overview = new AlertDialog.Builder(a)
        .setTitle("Home Assistant 充电插座" + (active ? " · 已开启" : ""))
        .setMessage(message)
        .setNegativeButton("关闭", null)
        .setPositiveButton(
            configured ? "操作" : "配置连接",
            (d, w) -> {
              if (!configured) {
                configure(a, refresh);
                return;
              }
              String[] options = {
                active ? "停用联动并尝试恢复供电" : "按养护策略联动", "只读检查插座", "手动恢复供电", "更换连接", "移除本机配置"
              };
              new AlertDialog.Builder(a)
                  .setTitle(entity)
                  .setItems(
                      options,
                      (choose, index) -> {
                        SmartOutlet.Done done =
                            result -> {
                              if (a.isFinishing() || a.isDestroyed()) return;
                              Toast.makeText(a, result, Toast.LENGTH_LONG).show();
                              show(a, refresh);
                            };
                        switch (index) {
                          case 0:
                            if (active) {
                              SmartOutlet.enable(a, false);
                              refresh.run();
                              show(a, refresh);
                            } else if (!ObserverApp.prefs(a).getBoolean("watch", false))
                              Toast.makeText(a, "先开启记录与提醒，再启用插座联动", Toast.LENGTH_LONG).show();
                            else
                              new AlertDialog.Builder(a)
                                  .setTitle("启用 " + entity + "？")
                                  .setMessage("达到上限或过热时会切断该插座。仅当归根已确认由自己断电，才会在允许充电时自动恢复。")
                                  .setNegativeButton("取消", null)
                                  .setPositiveButton(
                                      "启用",
                                      (x, y) -> {
                                        SmartOutlet.enable(a, true);
                                        refresh.run();
                                        show(a, refresh);
                                      })
                                  .show();
                            break;
                          case 1:
                            SmartOutlet.check(a, false, done);
                            break;
                          case 2:
                            new AlertDialog.Builder(a)
                                .setTitle("手动为 " + entity + " 通电？")
                                .setMessage("这会开启该插座，包括原本由其他人关闭的插座。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton(
                                    "恢复供电", (x, y) -> SmartOutlet.check(a, true, done))
                                .show();
                            break;
                          case 3:
                            configure(a, refresh);
                            break;
                          case 4:
                            new AlertDialog.Builder(a)
                                .setTitle("移除本机配置？")
                                .setMessage("请先确认插座状态。移除配置不会切换插座，之后可在 Home Assistant 撤销令牌。")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("移除", (x, y) -> SmartOutlet.forget(a, done))
                                .show();
                            break;
                        }
                      })
                  .setNegativeButton("返回", (x, y) -> show(a, refresh))
                  .show();
            });
    if (OutletNetwork.missing(a))
      overview.setNeutralButton("局域网权限", null);
    AlertDialog dialog = overview.show();
    OutletNetwork.bindPermissionButton(a, dialog,
        () -> dialog.setMessage(description + "\n已允许访问局域网。"));
  }

  private static void configure(Activity a, Runnable refresh) {
    SharedPreferences p = SmartOutlet.prefs(a);
    LinearLayout box = new LinearLayout(a);
    box.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
    box.setPadding(pad, 0, pad, 0);
    EditText host = field(a, box, "HTTPS 服务器", p.getString("origin", ""));
    EditText id = field(a, box, "充电插座实体 ID", p.getString("entity", ""));
    id.setHint("switch.phone_charger");
    EditText token = field(a, box, "长期访问令牌", "");
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    TextView status = new TextView(a);
    status.setText("建议使用单独的 Home Assistant 用户和令牌。连接验证只读取这个开关。");
    box.addView(status);
    AlertDialog.Builder setup =
        new AlertDialog.Builder(a)
            .setTitle("连接 Home Assistant")
            .setView(DisplayLayout.scrollForm(box))
            .setPositiveButton("验证并保存", null)
            .setNegativeButton("取消", null);
    if (OutletNetwork.missing(a)) {
      setup.setNeutralButton("局域网权限", null);
      status.append("\n连接家中服务器前请允许「附近的设备」。公网 HTTPS 地址可直接验证。");
    }
    AlertDialog dlg = setup.show();
    OutletNetwork.bindPermissionButton(a, dlg,
        () -> status.setText("已允许访问局域网，可验证连接。"));
    dlg.getButton(-1)
        .setOnClickListener(
            v -> {
              dlg.getButton(-1).setEnabled(false);
              status.setText("正在验证连接…");
              SmartOutlet.setup(
                  a,
                  host.getText().toString(),
                  id.getText().toString(),
                  token.getText().toString(),
                  result -> {
                    if (!dlg.isShowing() || a.isDestroyed()) return;
                    dlg.getButton(-1).setEnabled(true);
                    status.setText(result);
                    if (result.startsWith("连接验证成功")) {
                      token.setText("");
                      dlg.dismiss();
                      show(a, refresh);
                    }
                  });
            });
  }

  private static EditText field(Activity a, LinearLayout box, String label, String initial) {
    TextView t = new TextView(a);
    t.setText(label);
    box.addView(t);
    EditText e = new EditText(a);
    e.setSingleLine(true);
    e.setText(initial);
    box.addView(e);
    return e;
  }

  private OutletDialog() {}
}
