package org.cheban.swisstoolbot.util;

import lombok.experimental.UtilityClass;
import org.cheban.swisstoolbot.objects.LocationInfo;

import java.util.function.BiFunction;
import java.util.regex.Pattern;

@UtilityClass
public class LocationUtil {
  private static final Pattern PAIR = Pattern.compile("-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?");
  private static final Pattern SEPARATOR = Pattern.compile("\\s*,\\s*");
  private static final double MAX_LATITUDE = 90.0;
  private static final double MAX_LONGITUDE = 180.0;

  /** @return parsed coordinates, or {@code null} if the input is not a valid lat,lon pair. */
  public static LocationInfo parse(String candidate, BiFunction<Double, Double, String> locNameProvider) {
    if (candidate == null) return null;

    String trimmed = candidate.trim();
    if (!PAIR.matcher(trimmed).matches()) return null;

    String[] coords = SEPARATOR.split(trimmed);
    Double lat = Double.valueOf(coords[0]);
    Double lon = Double.valueOf(coords[1]);
    if (Math.abs(lat) > MAX_LATITUDE || Math.abs(lon) > MAX_LONGITUDE) return null;

    return new LocationInfo(lat, lon, locNameProvider != null ? locNameProvider.apply(lat, lon) : null);
  }
}
