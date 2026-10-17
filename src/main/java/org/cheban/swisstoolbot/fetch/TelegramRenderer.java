package org.cheban.swisstoolbot.fetch;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link WebDocument} as a Telegram {@code parse_mode=HTML} message.
 *
 * <p>Only tags the Bot API accepts are emitted ({@code b, i, u, s, a, code, pre, blockquote}) — never
 * {@code <p>}, {@code <br>} or heading/list tags, so the message survives Telegram's HTML parser
 * unchanged. Layout: bold title, blank line, the body blocks, blank line, the source URL.
 *
 * <p>Images and tables are skipped: {@link InlineClassifier} only routes documents without them here,
 * and there is no way to express either inside a plain message.
 */
@Component
public class TelegramRenderer {
  private static final String INDENT = "  ";
  private static final String BULLET = "• ";
  private static final String SEPARATOR = "\n\n";
  private static final String LINE_SEPARATOR = "\n";

  public String render(WebDocument document) {
    List<String> parts = new ArrayList<>();
    if (!document.title().isBlank()) {
      parts.add("<b>" + escape(document.title()) + "</b>");
    }
    for (Block block : withoutLeadingTitleHeading(document.blocks(), document.title())) {
      String rendered = renderBlock(block, 0);
      if (!rendered.isBlank()) {
        parts.add(rendered);
      }
    }
    if (!document.sourceUrl().isBlank()) {
      parts.add(link(document.sourceUrl(), escape(document.sourceUrl())));
    }
    return String.join(SEPARATOR, parts);
  }

  /**
   * The extractor usually repeats the page title as the first heading; it is dropped here so the
   * message does not open with the same line twice.
   */
  private static List<Block> withoutLeadingTitleHeading(List<Block> blocks, String title) {
    if (blocks.isEmpty() || title.isBlank()) {
      return blocks;
    }
    if (blocks.getFirst() instanceof Block.Heading heading
        && title.equals(PlainText.ofInlines(heading.inlines()).strip())) {
      return blocks.subList(1, blocks.size());
    }
    return blocks;
  }

  private String renderBlock(Block block, int depth) {
    return switch (block) {
      case Block.Heading heading -> "<b>" + renderInlines(heading.inlines()) + "</b>";
      case Block.Paragraph paragraph -> renderInlines(paragraph.inlines());
      case Block.ListBlock list -> renderList(list, depth);
      case Block.Quote quote -> "<blockquote>" + renderBlocks(quote.blocks(), depth) + "</blockquote>";
      case Block.Pre pre -> renderPre(pre);
      case Block.Image ignored -> "";
      case Block.Table table -> renderTable(table);
    };
  }

  private String renderBlocks(List<Block> blocks, int depth) {
    return blocks.stream()
        .map(block -> renderBlock(block, depth))
        .filter(rendered -> !rendered.isBlank())
        .reduce((left, right) -> left + LINE_SEPARATOR + right)
        .orElse("");
  }

  private String renderList(Block.ListBlock list, int depth) {
    String indent = INDENT.repeat(depth);
    List<String> lines = new ArrayList<>();
    int index = 1;
    for (Block.ListItem item : list.items()) {
      String content = renderItem(item, depth);
      if (content.isBlank()) {
        continue;
      }
      lines.add(indent + (list.ordered() ? index + ". " : BULLET) + content);
      index++;
    }
    return String.join(LINE_SEPARATOR, lines);
  }

  /**
   * Renders the blocks of one list entry: the first block continues the bullet line, later ones start
   * on their own indented line. A nested list already carries its own indentation.
   */
  private String renderItem(Block.ListItem item, int depth) {
    String indent = INDENT.repeat(depth + 1);
    StringBuilder content = new StringBuilder();
    for (Block block : item.blocks()) {
      String rendered = renderBlock(block, depth + 1);
      if (rendered.isBlank()) {
        continue;
      }
      if (content.isEmpty()) {
        content.append(rendered);
      } else if (block instanceof Block.ListBlock) {
        content.append(LINE_SEPARATOR).append(rendered);
      } else {
        content.append(LINE_SEPARATOR).append(indent).append(rendered);
      }
    }
    return content.toString();
  }

  private String renderPre(Block.Pre pre) {
    String body = escape(pre.text());
    if (pre.language() == null || pre.language().isBlank()) {
      return "<pre>" + body + "</pre>";
    }
    // Telegram highlights the code when the language is passed as a language-* class on <code>.
    return "<pre><code class=\"language-" + escapeAttribute(pre.language()) + "\">" + body + "</code></pre>";
  }

  /** Tables never reach this renderer, but flattening them is cheaper than failing the reply. */
  private String renderTable(Block.Table table) {
    return table.rows().stream()
        .map(row -> row.stream().map(TelegramRenderer::escape).reduce((l, r) -> l + " | " + r).orElse(""))
        .filter(row -> !row.isBlank())
        .reduce((top, bottom) -> top + LINE_SEPARATOR + bottom)
        .orElse("");
  }

  private String renderInlines(List<Inline> inlines) {
    StringBuilder text = new StringBuilder();
    for (Inline inline : inlines) {
      text.append(renderInline(inline));
    }
    return text.toString();
  }

  private String renderInline(Inline inline) {
    return switch (inline) {
      case Inline.Text node -> escape(node.text());
      case Inline.Bold bold -> "<b>" + renderInlines(bold.inlines()) + "</b>";
      case Inline.Italic italic -> "<i>" + renderInlines(italic.inlines()) + "</i>";
      case Inline.Code code -> "<code>" + escape(code.text()) + "</code>";
      case Inline.Link link -> renderLink(link);
      case Inline.LineBreak ignored -> LINE_SEPARATOR;
    };
  }

  private String renderLink(Inline.Link link) {
    String label = renderInlines(link.inlines());
    return link(link.href(), label.isEmpty() ? escape(link.href()) : label);
  }

  private static String link(String href, String label) {
    return "<a href=\"" + escapeAttribute(href) + "\">" + label + "</a>";
  }

  /** Telegram needs {@code &}, {@code <} and {@code >} escaped; the ampersand goes first. */
  static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String escapeAttribute(String value) {
    return escape(value).replace("\"", "&quot;");
  }
}
