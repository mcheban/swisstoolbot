package org.cheban.swisstoolbot.fetch;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Decides whether a {@link WebDocument} can be delivered as one inline Telegram message or has to be
 * published to telegra.ph. The size decision is taken on the message the {@link TelegramRenderer}
 * actually produces, so every accepted document is guaranteed to stay under Telegram's 4096-character
 * limit and its entity budget: the renderer's list markers, blank-line separators, indentation and
 * inline tags all count.
 */
@Component
@RequiredArgsConstructor
public class InlineClassifier {
  /** Rendered message characters that still leave headroom under Telegram's 4096-character limit. */
  static final int MAX_RENDERED_LENGTH = 3500;
  /** Deepest list nesting a chat message can show without becoming unreadable; a top-level list is 1. */
  static final int MAX_LIST_DEPTH = 2;
  /** Entity-producing tags above which the message risks Telegram's ~100-entity limit. */
  static final int MAX_ENTITIES = 50;
  /** Opening tags that each become one Telegram message entity. */
  private static final List<String> ENTITY_TAGS =
      List.of("<b>", "<i>", "<a ", "<code", "<pre", "<blockquote>");

  private final TelegramRenderer telegramRenderer;

  public boolean fitsInline(WebDocument document) {
    if (PlainText.hasImageOrTable(document.blocks())) {
      return false;
    }
    if (PlainText.maxListDepth(document.blocks()) > MAX_LIST_DEPTH) {
      return false;
    }
    String rendered = telegramRenderer.render(document);
    return rendered.length() <= MAX_RENDERED_LENGTH && entityCount(rendered) < MAX_ENTITIES;
  }

  /**
   * Counts the entity-producing opening tags in a rendered message, i.e. everything Telegram turns
   * into an entity. Escaped text cannot be mistaken for a tag because the renderer escapes {@code <}.
   */
  static int entityCount(String rendered) {
    int count = 0;
    for (String tag : ENTITY_TAGS) {
      for (int index = rendered.indexOf(tag); index >= 0; index = rendered.indexOf(tag, index + tag.length())) {
        count++;
      }
    }
    return count;
  }
}
