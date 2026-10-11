package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.inject.Inject;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.service.ExchangeRateService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class FrankfurterExchangeRateService implements ExchangeRateService {
  private static final String API_URL = "https://api.frankfurter.dev/v2/rate/%s/%s";
  private static final String DEFAULT_TARGET = "CAD";
  private static final String ALT_TARGET = "USD";
  private static final Pattern INPUT = Pattern.compile(
      "^\\s*(\\d+(?:[.,]\\d+)?)\\s*([a-z]{3})(?:\\s+(?:in|to)\\s+([a-z]{3}))?\\s*$",
      Pattern.CASE_INSENSITIVE);

  // self-reference so @Cacheable goes through the Spring proxy (self-invocation bypasses it)
  @Lazy
  @Inject
  private FrankfurterExchangeRateService self;

  @Override
  public Optional<String> convert(String input) {
    if (input == null) return Optional.empty();
    Matcher m = INPUT.matcher(input);
    if (!m.matches()) return Optional.empty();

    BigDecimal amount = new BigDecimal(m.group(1).replace(',', '.'));
    String from = m.group(2).toUpperCase();
    String to = m.group(3) != null
        ? m.group(3).toUpperCase()
        : (from.equals(DEFAULT_TARGET) ? ALT_TARGET : DEFAULT_TARGET);

    BigDecimal rate = from.equals(to) ? BigDecimal.ONE : self.getRate(from, to);
    if (rate == null) return Optional.empty();

    BigDecimal result = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    return Optional.of("%s %s = %s %s".formatted(amount.stripTrailingZeros().toPlainString(), from,
        result.toPlainString(), to));
  }

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
