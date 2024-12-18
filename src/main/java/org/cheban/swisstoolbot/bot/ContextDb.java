package org.cheban.swisstoolbot.bot;

import lombok.RequiredArgsConstructor;
import org.telegram.abilitybots.api.db.DBContext;

import java.util.Objects;
import java.util.Optional;

@RequiredArgsConstructor
public class ContextDb {
  private static final String LOCATION_LAT = "LOCATION_LAT";
  private static final String LOCATION_LON = "LOCATION_LON";
  private static final String LOCATION_NAME = "LOCATION_NAME";
  private static final String LOCATION_KB_VISIBLE = "LOCATION_KB_VISIBLE";
  private static final String SEARCH_NUM = "SEARCH_NUM";
  private static final int SEARCH_DEFAULT = 5;
  private static final String SEARCH_NUM_IMG = "SEARCH_NUM_IMG";
  private static final int SEARCH_DEFAULT_IMG = 10;

  private final DBContext db;
  private final Long chatId;

  public void updateLocationData(Double lat, Double lon, String locationName) {
    setLocationLatitude(lat);
    setLocationLongitude(lon);
    setLocationName(locationName);
  }

  public void setLocationLatitude(Double lat) {
    setVar(LOCATION_LAT, lat);
  }

  public Double getLocationLatitude() {
    return getVar(LOCATION_LAT);
  }

  public void setLocationLongitude(Double lon) {
    setVar(LOCATION_LON, lon);
  }

  public Double getLocationLongitude() {
    return getVar(LOCATION_LON);
  }

  public void setLocationName(String name) {
    setVar(LOCATION_NAME, name);
  }

  public String getLocationName() {
    return getVar(LOCATION_NAME);
  }

  public void setLocationKeyboardVisible(boolean visible) {
    setVar(LOCATION_KB_VISIBLE, visible);
  }

  public boolean isLocationKeyboardVisible() {
    return Objects.equals(getVar(LOCATION_KB_VISIBLE), true);
  }

  public void setSearchNum(int num) {
    setVar(SEARCH_NUM, num);
  }

  public int getSearchNum() {
    return Optional.<Integer>ofNullable(getVar(SEARCH_NUM)).orElse(SEARCH_DEFAULT);
  }

  public void setSearchNumImg(int num) {
    setVar(SEARCH_NUM_IMG, num);
  }

  public int getSearchNumImg() {
    return Optional.<Integer>ofNullable(getVar(SEARCH_NUM_IMG)).orElse(SEARCH_DEFAULT_IMG);
  }

  public <T> T getVar(String name) {
    return db.<T>getVar(dbKey(name)).get();
  }

  public <T> void setVar(String name, T value) {
    this.db.getVar(this.dbKey(name)).set(value);
  }

  private String dbKey(String name) {
    return chatId + ":" + name;
  }
}