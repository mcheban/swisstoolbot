package org.cheban.swisstoolbot.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.objects.FuelPrice;
import org.cheban.swisstoolbot.service.FuelPriceService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class NBFuelPriceService implements FuelPriceService {
  private static final String FUEL_URL = "https://nbeub.ca/index.php?page=current-petroleum-prices";

  @Cacheable(cacheNames = "fuelPrices", unless = "#result.isEmpty()")
  public List<FuelPrice> getPrices() {
    try {
      Document doc = Jsoup.connect(FUEL_URL).userAgent(HttpUtil.USER_AGENT).timeout(5000).get();
      Element table = doc.selectFirst("h1:matchesOwn(^\\s*Current Petroleum Prices\\s*$) ~ table");
      if (table == null) return List.of();

      List<FuelPrice> result = new ArrayList<>();
      for (Element tr : table.select("tr")) {
        if (tr.hasClass("header")) {
          if (!result.isEmpty()) break;   // second header (Heating Fuel): stop
          continue;                       // first header (Motor Fuel): skip
        }
        Elements td = tr.select("td");
        if (td.size() < 2) continue;
        result.add(new FuelPrice(td.get(0).text().trim(), new BigDecimal(td.get(1).text().trim())));
      }
      return result;
    } catch (Exception e) {
      log.error("Could not load fuel prices: {}", e.getMessage());
      return List.of();
    }
  }
}
