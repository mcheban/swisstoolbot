package org.cheban.swisstoolbot.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("FrankfurterExchangeRateService")
class FrankfurterExchangeRateServiceTest {
  private final FrankfurterRateProvider rateProvider = mock(FrankfurterRateProvider.class);
  private final FrankfurterExchangeRateService service = new FrankfurterExchangeRateService(rateProvider);

  @Test
  @DisplayName("converts through the provided rate, defaulting the target to CAD")
  void convertsUsingTheProviderRate() {
    when(rateProvider.getRate("USD", "CAD")).thenReturn(new BigDecimal("1.35"));

    assertThat(service.convert("100 usd")).contains("100 USD = 135.00 CAD");
  }

  @Test
  @DisplayName("defaults the target to USD when the source is already CAD")
  void defaultsToUsdForCad() {
    when(rateProvider.getRate("CAD", "USD")).thenReturn(new BigDecimal("0.74"));

    assertThat(service.convert("10 cad")).contains("10 CAD = 7.40 USD");
  }

  @Test
  @DisplayName("uses the explicit target currency")
  void usesTheExplicitTarget() {
    when(rateProvider.getRate("EUR", "CHF")).thenReturn(new BigDecimal("0.95"));

    assertThat(service.convert("20 eur to chf")).contains("20 EUR = 19.00 CHF");
  }

  @Test
  @DisplayName("does not ask for a rate when both currencies are the same")
  void skipsTheProviderForSameCurrency() {
    assertThat(service.convert("5 eur to eur")).contains("5 EUR = 5.00 EUR");

    verifyNoInteractions(rateProvider);
  }

  @Test
  @DisplayName("accepts a comma as the decimal separator")
  void acceptsCommaDecimals() {
    when(rateProvider.getRate("USD", "CAD")).thenReturn(new BigDecimal("1.35"));

    assertThat(service.convert("12,5 usd")).contains("12.5 USD = 16.88 CAD");
  }

  @Test
  @DisplayName("returns empty when the rate could not be loaded")
  void returnsEmptyWhenTheRateIsMissing() {
    when(rateProvider.getRate("USD", "CAD")).thenReturn(null);

    assertThat(service.convert("100 usd")).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("returns empty for input that is not an amount plus currency")
  void returnsEmptyForUnparsableInput() {
    assertThat(service.convert("one hundred dollars")).isEqualTo(Optional.empty());
    assertThat(service.convert("100")).isEqualTo(Optional.empty());
    assertThat(service.convert(null)).isEqualTo(Optional.empty());

    verifyNoInteractions(rateProvider);
  }
}
