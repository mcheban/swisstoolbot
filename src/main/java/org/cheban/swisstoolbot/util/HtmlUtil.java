package org.cheban.swisstoolbot.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class HtmlUtil {
  private static final String LINK_HTML = "<a href=\"%s\">%s</a>";

  public static String buildLink(String url, String text) {
    return String.format(LINK_HTML, url, text);
  }
}
