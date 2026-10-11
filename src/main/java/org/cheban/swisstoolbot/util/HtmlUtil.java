package org.cheban.swisstoolbot.util;

import lombok.experimental.UtilityClass;

import java.net.URI;

@UtilityClass
public class HtmlUtil {
  private static final String LINK_HTML = "<a href=\"%s\">%s</a>";

  public static String buildLink(String url, String text) {
    return String.format(LINK_HTML, url, text);
  }

  public static String fileName(String src, String fallback) {
    try {
      String path = URI.create(src).getPath();          // drops query and fragment
      if (path == null) return fallback;
      String name = path.substring(path.lastIndexOf('/') + 1);   // getPath() is already decoded
      return name.isBlank() ? fallback : name;
    } catch (Exception e) {
      return fallback;
    }
  }

  public static String uploadName(String src, String ext, int index) {
    String name = fileName(src, "img" + index);
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name;
    base = base.replaceAll("[^\\w\\- ]", "_");           // keep it filesystem- and Telegram-safe
    if (base.length() > 64) base = base.substring(0, 64);
    return base + "." + ext;
  }
}
