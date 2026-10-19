package org.cheban.swisstoolbot.fetch;

import java.util.List;

/**
 * Inline content of a {@link Block}. Only the constructs the Telegram renderer can express are
 * modelled; other markup (underline, strike-through, layout spans, ...) is flattened to plain
 * {@link Text}.
 */
public sealed interface Inline {

  /** A run of text with whitespace collapsed to single spaces. */
  record Text(String text) implements Inline {
  }

  /** Bold text ({@code <b>}/{@code <strong>}). */
  record Bold(List<Inline> inlines) implements Inline {
  }

  /** Italic text ({@code <i>}/{@code <em>}). */
  record Italic(List<Inline> inlines) implements Inline {
  }

  /** Inline code ({@code <code>}). */
  record Code(String text) implements Inline {
  }

  /** A hyperlink; {@code href} is an absolute URL. */
  record Link(String href, List<Inline> inlines) implements Inline {
  }

  /** A hard line break ({@code <br>}). */
  record LineBreak() implements Inline {
  }
}
