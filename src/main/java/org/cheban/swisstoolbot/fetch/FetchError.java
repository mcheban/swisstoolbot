package org.cheban.swisstoolbot.fetch;

/**
 * Every way a {@code /web} request can fail. The bot maps each constant to a distinct user-facing
 * message, so the values are deliberately fine-grained rather than a single generic failure.
 */
public enum FetchError {
  INVALID_URL,
  BLOCKED_URL,
  FETCH_FAILED,
  NOT_HTML,
  TOO_LARGE,
  EMPTY_CONTENT,
  RATE_LIMITED,
  TELEGRAPH_FAILED
}
