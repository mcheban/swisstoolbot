package org.cheban.swisstoolbot.fetch;

/**
 * What {@link UrlFetchService} hands back to the bot for a {@code /web} request. Exactly one of the
 * three shapes is produced, so the bot never has to guess whether a reply body is HTML, a link or an
 * error.
 */
public sealed interface FetchOutcome {

  /** The page fits into a single message; {@code html} is ready for {@code parse_mode=HTML}. */
  record Inline(String html) implements FetchOutcome {
  }

  /** The page was too rich for a chat message; {@code url} is the generated telegra.ph page. */
  record Telegraph(String url) implements FetchOutcome {
  }

  /** The request failed; {@code error} selects the user-facing message. */
  record Failure(FetchError error) implements FetchOutcome {
  }
}
