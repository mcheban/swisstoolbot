package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.objects.LocationInfo;
import org.cheban.swisstoolbot.service.GeocodeService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.cheban.swisstoolbot.util.HttpUtil.urlEncode;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionstackGeocodeService implements GeocodeService {
  private static final String FORWARD_URL = "http://api.positionstack.com/v1/forward?access_key=%s&query=%s";
  private static final String REVERSE_URL = "http://api.positionstack.com/v1/reverse?access_key=%s&query=%.6f,%.6f";

  private final BotProperties botProperties;

  @Override
  public LocationInfo coordinates(String query) {
    try {
      Response response = HttpUtil.loadJson(getForwardUrl(query), Response.class);
      log.info("Got response for query={}", query);
      return response.getFirstLocation()
              .map(l -> new LocationInfo(l.lat.doubleValue(), l.lon.doubleValue(), l.label))
              .orElse(null);
    } catch (Exception e) {
      return null;
    }
  }

  @Override
  public String location(Double lat, Double lon) {
    try {
      Response response = HttpUtil.loadJson(getReverseUrl(lat, lon), Response.class);
      log.info("Got response for lat={}; lng={}", lat, lon);
      return response.getFirstLocation().map(l -> l.label).orElse(null);
    } catch (Exception e) {
      return null;
    }
  }

  private String getForwardUrl(String query) {
    return String.format(FORWARD_URL, botProperties.getPositionStackToken(), urlEncode(query));
  }

  private String getReverseUrl(Double lat, Double lon) {
    return String.format(REVERSE_URL, botProperties.getPositionStackToken(), lat, lon);
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  private static class Response {
    @JsonProperty("data")
    private List<Location> locations;

    public Optional<Location> getFirstLocation() {
      return getLocations().isEmpty() ? Optional.empty() : Optional.of(getLocations().getFirst());
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Location {
      @JsonProperty("latitude")
      private BigDecimal lat;
      @JsonProperty("longitude")
      private BigDecimal lon;
      @JsonProperty("name")
      private String name;
      @JsonProperty("locality")
      private String locality;
      @JsonProperty("region")
      private String region;
      @JsonProperty("country")
      private String country;
      @JsonProperty("label")
      private String label;
    }
  }
}
