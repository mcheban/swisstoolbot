package org.cheban.swisstoolbot.fetch;

import java.util.List;

/**
 * A top-level content block of a {@link WebDocument}. The model only mirrors the constructs the
 * renderers understand; any other markup is flattened away by {@link DocumentExtractor}.
 */
public sealed interface Block {

  /** A heading; {@code level} is the original HTML heading level 1-6. */
  record Heading(int level, List<Inline> inlines) implements Block {
  }

  /** A paragraph of inline content. */
  record Paragraph(List<Inline> inlines) implements Block {
  }

  /**
   * An ordered or unordered list. A nested list is represented as a {@link ListBlock} inside the
   * {@link ListItem#blocks() blocks} of its parent entry.
   */
  record ListBlock(boolean ordered, List<ListItem> items) implements Block {
  }

  /** A single list entry, which may itself hold nested blocks (a nested list or extra paragraphs). */
  record ListItem(List<Block> blocks) {
  }

  /** A block quotation. */
  record Quote(List<Block> blocks) implements Block {
  }

  /** Preformatted text; {@code language} is {@code null} when the page declared none. */
  record Pre(String text, String language) implements Block {
  }

  /** An image; {@code src} is always an absolute URL. */
  record Image(String src, String alt) implements Block {
  }

  /** A table flattened to rows of plain-text cells; header rows are not distinguished. */
  record Table(List<List<String>> rows) implements Block {
  }
}
