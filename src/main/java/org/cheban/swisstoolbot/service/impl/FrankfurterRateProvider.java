package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fetches a single exchange rate from Frankfurter. It is a bean of its own so the {@link Cacheable}
 * lookup goes through the Spring proxy: a call from another bean always does, unlike a self-call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FrankfurterRateProvider {
  private static final String API_URL = "https://api.frankfurter.dev/v2/rate/%s/%s";

  /**
   * Returns the rate for {@code from}/{@code to}, or {@code null} when it could not be loaded. A
   * failure is not cached, so the next request retries instead of pinning the error for the whole TTL.
   */
  @Cacheable(cacheNames = "exchangeRates", key = "#from + '/' + #to", unless = "#result == null")
  public BigDecimal getRate(String from, String to) {
    try {
      ExchangeData response = HttpUtil.loadJson(String.format(API_URL, from, to), ExchangeData.class);
      if (response == null) return null;
      return response.getRate();
    } catch (Exception e) {
      log.error("Could not load rate {}/{}: {}", from, to, e.getMessage());
      return null;
    }
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ExchangeData {
    private String date;
    private String base;
    private String quote;
    private BigDecimal rate;
  }
}
