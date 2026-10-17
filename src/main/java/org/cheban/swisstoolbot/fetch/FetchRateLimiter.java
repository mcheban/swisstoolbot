package org.cheban.swisstoolbot.fetch;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many {@code /web} requests a single chat and the bot as a whole may start within a fixed
 * window, so one user cannot make the bot hammer arbitrary sites.
 *
 * <p>The counters live in a Caffeine cache keyed by chat id (plus one shared key), so every window
 * expires on its own without a background task.
 */
@Component
public class FetchRateLimiter {
  private static final String GLOBAL_KEY = "*";
  private static final long MAX_TRACKED_CHATS = 100_000;

  private final Cache<String, AtomicInteger> counters;
  private final int perChatLimit;
  private final int globalLimit;

  public FetchRateLimiter(@Value("${fetch.rate-limit.per-chat:10}") int perChatLimit,
                          @Value("${fetch.rate-limit.window:1m}") Duration window,
                          @Value("${fetch.rate-limit.global:100}") int globalLimit) {
    this.counters = Caffeine.newBuilder()
        .expireAfterWrite(window)
        .maximumSize(MAX_TRACKED_CHATS)
        .build();
    this.perChatLimit = perChatLimit;
    this.globalLimit = globalLimit;
  }

  /**
   * Reserves one request slot for {@code chatId}. A rejected call consumes nothing, so a chat that
   * is over its own limit does not also drain the global budget.
   *
   * <p>Each counter is reserved with a compare-and-set loop rather than a separate check and
   * increment, so overlapping callers can never slip past a limit together.
   *
   * @return {@code true} when the request is within both limits; {@code false} when it must be rejected
   */
  public boolean tryAcquire(long chatId) {
    AtomicInteger chatCounter = counterFor(Long.toString(chatId));
    if (!reserve(chatCounter, perChatLimit)) {
      return false;
    }
    if (reserve(counterFor(GLOBAL_KEY), globalLimit)) {
      return true;
    }
    chatCounter.decrementAndGet(); // the chat slot is taken, but the global window is full
    return false;
  }

  /** Increments {@code counter} only while it stays below {@code limit}; the CAS makes the check
   *  and the increment a single atomic reservation. */
  private static boolean reserve(AtomicInteger counter, int limit) {
    for (int current = counter.get(); current < limit; current = counter.get()) {
      if (counter.compareAndSet(current, current + 1)) {
        return true;
      }
    }
    return false;
  }

  private AtomicInteger counterFor(String key) {
    return counters.get(key, _ -> new AtomicInteger());
  }
}
