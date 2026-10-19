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
  private static final String WEB_FETCH_URLS = "WEB_FETCH_URLS";

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

  /** Remembers which URL the "🌐 Fetch" button of a search-result message points at. */
  public void putWebFetchUrl(int messageId, String url) {
    this.db.<String, String>getMap(WEB_FETCH_URLS).put(webFetchKey(messageId), url);
  }

  /** Consumes the URL behind a search-result button; returns {@code null} when the button is stale. */
  public String removeWebFetchUrl(int messageId) {
    return this.db.<String, String>getMap(WEB_FETCH_URLS).remove(webFetchKey(messageId));
  }

  private String webFetchKey(int messageId) {
    return dbKey("web-fetch:" + messageId);
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
