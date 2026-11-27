package io.local.rootward.shizuku;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

/** A single named Home Assistant switch. Never follows redirects with the credential. */
final class HaSwitch {
  final String origin, entity;
  private final String token;

  HaSwitch(String origin, String entity, String token) {
    URI uri;
    try {
      uri = URI.create(origin.trim());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("请填写正确的 HTTPS 地址");
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || uri.getPort() == 0
        || uri.getPort() > 65535
        || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
      throw new IllegalArgumentException("地址应为 HTTPS 服务器和可选端口，不含路径");
    this.origin = "https://" + uri.getRawAuthority();
    this.entity = entity.trim();
    if (!this.entity.matches("switch\\.[a-z0-9_]{1,180}") || this.entity.equals("switch.all"))
      throw new IllegalArgumentException("请填写单个 switch 实体 ID");
    if (!token.matches("[A-Za-z0-9._~+/=-]{1,8192}"))
      throw new IllegalArgumentException("访问令牌为空或格式有误");
    this.token = token;
  }

  static final class State {
    final String mode, changed;

    State(String mode, String changed) {
      this.mode = mode;
      this.changed = changed;
    }

    boolean known() {
      return !changed.isEmpty() && (mode.equals("on") || mode.equals("off"));
    }
  }

  State read() throws IOException {
    try {
      JSONObject state = new JSONObject(call("/api/states/" + entity, null));
      if (!entity.equals(state.optString("entity_id"))) throw new IOException("实体回读不匹配");
      return new State(
          state.getString("state"),
          state.isNull("last_changed") ? "" : state.getString("last_changed"));
    } catch (JSONException e) {
      throw new IOException("服务未返回有效的实体状态");
    }
  }

  void set(boolean on) throws IOException {
    call("/api/services/switch/turn_" + (on ? "on" : "off"), "{\"entity_id\":\"" + entity + "\"}");
  }

  private String call(String path, String json) throws IOException {
    HttpsURLConnection h = (HttpsURLConnection) new URL(origin + path).openConnection();
    try {
      h.setInstanceFollowRedirects(false);
      h.setUseCaches(false);
      h.setConnectTimeout(4000);
      h.setReadTimeout(4000);
      h.setRequestProperty("Authorization", "Bearer " + token);
      h.setRequestProperty("Accept", "application/json");
      if (json != null) {
        h.setRequestMethod("POST");
        h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        h.setDoOutput(true);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        h.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = h.getOutputStream()) {
          out.write(bytes);
        }
      }
      int code = h.getResponseCode();
      if (code != 200 && code != 201) throw new IOException("Home Assistant 返回 HTTP " + code);
      if (json != null) return "";
      try (InputStream in = h.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          if (out.size() + n > 131072) throw new IOException("实体状态响应过长");
          out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
      }
    } finally {
      h.disconnect();
    }
  }
}
