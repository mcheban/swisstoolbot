package org.cheban.swisstoolbot.fetch;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * Remembers the outcome of a {@code /web} request by URL, so asking for the same link twice does not
 * refetch the page.
 *
 * <p>Only successes are kept: a failure is usually transient (a blip, a paywall, a page that grows
 * later), and caching it would keep the link unreadable for the whole TTL. {@link Cache#get} makes
 * the loader single-flight, so concurrent requests for the same URL still fetch at most once.
 */
@Slf4j
@Component
public class FetchCache {
  private final Cache<String, FetchOutcome> cache;

  public FetchCache(@Value("${fetch.cache.ttl:30m}") Duration ttl,
                    @Value("${fetch.cache.max-size:200}") long maxSize) {
    this.cache = Caffeine.newBuilder()
        .expireAfterWrite(ttl)
        .maximumSize(maxSize)
        .build();
  }

  /**
   * Returns the cached outcome for {@code url}, or runs {@code loader} once to produce it.
   *
   * @param loader never called for a cached URL, and never concurrently for the same URL
   */
  public FetchOutcome getOrCompute(String url, Function<String, FetchOutcome> loader) {
    FetchOutcome outcome = cache.get(url, loader);
    if (outcome instanceof FetchOutcome.Failure) {
      cache.invalidate(url);
    }
    return Objects.requireNonNull(outcome, "loader must return an outcome");
  }
}
