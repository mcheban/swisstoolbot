package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.service.WeatherService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class OpenWeatherMapService implements WeatherService {
  private static final String API_URL = "http://api.openweathermap.org/data/3.0/onecall?lat=%.6f&lon=%.6f&exclude=minutely,hourly&appid=%s&units=metric";
  private static final String HEADER_TEMPLATE = "Weather for %s\n\nCurrently %s %.0f° (FL %.0f°)\n\n";
  private static final String DAY_TEMPLATE = "%1$ta %1$te %1$tb %2$s %3$.0f° / %4$.0f° (FL %5$.0f° / %6$.0f°)";

  private final BotProperties botProperties;

  @Override
  public Optional<String> forecastMsg(String name, Double lat, Double lon) {
    WeatherData weatherData = HttpUtil.loadJson(String.format(API_URL, lat, lon, botProperties.getOpenWeatherMapToken()), WeatherData.class);
    if (weatherData != null) {
      ZoneId zoneId = ZoneId.of(weatherData.getTimezone());
      String msg = String.format(HEADER_TEMPLATE, name, getWeatherIcon(weatherData.getCurrent().getWeatherIconCode()),
              weatherData.getCurrent().getTemp(), weatherData.getCurrent().getFeelsLike()) +
              weatherData.getDaily().stream().map(d -> formatDailyData(d, zoneId)).collect(Collectors.joining("\n"));
      return Optional.of(msg);
    } else {
      log.error("Could not fetch weather");
      return Optional.empty();
    }
  }

  private static String formatDailyData(WeatherData.DailyWeather wd, ZoneId zoneId) {
    ZonedDateTime date = ZonedDateTime.ofInstant(Instant.ofEpochSecond(wd.getDt()), zoneId);
    return String.format(DAY_TEMPLATE, date.toLocalDate(), getWeatherIcon(wd.getWeatherIconCode()),
            wd.getTemp().getMax(), wd.getTemp().getMin(), wd.getFeelsLike().getMaximum(), wd.getFeelsLike().getMinimum());
  }

  private static String getWeatherIcon(String weatherIcon) {
    return switch (weatherIcon) {
      case "01d" -> "☀️"; // clear sky day
      case "01n" -> "🌙"; // clear sky night
      case "02d" -> "⛅️"; // few clouds day
      case "02n" -> "☁️🌙"; // few clouds night
      case "03d", "03n" -> "☁️"; // scattered clouds
      case "04d", "04n" -> "☁️"; // broken clouds
      case "09d", "09n" -> "🌧️"; // shower rain
      case "10d", "10n" -> "🌧️"; // rain
      case "11d", "11n" -> "⛈️"; // thunderstorm
      case "13d", "13n" -> "❄️"; // snow
      case "50d", "50n" -> "🌫️"; // mist
      default -> "";
    };
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class WeatherData {

    @JsonProperty("timezone")
    private String timezone;

    @JsonProperty("current")
    private CurrentWeather current;

    @JsonProperty("daily")
    private List<DailyWeather> daily;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CurrentWeather {
      @JsonProperty("temp")
      private BigDecimal temp;

      @JsonProperty("feels_like")
      private BigDecimal feelsLike;

      @JsonProperty("weather")
      private List<Weather> weathers;

      public String getWeatherIconCode() {
        return Optional.ofNullable(getWeathers()).map(List::getFirst).map(Weather::getIconCode).orElse(null);
      }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyWeather {
      @JsonProperty("dt")
      private long dt;

      @JsonProperty("temp")
      private Temperature temp;

      @JsonProperty("feels_like")
      private Temperature feelsLike;

      @JsonProperty("weather")
      private List<Weather> weathers;

      public String getWeatherIconCode() {
        return Optional.ofNullable(getWeathers()).map(List::getFirst).map(Weather::getIconCode).orElse(null);
      }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Temperature {
      @JsonProperty("day")
      private BigDecimal day;

      @JsonProperty("night")
      private BigDecimal night;

      @JsonProperty("min")
      private BigDecimal min;

      @JsonProperty("max")
      private BigDecimal max;

      public BigDecimal getMinimum() {
        return Stream.of(getMin(), getMax(), getDay(), getNight())
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
      }

      public BigDecimal getMaximum() {
        return Stream.of(getMin(), getMax(), getDay(), getNight())
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
      }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Weather {
      @JsonProperty("id")
      private Integer id;

      @JsonProperty("icon")
      private String iconCode;
    }
  }
}
