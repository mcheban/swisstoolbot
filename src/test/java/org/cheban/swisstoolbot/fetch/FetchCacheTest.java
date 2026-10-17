package org.cheban.swisstoolbot.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FetchCache")
class FetchCacheTest {
  private static final String URL = "https://example.com/article";
  private static final FetchOutcome INLINE = new FetchOutcome.Inline("<b>Title</b>");
  private static final FetchOutcome FAILURE = new FetchOutcome.Failure(FetchError.FETCH_FAILED);

  private final FetchCache cache = new FetchCache(Duration.ofMinutes(30), 100);

  @Test
  @DisplayName("a second request for the same URL does not re-run the loader")
  void servesTheSecondRequestFromTheCache() {
    AtomicInteger calls = new AtomicInteger();

    assertThat(cache.getOrCompute(URL, counting(calls, INLINE))).isEqualTo(INLINE);
    assertThat(cache.getOrCompute(URL, counting(calls, INLINE))).isEqualTo(INLINE);

    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("different URLs are cached independently")
  void keepsSeparateEntriesPerUrl() {
    AtomicInteger calls = new AtomicInteger();

    cache.getOrCompute(URL, counting(calls, INLINE));
    cache.getOrCompute("https://example.com/other", counting(calls, INLINE));

    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("a failure is not cached, so the next request tries again")
  void doesNotCacheFailures() {
    AtomicInteger calls = new AtomicInteger();

    assertThat(cache.getOrCompute(URL, counting(calls, FAILURE))).isEqualTo(FAILURE);
    assertThat(cache.getOrCompute(URL, counting(calls, FAILURE))).isEqualTo(FAILURE);

    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("concurrent requests for the same URL collapse to a single computation")
  void collapsesConcurrentRequestsToOneComputation() throws Exception {
    int threads = 8;
    AtomicInteger calls = new AtomicInteger();
    CyclicBarrier barrier = new CyclicBarrier(threads);
    Function<String, FetchOutcome> loader = _ -> {
      calls.incrementAndGet();
      sleep();
      return INLINE;
    };
    Callable<FetchOutcome> task = () -> {
      barrier.await();
      return cache.getOrCompute(URL, loader);
    };

    List<Future<FetchOutcome>> futures;
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      futures = pool.invokeAll(Collections.nCopies(threads, task));
    }

    assertThat(calls).hasValue(1);
    for (Future<FetchOutcome> future : futures) {
      assertThat(future.get()).isEqualTo(INLINE);
    }
  }

  private static Function<String, FetchOutcome> counting(AtomicInteger calls, FetchOutcome outcome) {
    return _ -> {
      calls.incrementAndGet();
      return outcome;
    };
  }

  private static void sleep() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
