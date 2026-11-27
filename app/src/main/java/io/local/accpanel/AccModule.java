package io.local.accpanel;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Offline distribution of the original flashable ZIP. The root manager handles installation. */
final class AccModule {
    static final int CREATE_ZIP = 1401;
    private static final String NAME = "acc_v2023.10.16_202310160.zip";
    private static final String DIGEST = "fe071f77a6300f1c14aee1ffc8e22452e448635a7a40e6d97480f3b44fb8c795";
    private static final String SOURCE = "https://github.com/VR-25/acc/tree/v2023.10.16";

    private AccModule() {}

    static void show(Activity activity, String installedVersion) {
        String status = installedVersion == null ? "如设备尚未安装 ACC，可按以下步骤安装。"
                : "已检测到设备上的 ACC " + installedVersion + "。可以直接使用现有模块和配置。";
        String text = status + "\n\nMagisk 初次安装\n"
                + "1. 导出模块 ZIP 并选择保存位置。\n"
                + "2. 打开 Magisk → 模块 → 从本地安装，选择刚保存的 ZIP。\n"
                + "3. 按管理器提示完成安装，回到归根点「刷新」并授权 root。\n\n"
                + "内置版本 v2023.10.16（202310160）\n"
                + "Copyright 2017–2023, VR25 · GPL-3.0-or-later\n"
                + "许可证、可读源脚本与构建脚本均随 ZIP 分发。";
        new CareDialog(activity).setTitle("内置 ACC 模块")
                .setMessage(text).setPositiveButton("导出模块 ZIP", (d, w) -> {
                    Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/zip").putExtra(Intent.EXTRA_TITLE, NAME);
                    try {
                        activity.startActivityForResult(create, CREATE_ZIP);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(activity, "系统文件选择器不可用。", Toast.LENGTH_LONG).show();
                    }
                }).setNeutralButton("上游源码", (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)));
                    } catch (ActivityNotFoundException e) {
                        new CareDialog(activity).setMessage(SOURCE).setPositiveButton("完成", null).show();
                    }
                }).setNegativeButton("关闭", null).show();
    }

    static void export(Context app, Uri destination) {
        new Thread(() -> {
            String message;
            try (InputStream input = app.getResources().openRawResource(R.raw.acc_module)) {
                ByteArrayOutputStream data = new ByteArrayOutputStream(210000);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) data.write(buffer, 0, count);
                byte[] module = data.toByteArray();
                if (!DIGEST.equals(sha256(module))) throw new IOException("内置模块的 SHA-256 校验失败");
                try (OutputStream out = app.getContentResolver().openOutputStream(destination, "wt")) {
                    if (out == null) throw new IOException("文件提供方无法写入");
                    out.write(module);
                }
                message = "已导出 ACC 模块。可在 Magisk 的「模块」中选择此 ZIP。";
            } catch (IOException | SecurityException | NoSuchAlgorithmException e) {
                message = "导出未完成，可重新选择位置。 " + e.getMessage();
            }
            final String result = message;
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(app, result, Toast.LENGTH_LONG).show());
        }, "acc-module-export").start();
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        StringBuilder hexadecimal = new StringBuilder(64);
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hexadecimal.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hexadecimal.append(Character.forDigit(value & 0xf, 16));
        }
        return hexadecimal.toString();
    }
}
