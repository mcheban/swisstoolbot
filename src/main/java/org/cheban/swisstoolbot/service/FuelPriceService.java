package org.cheban.swisstoolbot.service;

import org.cheban.swisstoolbot.objects.FuelPrice;

import java.util.List;

public interface FuelPriceService {
  List<FuelPrice> getPrices();
}
