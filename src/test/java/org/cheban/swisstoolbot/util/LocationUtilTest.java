package org.cheban.swisstoolbot.util;

import org.cheban.swisstoolbot.objects.LocationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LocationUtil.parse")
class LocationUtilTest {

  @Test
  void parsesDecimalPair() {
    LocationInfo loc = LocationUtil.parse("46.15,-6.1", null);

    assertThat(loc).isNotNull();
    assertThat(loc.lat()).isEqualTo(46.15);
    assertThat(loc.lon()).isEqualTo(-6.1);
  }

  @Test
  void parsesIntegerPair() {
    LocationInfo loc = LocationUtil.parse("46,-6", null);

    assertThat(loc.lat()).isEqualTo(46.0);
    assertThat(loc.lon()).isEqualTo(-6.0);
  }

  @Test
  void parsesPositiveLatitudeWithNegativeLongitudeAndViceVersa() {
    assertThat(LocationUtil.parse("46.15,-6.1", null).lat()).isEqualTo(46.15);
    assertThat(LocationUtil.parse("-46.15,-6.1", null).lon()).isEqualTo(-6.1);
    assertThat(LocationUtil.parse("-46.5, 6.5", null).lat()).isEqualTo(-46.5);
    assertThat(LocationUtil.parse("46.5,6.5", null).lon()).isEqualTo(6.5);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "46.15, -6.1",
      "46.15 , -6.1",
      "46.15,  -6.1",
      "46.15  ,   -6.1",
      "46.15,\t-6.1"
  })
  void acceptsWhitespaceAroundTheComma(String candidate) {
    LocationInfo loc = LocationUtil.parse(candidate, null);

    assertThat(loc).isNotNull();
    assertThat(loc.lat()).isEqualTo(46.15);
    assertThat(loc.lon()).isEqualTo(-6.1);
  }

  @Test
  @DisplayName("null provider leaves the name unset instead of failing")
  void nullProviderLeavesNameNull() {
    assertThat(LocationUtil.parse("46.15,-6.1", null).name()).isNull();
  }

  @Test
  @DisplayName("provider receives the parsed coordinates and its result becomes the name")
  void providerIsCalledWithParsedCoordinates() {
    List<Double> seen = new ArrayList<>();

    LocationInfo loc = LocationUtil.parse("46.15, -6.1", (lat, lon) -> {
      seen.add(lat);
      seen.add(lon);
      return "Riverview, NB";
    });

    assertThat(seen).containsExactly(46.15, -6.1);
    assertThat(loc.name()).isEqualTo("Riverview, NB");
  }

  @Test
  @DisplayName("provider returning null is passed through")
  void providerReturningNullLeavesNameNull() {
    assertThat(LocationUtil.parse("46.15,-6.1", (lat, lon) -> null).name()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "46.15 -6.1",          // no comma
      "46.15.-6.1",          // dot instead of comma
      "46,,-6",              // empty second number
      ",-6",                 // missing first number
      "46.15,",              // missing second number
      "46.15,-6.1,7",        // third component
      "+46.15,-6.1",         // explicit plus sign
      "46.15,abc",           // non-numeric second number
      "abc,46.15",           // non-numeric first number
      "Moncton NB",          // place name
      "46.15,-6.1extra",     // trailing junk
      "46.15,-6.1;"          // trailing punctuation
  })
  void returnsNullForAnythingThatIsNotAPair(String candidate) {
    assertThat(LocationUtil.parse(candidate, (lat, lon) -> "should not be called")).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {" 46.15,-6.1", "46.15,-6.1 ", "\t46.15,-6.1\n", "  46.15 , -6.1  "})
  @DisplayName("surrounding whitespace is trimmed internally")
  void trimsSurroundingWhitespace(String candidate) {
    LocationInfo loc = LocationUtil.parse(candidate, null);

    assertThat(loc).isNotNull();
    assertThat(loc.lat()).isEqualTo(46.15);
    assertThat(loc.lon()).isEqualTo(-6.1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"90,180", "-90,-180", "90.0, -180.0", "0,0", "89.999, 179.999", "-89.999,-179.999"})
  @DisplayName("extremes of the valid range are accepted")
  void acceptsInRangeCoordinates(String candidate) {
    assertThat(LocationUtil.parse(candidate, (lat, lon) -> "nowhere")).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"90.1,-6.1", "-90.0001,6.1", "46.15,180.1", "46.15,-180.5", "91,0", "0,181", "999,999", "-999, 999"})
  @DisplayName("out-of-range latitude / longitude are rejected")
  void rejectsOutOfRangeCoordinates(String candidate) {
    assertThat(LocationUtil.parse(candidate, (lat, lon) -> "should not be called")).isNull();
  }

  @Test
  @DisplayName("provider is never called for an out-of-range pair")
  void providerNotCalledWhenOutOfRange() {
    List<Double> seen = new ArrayList<>();

    assertThat(LocationUtil.parse("100, -200", (lat, lon) -> {
      seen.add(lat);
      return "should not be called";
    })).isNull();
    assertThat(seen).isEmpty();
  }

  @Test
  @DisplayName("null candidate returns null instead of throwing")
  void rejectsNullCandidate() {
    assertThat(LocationUtil.parse(null, null)).isNull();
  }

  @Test
  @DisplayName("empty candidate is rejected")
  void rejectsEmptyCandidate() {
    assertThat(LocationUtil.parse("", null)).isNull();
  }
}
