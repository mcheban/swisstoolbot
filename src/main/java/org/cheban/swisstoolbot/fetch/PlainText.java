package org.cheban.swisstoolbot.fetch;

import java.util.List;

/**
 * Plain-text projections of the document model, used where a block collapses to text (the renderer
 * compares a leading heading against the title) and for the structural checks the classifier runs
 * before rendering ({@link #maxListDepth}, {@link #hasImageOrTable}).
 */
final class PlainText {
  private PlainText() {
  }

  static String ofInlines(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    appendInlines(text, inlines);
    return text.toString();
  }

  /** Deepest list nesting in {@code blocks}; a top-level list counts as depth 1. */
  static int maxListDepth(List<Block> blocks) {
    return listDepth(blocks, 0);
  }

  /** Whether {@code blocks} hold anything the Telegram message layout cannot express. */
  static boolean hasImageOrTable(List<Block> blocks) {
    for (Block block : blocks) {
      if (block instanceof Block.Image || block instanceof Block.Table) {
        return true;
      }
      if (block instanceof Block.ListBlock list
          && list.items().stream().anyMatch(item -> hasImageOrTable(item.blocks()))) {
        return true;
      }
      if (block instanceof Block.Quote quote && hasImageOrTable(quote.blocks())) {
        return true;
      }
    }
    return false;
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

  private static int listDepth(List<Block> blocks, int depth) {
    int deepest = depth;
    for (Block block : blocks) {
      if (block instanceof Block.ListBlock list) {
        for (Block.ListItem item : list.items()) {
          deepest = Math.max(deepest, listDepth(item.blocks(), depth + 1));
        }
      } else if (block instanceof Block.Quote quote) {
        deepest = Math.max(deepest, listDepth(quote.blocks(), depth));
      }
    }
    return deepest;
  }
}
