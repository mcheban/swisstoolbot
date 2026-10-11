package org.cheban.swisstoolbot.objects;

import java.math.BigDecimal;

public record ExchangeResult(String currencyFrom, String currencyTo, BigDecimal from, BigDecimal to) {
}
