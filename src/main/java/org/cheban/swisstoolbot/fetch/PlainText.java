package org.cheban.swisstoolbot.fetch;

import java.util.List;

/**
 * Plain-text projection of the document model, used where a block collapses to text (the renderer
 * compares a leading heading against the title).
 */
final class PlainText {
  private PlainText() {
  }

  static String ofInlines(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    appendInlines(text, inlines);
    return text.toString();
  }

  private static void appendInlines(StringBuilder text, List<Inline> inlines) {
    for (Inline inline : inlines) {
      switch (inline) {
        case Inline.Text node -> text.append(node.text());
        case Inline.Bold bold -> appendInlines(text, bold.inlines());
        case Inline.Italic italic -> appendInlines(text, italic.inlines());
        case Inline.Code code -> text.append(code.text());
        case Inline.Link link -> appendInlines(text, link.inlines());
        case Inline.LineBreak ignored -> text.append('\n');
      }
    }
  }
}
