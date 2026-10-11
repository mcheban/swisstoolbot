package org.cheban.swisstoolbot.service;

import org.cheban.swisstoolbot.objects.ExchangeResult;

public interface ExchangeRateService {
    ExchangeResult convert(String input);
}
