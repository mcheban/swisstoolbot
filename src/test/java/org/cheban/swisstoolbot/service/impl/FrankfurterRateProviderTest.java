package org.cheban.swisstoolbot.service.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Proves the {@code @Cacheable} lookup really happens: the provider is called from another bean, so
 * the call goes through its Spring proxy instead of being a self-invocation.
 *
 * <p>This is the one test that needs a container, because the proxy is what is under test; the
 * surrounding logic is covered by {@link FrankfurterExchangeRateServiceTest}.
 */
@SpringJUnitConfig(FrankfurterRateProviderTest.TestConfig.class)
@DisplayName("FrankfurterRateProvider caching")
class FrankfurterRateProviderTest {
  private static final String CACHE = "exchangeRates";

  @Autowired
  private FrankfurterRateProvider rateProvider;

  @Autowired
  private CacheManager cacheManager;

  @Autowired
  private FrankfurterExchangeRateService exchangeRateService;

  @AfterEach
  void clearCache() {
    Cache cache = cacheManager.getCache(CACHE);
    if (cache != null) cache.clear();
  }

  @Test
  @DisplayName("a second lookup of the same pair is served from the cache")
  void servesRepeatedLookupsFromTheCache() {
    AtomicInteger requests = new AtomicInteger();
    try (MockedStatic<HttpUtil> http = Mockito.mockStatic(HttpUtil.class)) {
      http.when(() -> HttpUtil.loadJson(anyString(), eq(FrankfurterRateProvider.ExchangeData.class)))
          .thenAnswer(invocation -> countAndAnswer(requests, new BigDecimal("1.35")));

      assertThat(rateProvider.getRate("USD", "CAD")).isEqualByComparingTo("1.35");
      assertThat(rateProvider.getRate("USD", "CAD")).isEqualByComparingTo("1.35");

      assertThat(requests).hasValue(1);
    }
  }

  @Test
  @DisplayName("convert() benefits from the cache too, which is what the proxy is for")
  void cachesThroughTheService() {
    AtomicInteger requests = new AtomicInteger();
    try (MockedStatic<HttpUtil> http = Mockito.mockStatic(HttpUtil.class)) {
      http.when(() -> HttpUtil.loadJson(anyString(), eq(FrankfurterRateProvider.ExchangeData.class)))
          .thenAnswer(invocation -> countAndAnswer(requests, new BigDecimal("1.35")));

      assertThat(exchangeRateService.convert("1 usd")).contains("1 USD = 1.35 CAD");
      assertThat(exchangeRateService.convert("2 usd")).contains("2 USD = 2.70 CAD");

      assertThat(requests).hasValue(1);
    }
  }

  @Test
  @DisplayName("a failed lookup is not cached, so the next one retries")
  void doesNotCacheFailures() {
    AtomicInteger requests = new AtomicInteger();
    try (MockedStatic<HttpUtil> http = Mockito.mockStatic(HttpUtil.class)) {
      http.when(() -> HttpUtil.loadJson(anyString(), eq(FrankfurterRateProvider.ExchangeData.class)))
          .thenAnswer(invocation -> {
            requests.incrementAndGet();
            return null;
          });

      assertThat(rateProvider.getRate("USD", "CAD")).isNull();
      assertThat(rateProvider.getRate("USD", "CAD")).isNull();

      assertThat(requests).hasValue(2);
    }
  }

  private static FrankfurterRateProvider.ExchangeData countAndAnswer(AtomicInteger requests, BigDecimal rate) {
    requests.incrementAndGet();
    FrankfurterRateProvider.ExchangeData data = new FrankfurterRateProvider.ExchangeData();
    data.setRate(rate);
    return data;
  }

  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  static class TestConfig {

    @Bean
    FrankfurterRateProvider rateProvider() {
      return new FrankfurterRateProvider();
    }

    @Bean
    FrankfurterExchangeRateService exchangeRateService(FrankfurterRateProvider rateProvider) {
      return new FrankfurterExchangeRateService(rateProvider);
    }

    @Bean
    CacheManager cacheManager() {
      CaffeineCacheManager cacheManager = new CaffeineCacheManager(CACHE);
      cacheManager.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)));
      return cacheManager;
    }
  }
}
