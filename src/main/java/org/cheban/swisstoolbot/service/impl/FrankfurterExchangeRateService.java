package org.cheban.swisstoolbot.service.impl;

import lombok.RequiredArgsConstructor;
import org.cheban.swisstoolbot.service.ExchangeRateService;
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
public class FrankfurterExchangeRateService implements ExchangeRateService {
  private static final String DEFAULT_TARGET = "CAD";
  private static final String ALT_TARGET = "USD";
  private static final Pattern INPUT = Pattern.compile(
      "^\\s*(\\d+(?:[.,]\\d+)?)\\s*([a-z]{3})(?:\\s+(?:in|to)\\s+([a-z]{3}))?\\s*$",
      Pattern.CASE_INSENSITIVE);

  private final FrankfurterRateProvider rateProvider;

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

    BigDecimal rate = from.equals(to) ? BigDecimal.ONE : rateProvider.getRate(from, to);
    if (rate == null) return Optional.empty();

    BigDecimal result = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    return Optional.of("%s %s = %s %s".formatted(amount.stripTrailingZeros().toPlainString(), from,
        result.toPlainString(), to));
  }
}
