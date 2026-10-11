package org.cheban.swisstoolbot.service;

import java.util.Optional;

public interface ExchangeRateService {
  Optional<String> convert(String input);
}
