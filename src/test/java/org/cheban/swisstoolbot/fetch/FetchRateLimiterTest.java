package org.cheban.swisstoolbot.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FetchRateLimiter")
class FetchRateLimiterTest {
  private static final long CHAT_ID = 42L;
  private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
  private static final int CALLERS = 512;
  private static final int ROUNDS = 200;

  @Test
  @DisplayName("the per-chat limit rejects the request that crosses it")
  void enforcesThePerChatLimit() {
    FetchRateLimiter limiter = new FetchRateLimiter(2, ONE_MINUTE, 1000);

    assertThat(limiter.tryAcquire(CHAT_ID)).isTrue();
    assertThat(limiter.tryAcquire(CHAT_ID)).isTrue();
    assertThat(limiter.tryAcquire(CHAT_ID)).isFalse();
  }

  @Test
  @DisplayName("the per-chat limit is tracked independently per chat")
  void tracksEachChatSeparately() {
    FetchRateLimiter limiter = new FetchRateLimiter(1, ONE_MINUTE, 1000);

    assertThat(limiter.tryAcquire(CHAT_ID)).isTrue();
    assertThat(limiter.tryAcquire(CHAT_ID)).isFalse();
    assertThat(limiter.tryAcquire(CHAT_ID + 1)).isTrue();
  }

  @Test
  @DisplayName("the global limit rejects the request that crosses it even across chats")
  void enforcesTheGlobalLimit() {
    FetchRateLimiter limiter = new FetchRateLimiter(1000, ONE_MINUTE, 2);

    assertThat(limiter.tryAcquire(1)).isTrue();
    assertThat(limiter.tryAcquire(2)).isTrue();
    assertThat(limiter.tryAcquire(3)).isFalse();
  }

  @Test
  @DisplayName("a rejected call does not consume the other quota")
  void rejectedCallsDoNotConsumeQuota() {
    FetchRateLimiter limiter = new FetchRateLimiter(1, ONE_MINUTE, 2);

    assertThat(limiter.tryAcquire(1)).isTrue();
    assertThat(limiter.tryAcquire(1)).isFalse();
    // the per-chat rejection above must not have spent the global slot, so a second chat still fits
    assertThat(limiter.tryAcquire(2)).isTrue();
    assertThat(limiter.tryAcquire(3)).isFalse();
  }

  @Test
  @DisplayName("the window resets once it expires")
  void resetsAfterTheWindow() throws InterruptedException {
    FetchRateLimiter limiter = new FetchRateLimiter(1, Duration.ofMillis(100), 1000);

    assertThat(limiter.tryAcquire(CHAT_ID)).isTrue();
    assertThat(limiter.tryAcquire(CHAT_ID)).isFalse();

    Thread.sleep(300);

    assertThat(limiter.tryAcquire(CHAT_ID)).isTrue();
  }

  @Test
  @DisplayName("overlapping callers can never acquire more than the per-chat limit")
  void overlappingCallersCannotOvershootThePerChatLimit() throws Exception {
    int limit = 8;

    for (int round = 0; round < ROUNDS; round++) {
      FetchRateLimiter limiter = new FetchRateLimiter(limit, ONE_MINUTE, 100_000);
      assertThat(grantedAmongOverlapping(limiter, false))
          .as("per-chat grants in round %d", round)
          .isLessThanOrEqualTo(limit);
    }
  }

  @Test
  @DisplayName("overlapping callers can never acquire more than the global limit")
  void overlappingCallersCannotOvershootTheGlobalLimit() throws Exception {
    int limit = 8;

    for (int round = 0; round < ROUNDS; round++) {
      FetchRateLimiter limiter = new FetchRateLimiter(100_000, ONE_MINUTE, limit);
      assertThat(grantedAmongOverlapping(limiter, true))
          .as("global grants in round %d", round)
          .isLessThanOrEqualTo(limit);
    }
  }

  /**
   * Fires {@link #CALLERS} {@code tryAcquire} calls that all reach the limiter at the same moment and
   * returns how many were granted. With {@code distinctChats} every caller uses its own chat, which
   * pits them against the global window alone.
   */
  private static int grantedAmongOverlapping(FetchRateLimiter limiter, boolean distinctChats) throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(CALLERS);
    AtomicLong chatSequence = new AtomicLong();
    List<Future<Boolean>> futures = new ArrayList<>(CALLERS);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CALLERS; i++) {
        futures.add(executor.submit(() -> {
          barrier.await();
          return limiter.tryAcquire(distinctChats ? chatSequence.incrementAndGet() : CHAT_ID);
        }));
      }
    }
    int granted = 0;
    for (Future<Boolean> future : futures) {
      if (future.get()) {
        granted++;
      }
    }
    return granted;
  }
}
