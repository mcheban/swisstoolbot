package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.service.WeatherService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OpenMeteoWeatherService implements WeatherService {
  private static final String API_URL = "http://api.open-meteo.com/v1/forecast?latitude=%.6f&longitude=%.6f&timezone=auto&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min&current=temperature_2m,apparent_temperature,weather_code";
  private static final String HEADER_TEMPLATE = "Weather for %s\n\nCurrently %s %.0f° (FL %.0f°)\n\n";
  private static final String DAY_TEMPLATE = "%1$ta %1$te %1$tb %2$s %3$.0f° / %4$.0f° (FL %5$.0f° / %6$.0f°)";

  public Optional<String> forecastMsg(String name, Double lat, Double lon) {
    JsonNode response = HttpUtil.loadJson(String.format(API_URL, lat, lon));
    if (response != null && response.isObject()) {
      JsonNode currently = response.get("current");
      Integer weatherCode = currently.get("weather_code").asInt();
      Double temperature = currently.get("temperature_2m").asDouble();
      Double apparentTemperature = currently.get("apparent_temperature").asDouble();
      String msg = String.format(HEADER_TEMPLATE, name, getWeatherIcon(weatherCode), temperature, apparentTemperature) +
              parseDailyData(response.get("daily")).stream()
                      .map(OpenMeteoWeatherService::formatDailyData)
                      .collect(Collectors.joining("\n"));
      return Optional.of(msg);
    } else {
      log.error("Could not fetch weather");
      return Optional.empty();
    }
  }

  private List<WeatherData> parseDailyData(JsonNode daily) {
    List<WeatherData> weatherDataList = new ArrayList<>();

    JsonNode timeNodes = daily.get("time");
    JsonNode tempMinNodes = daily.get("temperature_2m_min");
    JsonNode tempMaxNodes = daily.get("temperature_2m_max");
    JsonNode appTempMinNodes = daily.get("apparent_temperature_min");
    JsonNode appTempMaxNodes = daily.get("apparent_temperature_max");
    JsonNode weatherCodeNodes = daily.get("weather_code");

    for (int i = 0; i < timeNodes.size(); i++) {
      weatherDataList.add(new WeatherData(LocalDate.parse(timeNodes.get(i).asText()),
              tempMinNodes.get(i).asDouble(),
              tempMaxNodes.get(i).asDouble(),
              appTempMinNodes.get(i).asDouble(),
              appTempMaxNodes.get(i).asDouble(),
              weatherCodeNodes.get(i).asInt()));
    }

    return weatherDataList;
  }

  private static String formatDailyData(WeatherData wd) {
    return String.format(DAY_TEMPLATE, wd.date, getWeatherIcon(wd.weatherCode),
            wd.tempMin, wd.tempMax, wd.appTempMin, wd.appTempMax);
  }

  private static String getWeatherIcon(Integer weatherCode) {
    return switch (weatherCode) {
      case 0 -> "☀️"; // Clear sky
      case 1, 2, 3 -> "⛅️"; // Mainly clear, partly cloudy, or overcast
      case 45, 48 -> "🌫️"; // Fog or depositing rime fog
      case 51, 53, 55 -> "🌧️"; // Drizzle: Light, moderate, or dense intensity
      case 56, 57 -> "🧊🌧️"; // Freezing Drizzle: Light or dense intensity
      case 61, 63, 65 -> "🌧️"; // Rain: Slight, moderate, or heavy intensity
      case 66, 67 -> "🧊🌧️"; // Freezing Rain: Light or heavy intensity
      case 71, 73, 75 -> "❄️"; // Snow fall: Slight, moderate, or heavy intensity
      case 77 -> "🌨️"; // Snow grains
      case 80, 81, 82 -> "🌦️"; // Rain showers: Slight, moderate, or violent
      case 85, 86 -> "🌨️"; // Snow showers: Slight or heavy
      case 95, 96, 99 -> "⛈️"; // Thunderstorm: Slight or moderate, with or without slight or heavy hail
      default -> "❓"; // Unknown weather code
    };
  }

  private record WeatherData(LocalDate date, double tempMin, double tempMax, double appTempMin, double appTempMax, int weatherCode) {
  }
}
