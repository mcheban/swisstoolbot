package org.cheban.swisstoolbot.bot;

import lombok.RequiredArgsConstructor;
import org.telegram.telegrambots.abilitybots.api.db.DBContext;

import java.util.Objects;

@RequiredArgsConstructor
public class ContextDb {
  private static final String LOCATION_LAT = "LOCATION_LAT";
  private static final String LOCATION_LON = "LOCATION_LON";
  private static final String LOCATION_NAME = "LOCATION_NAME";
  private static final String LOCATION_KB_VISIBLE = "LOCATION_KB_VISIBLE";

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

  public int getSearchNum() {
    return (Integer) ConfigOption.SEARCH_NUM.getValue(this);
  }

  public int getSearchNumImg() {
    return (Integer) ConfigOption.SEARCH_NUM_IMG.getValue(this);
  }

  public <T> T getVar(ConfigOption opt) {
    return getVar(opt.name());
  }

  public <T> void setVar(ConfigOption opt, T value) {
    setVar(opt.name(), value);
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
