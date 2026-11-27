package io.local.rootward.shizuku;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CareProfiles {
  interface Apply {
    void limits(int lower, int upper, int hot);
  }

  private static final class Profile {
    final String name;
    final int lower, upper, hot;
    final boolean custom;

    Profile(String name, int lower, int upper, int hot, boolean custom) {
      this.name = name;
      this.lower = lower;
      this.upper = upper;
      this.hot = hot;
      this.custom = custom;
    }

    String label() {
      return name + "　" + lower + "–" + upper + "%  ·  " + hot + "°C";
    }
  }

  static void show(Activity a, SharedPreferences current, Apply apply) {
    SharedPreferences store = a.getSharedPreferences("care_profiles", 0);
    List<Profile> list = new ArrayList<>();
    list.add(new Profile("长期接电", 45, 55, 38, false));
    list.add(new Profile("日常", 50, 75, 40, false));
    list.add(new Profile("出行", 65, 90, 40, false));
    List<String> names = new ArrayList<>(store.getAll().keySet());
    Collections.sort(names);
    for (String name : names) {
      String[] parts = store.getString(name, "").split(",");
      if (parts.length == 3)
        try {
          int l = Integer.parseInt(parts[0]),
              u = Integer.parseInt(parts[1]),
              h = Integer.parseInt(parts[2]);
          if (l >= 0 && l < u && u <= 100 && h >= 20 && h <= 65)
            list.add(new Profile(name, l, u, h, true));
        } catch (NumberFormatException ignored) {
        }
    }
    List<String> labels = new ArrayList<>();
    for (Profile p : list) labels.add(p.label());
    new AlertDialog.Builder(a)
        .setTitle("养护方案")
        .setItems(
            labels.toArray(new String[0]),
            (dialog, i) -> {
              Profile p = list.get(i);
              AlertDialog.Builder b =
                  new AlertDialog.Builder(a)
                      .setTitle(p.name)
                      .setMessage(
                          "应用 "
                              + p.lower
                              + "% 恢复、"
                              + p.upper
                              + "% 暂停、"
                              + p.hot
                              + "°C 高温阈值。已开启的提醒、巡航或插座会采用新策略。系统固件阈值须在原生限充页单独修改。")
                      .setPositiveButton("应用方案", (d, w) -> apply.limits(p.lower, p.upper, p.hot))
                      .setNegativeButton("取消", null);
              if (p.custom)
                b.setNeutralButton(
                    "删除方案",
                    (d, w) ->
                        new AlertDialog.Builder(a)
                            .setTitle("删除「" + p.name + "」？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (x, y) -> store.edit().remove(p.name).apply())
                            .show());
              b.show();
            })
        .setNegativeButton("返回", null)
        .setPositiveButton(
            "保存当前阈值为方案",
            (d, w) -> {
              EditText input = new EditText(a);
              input.setSingleLine(true);
              input.setHint("方案名称");
              AlertDialog edit =
                  new AlertDialog.Builder(a)
                      .setTitle("保存当前阈值")
                      .setView(input)
                      .setPositiveButton("保存", null)
                      .setNegativeButton("取消", null)
                      .create();
              edit.show();
              edit.getButton(-1)
                  .setOnClickListener(
                      v -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty() || name.length() > 32) {
                          input.setError("请输入 1–32 字的名称");
                          return;
                        }
                        for (Profile p : list)
                          if (p.name.equals(name)) {
                            input.setError("此名称已存在");
                            return;
                          }
                        String range =
                            current.getInt("low", 50)
                                + ","
                                + current.getInt("high", 75)
                                + ","
                                + current.getInt("heat", 40);
                        store.edit().putString(name, range).apply();
                        edit.dismiss();
                        Toast.makeText(a, "已保存方案", Toast.LENGTH_SHORT).show();
                      });
            })
        .show();
  }

  private CareProfiles() {}
}
