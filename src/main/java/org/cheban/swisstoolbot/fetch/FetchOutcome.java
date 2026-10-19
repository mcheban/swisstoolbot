package org.cheban.swisstoolbot.fetch;

import java.util.List;

/**
 * What {@link UrlFetchService} hands back to the bot for a {@code /web} request. Exactly one of the
 * two shapes is produced, so the bot never has to guess whether a reply body is HTML or an error.
 */
public sealed interface FetchOutcome {

  /**
   * The page rendered for a Telegram message. {@code messages} holds one entry for a short page and
   * several entries (page 1, page 2, ...) when the content had to be split.
   */
  record Inline(List<String> messages) implements FetchOutcome {
  }

  /** The request failed; {@code error} selects the user-facing message. */
  record Failure(FetchError error) implements FetchOutcome {
  }
}
