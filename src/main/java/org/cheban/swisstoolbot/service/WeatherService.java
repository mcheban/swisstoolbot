package org.cheban.swisstoolbot.service;

import java.util.Optional;

public interface WeatherService {
  Optional<String> forecastMsg(String name, Double lat, Double lon);
}
