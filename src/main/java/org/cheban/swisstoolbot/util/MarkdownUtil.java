package org.cheban.swisstoolbot.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class MarkdownUtil {
  public static String toTelegramHtml(String md) {
    String s = md.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    s = s.replaceAll("(?s)```[A-Za-z0-9_+-]*\\n?(.*?)```", "<pre>$1</pre>");
    s = s.replaceAll("`([^`\\n]+)`", "<code>$1</code>");
    s = s.replaceAll("(?m)^#{1,6}[ \\t]+(.+)$", "<b>$1</b>");
    s = s.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
    s = s.replaceAll("(?m)^([ \\t]*)[*-][ \\t]+", "$1• ");
    s = s.replaceAll("(?m)^([ \\t]*\\d+\\.)[ \\t]+", "$1 ");
    s = s.replaceAll("(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])", "<i>$1</i>");
    s = s.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)", "<a href=\"$2\">$1</a>");
    return s;
  }
}
