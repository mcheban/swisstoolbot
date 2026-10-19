package org.cheban.swisstoolbot.fetch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Renders a {@link WebDocument} into one or more Telegram HTML messages.
 *
 * <p>The source URL is placed on the first line, followed by the bold title and the body. A document
 * that does not fit a single message is split at block boundaries (and, when a single block is too
 * long, at inline or text boundaries) into at most {@code fetch.max-messages} messages. Every message
 * of a split reply carries the source URL followed by a {@code N/M} page indicator. When the document
 * is even larger than that many messages, the last message is marked as truncated.
 *
 * <p>Images and tables are skipped or flattened: there is no way to express either inside a plain
 * message.
 */
@Component
public class TelegramRenderer {
  /** Effective per-message budget, kept under Telegram's 4096-character limit for entity headroom. */
  static final int MAX_MESSAGE_LENGTH = 3500;
  static final String TRUNCATION_MARKER = "\n\n<i>[Content truncated]</i>";
  private static final String INDENT = "  ";
  private static final String BULLET = "• ";
  private static final String SEPARATOR = "\n\n";
  private static final String LINE_SEPARATOR = "\n";
  private static final String BLOCKQUOTE_OPEN = "<blockquote>";
  private static final String BLOCKQUOTE_CLOSE = "</blockquote>";
  private static final int BOLD_OVERHEAD = "<b></b>".length();
  private static final int ITALIC_OVERHEAD = "<i></i>".length();

  private final int maxMessages;

  public TelegramRenderer(@Value("${fetch.max-messages:5}") int maxMessages) {
    this.maxMessages = Math.max(1, maxMessages);
  }

  /** Renders the document into the messages the bot should send, in order. */
  public List<String> render(WebDocument document) {
    String header = sourceHeader(document.sourceUrl());
    int indicatorReserve = (" " + maxMessages + "/" + maxMessages).length();
    int budget = bodyBudget(header.length() + indicatorReserve);

    List<List<String>> pages = paginate(bodyPieces(document, budget), budget);
    boolean truncated = pages.size() > maxMessages;
    if (truncated) {
      pages = new ArrayList<>(pages.subList(0, maxMessages));
    }
    return toMessages(header, pages, truncated);
  }

  private static List<String> toMessages(String header, List<List<String>> pages, boolean truncated) {
    int total = pages.size();
    List<String> messages = new ArrayList<>(total);
    for (int index = 0; index < total; index++) {
      StringBuilder message = new StringBuilder(header);
      if (total > 1) {
        message.append(" [").append(index + 1).append('/').append(total).append(']');
      }
      String body = String.join(SEPARATOR, pages.get(index));
      if (!body.isEmpty()) {
        message.append(SEPARATOR).append(body);
      }
      if (truncated && index == total - 1) {
        message.append(TRUNCATION_MARKER);
      }
      messages.add(message.toString());
    }
    return messages;
  }

  private static int bodyBudget(int headerLength) {
    int budget = MAX_MESSAGE_LENGTH - headerLength - SEPARATOR.length() - TRUNCATION_MARKER.length();
    return Math.max(1, budget);
  }

  private static String sourceHeader(String url) {
    if (url == null || url.isBlank()) {
      return "";
    }
    return link(url, escape(url));
  }

  private List<String> bodyPieces(WebDocument document, int budget) {
    List<Block> blocks = withoutLeadingTitleHeading(document.blocks(), document.title());
    List<String> pieces = new ArrayList<>();
    int firstBudget = budget;
    if (document.title() != null && !document.title().isBlank()) {
      String title = renderInlines(List.of(
          new Inline.Bold(List.of(new Inline.Text(document.title())))));
      pieces.add(title);
      // Keep the title on the same page as the first content chunk.
      firstBudget = Math.max(1, budget - title.length() - SEPARATOR.length());
    }
    boolean first = true;
    for (Block block : blocks) {
      pieces.addAll(splitBlock(block, 0, first ? firstBudget : budget));
      first = false;
    }
    pieces.removeIf(String::isBlank);
    return pieces;
  }

  /**
   * The extractor usually repeats the page title as the first heading; it is dropped here so the
   * message does not open with the same line twice.
   */
  private static List<Block> withoutLeadingTitleHeading(List<Block> blocks, String title) {
    if (blocks == null || blocks.isEmpty() || title == null || title.isBlank()) {
      return blocks == null ? List.of() : blocks;
    }
    if (blocks.getFirst() instanceof Block.Heading heading
        && title.equals(PlainText.ofInlines(heading.inlines()).strip())) {
      return blocks.subList(1, blocks.size());
    }
    return blocks;
  }

  /** Greedily packs rendered pieces into pages, keeping each page within {@code budget}. */
  private static List<List<String>> paginate(List<String> pieces, int budget) {
    List<List<String>> pages = new ArrayList<>();
    List<String> current = new ArrayList<>();
    long currentLength = 0;
    for (String piece : pieces) {
      int added = current.isEmpty() ? piece.length() : SEPARATOR.length() + piece.length();
      if (!current.isEmpty() && currentLength + added > budget) {
        pages.add(List.copyOf(current));
        current.clear();
        currentLength = 0;
        added = piece.length();
      }
      current.add(piece);
      currentLength += added;
    }
    pages.add(List.copyOf(current));
    return pages;
  }

  /**
   * Renders one block into one or more strings that each fit {@code budget}. A block that is already
   * small enough is rendered as a whole; an oversized one is split at its natural boundaries so no
   * Telegram entity (bold, link, ...) is ever cut in half.
   */
  private List<String> splitBlock(Block block, int depth, int budget) {
    String rendered = renderBlock(block, depth);
    if (rendered.length() <= budget) {
      return rendered.isBlank() ? List.of() : List.of(rendered);
    }
    return switch (block) {
      case Block.Heading heading -> splitInlinesToBlocks(heading.inlines(), budget - BOLD_OVERHEAD,
          inlines -> "<b>" + renderInlines(inlines) + "</b>");
      case Block.Paragraph paragraph -> splitInlinesToBlocks(paragraph.inlines(), budget,
          this::renderInlines);
      case Block.Pre pre -> splitPre(pre, budget);
      case Block.Quote quote -> splitQuote(quote, depth, budget);
      case Block.ListBlock list -> splitList(list, depth, budget);
      case Block.Table table -> splitTable(table, budget);
      case Block.Image ignored -> List.of();
    };
  }

  private List<String> splitInlinesToBlocks(List<Inline> inlines, int budget,
                                            Function<List<Inline>, String> renderer) {
    List<String> result = new ArrayList<>();
    for (List<Inline> chunk : splitInlines(inlines, Math.max(1, budget))) {
      String rendered = renderer.apply(chunk);
      if (!rendered.isBlank()) {
        result.add(rendered);
      }
    }
    return result;
  }

  private List<String> splitPre(Block.Pre pre, int budget) {
    int overhead = renderPre(new Block.Pre("", pre.language())).length();
    int available = Math.max(1, budget - overhead);
    List<String> parts = new ArrayList<>();
    for (String chunk : splitText(pre.text(), available)) {
      parts.add(renderPre(new Block.Pre(chunk, pre.language())));
    }
    return parts;
  }

  private List<String> splitQuote(Block.Quote quote, int depth, int budget) {
    int overhead = BLOCKQUOTE_OPEN.length() + BLOCKQUOTE_CLOSE.length();
    int innerBudget = Math.max(1, budget - overhead);
    List<String> inner = new ArrayList<>();
    for (Block block : quote.blocks()) {
      inner.addAll(splitBlock(block, depth, innerBudget));
    }
    return packPieces(inner, innerBudget).stream()
        .map(part -> BLOCKQUOTE_OPEN + part + BLOCKQUOTE_CLOSE)
        .toList();
  }

  private List<String> splitList(Block.ListBlock list, int depth, int budget) {
    String indent = INDENT.repeat(depth);
    String continuationIndent = INDENT.repeat(depth + 1);
    List<String> lines = new ArrayList<>();
    int index = 1;
    for (Block.ListItem item : list.items()) {
      String marker = indent + (list.ordered() ? index + ". " : BULLET);
      List<String> parts = splitListItem(item, depth, Math.max(1, budget - marker.length()));
      if (parts.isEmpty()) {
        continue;
      }
      lines.add(marker + parts.getFirst());
      for (int part = 1; part < parts.size(); part++) {
        lines.add(continuationIndent + parts.get(part));
      }
      index++;
    }
    return packPieces(lines, budget);
  }

  private List<String> splitListItem(Block.ListItem item, int depth, int budget) {
    String rendered = renderItem(item, depth);
    if (rendered.length() <= budget) {
      return rendered.isBlank() ? List.of() : List.of(rendered);
    }
    List<String> pieces = new ArrayList<>();
    for (Block block : item.blocks()) {
      pieces.addAll(splitBlock(block, depth + 1, budget));
    }
    return packPieces(pieces, budget);
  }

  private List<String> splitTable(Block.Table table, int budget) {
    List<String> lines = new ArrayList<>();
    for (List<String> row : table.rows()) {
      String rendered = row.stream()
          .map(TelegramRenderer::escape)
          .reduce((left, right) -> left + " | " + right)
          .orElse("");
      if (rendered.isBlank()) {
        continue;
      }
      if (rendered.length() <= budget) {
        lines.add(rendered);
      } else {
        lines.addAll(splitText(rendered, budget));
      }
    }
    return packPieces(lines, budget);
  }

  private static List<String> packPieces(List<String> pieces, int budget) {
    List<String> packed = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String piece : pieces) {
      if (piece.isBlank()) {
        continue;
      }
      if (!current.isEmpty() && current.length() + TelegramRenderer.LINE_SEPARATOR.length() + piece.length() > budget) {
        packed.add(current.toString());
        current.setLength(0);
      }
      if (!current.isEmpty()) {
        current.append(TelegramRenderer.LINE_SEPARATOR);
      }
      current.append(piece);
    }
    if (!current.isEmpty()) {
      packed.add(current.toString());
    }
    return packed;
  }

  private String renderBlock(Block block, int depth) {
    return switch (block) {
      case Block.Heading heading -> "<b>" + renderInlines(heading.inlines()) + "</b>";
      case Block.Paragraph paragraph -> renderInlines(paragraph.inlines());
      case Block.ListBlock list -> renderList(list, depth);
      case Block.Quote quote -> BLOCKQUOTE_OPEN + renderBlocks(quote.blocks(), depth) + BLOCKQUOTE_CLOSE;
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

  /** Tables never reach a page as a table, but flattening them is cheaper than failing the reply. */
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

  // ---------------------------------------------------------------------------------------------
  // Splitting inline content
  // ---------------------------------------------------------------------------------------------

  /** Splits top-level inlines into chunks whose rendered form each fits {@code budget}. */
  private List<List<Inline>> splitInlines(List<Inline> inlines, int budget) {
    List<List<Inline>> chunks = new ArrayList<>();
    List<Inline> current = new ArrayList<>();
    for (Inline inline : inlines) {
      for (Inline piece : splitInline(inline, budget)) {
        if (!current.isEmpty() && renderInlines(appended(current, piece)).length() > budget) {
          chunks.add(List.copyOf(current));
          current = new ArrayList<>();
        }
        current.add(piece);
      }
    }
    if (!current.isEmpty()) {
      chunks.add(List.copyOf(current));
    }
    return chunks;
  }

  /** Splits a single inline whose rendered form exceeds {@code budget}; a small one is returned as is. */
  private List<Inline> splitInline(Inline inline, int budget) {
    if (renderInline(inline).length() <= budget) {
      return List.of(inline);
    }
    return switch (inline) {
      case Inline.Text text -> inlineTextPieces(text.text(), budget, Inline.Text::new);
      case Inline.Code code -> inlineTextPieces(code.text(), budget, Inline.Code::new);
      case Inline.Bold bold -> splitWrapped(bold.inlines(), budget, BOLD_OVERHEAD, Inline.Bold::new);
      case Inline.Italic italic ->
          splitWrapped(italic.inlines(), budget, ITALIC_OVERHEAD, Inline.Italic::new);
      case Inline.Link link -> splitLink(link, budget);
      case Inline.LineBreak ignored -> List.of(inline);
    };
  }

  private List<Inline> splitWrapped(List<Inline> children, int budget, int overhead,
                                    Function<List<Inline>, Inline> wrapper) {
    int innerBudget = Math.max(1, budget - overhead);
    List<Inline> result = new ArrayList<>();
    for (List<Inline> chunk : splitInlines(children, innerBudget)) {
      result.add(wrapper.apply(chunk));
    }
    return result;
  }

  private List<Inline> splitLink(Inline.Link link, int budget) {
    if (link.inlines().isEmpty()) {
      return List.of(link);
    }
    int overhead = "<a href=\"\"></a>".length() + escapeAttribute(link.href()).length();
    int innerBudget = Math.max(1, budget - overhead);
    List<Inline> result = new ArrayList<>();
    for (List<Inline> chunk : splitInlines(link.inlines(), innerBudget)) {
      result.add(new Inline.Link(link.href(), chunk));
    }
    return result;
  }

  private static List<Inline> inlineTextPieces(String text, int budget, Function<String, Inline> factory) {
    List<Inline> pieces = new ArrayList<>();
    for (String chunk : splitText(text, budget)) {
      pieces.add(factory.apply(chunk));
    }
    return pieces;
  }

  private static List<Inline> appended(List<Inline> current, Inline piece) {
    List<Inline> candidate = new ArrayList<>(current.size() + 1);
    candidate.addAll(current);
    candidate.add(piece);
    return candidate;
  }

  /** Splits text so the escaped form of every piece fits {@code budget}. */
  private static List<String> splitText(String text, int budget) {
    List<String> pieces = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = maxFittingEnd(text, start, budget);
      pieces.add(text.substring(start, end));
      start = end;
    }
    return pieces;
  }

  private static int maxFittingEnd(String text, int start, int budget) {
    int low = start + 1;
    int high = text.length();
    int best = low;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (escape(text.substring(start, mid)).length() <= budget) {
        best = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    if (best < text.length() && Character.isHighSurrogate(text.charAt(best - 1))) {
      return Math.max(start + 1, best - 1);   // never split a surrogate pair
    }
    return best;
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
