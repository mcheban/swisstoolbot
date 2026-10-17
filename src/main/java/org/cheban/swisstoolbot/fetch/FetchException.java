package org.cheban.swisstoolbot.fetch;

/**
 * Carries the {@link FetchError} a failure maps to, so callers never have to inspect exception
 * messages to decide which reply to send.
 */
public class FetchException extends RuntimeException {
  private final FetchError error;

  public FetchException(FetchError error) {
    this(error, error.name());
  }

  public FetchException(FetchError error, String message) {
    super(message);
    this.error = error;
  }

  public FetchError error() {
    return error;
  }
}
