package org.cheban.swisstoolbot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import lombok.experimental.UtilityClass;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@UtilityClass
public class HttpUtil {
  private static final Logger log = LoggerFactory.getLogger(HttpUtil.class);
  public static final long CONTENT_LENGTH_LIMIT = 5242880L;
  public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
  public static final String TG_USER_AGENT = "TelegramBot (like TwitterBot)";
  public static final String ACCEPT_ALL = "image/jpeg,image/png;q=0.9,*/*;q=0.8";
  public static final String ACCEPT_JSON = "application/json;q=0.9,*/*;q=0.8";
  public static final String ACCEPT_ENCODING = "gzip, deflate, br";

  public static final Map<String, String> LOAD_JSON_HEADERS = Map.of("User-Agent", USER_AGENT, "Accept", ACCEPT_JSON);
  public static final Map<String, String> URL_EXISTS_HEADERS = Map.of("User-Agent", TG_USER_AGENT, "Accept", ACCEPT_ALL, "Accept-Encoding", ACCEPT_ENCODING);
  private static final Set<Integer> OK_STATUSES = Set.of(200, 301, 302, 303, 307, 308);
  private static final Set<String> SUPPORTED_IMAGES_TYPES = Set.of("image/bmp", "image/jpeg", "image/png", "image/gif", "image/webp");

  public static String urlEncode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  public static <T> T getContent(String url, Map<String, String> headers, Function<Reader, T> responseTransformer) {
    try {
      log.info("Making GET request: URL={}; Headers={}", url, headers);
      HttpURLConnection con = connection(url, "GET", headers);
      if (con.getResponseCode() == 200) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
          return responseTransformer.apply(in);
        }
      } else {
        log.error("Got non-200 response: {} {}", con.getResponseCode(), con.getResponseMessage());
        return null;
      }
    } catch (Exception e) {
      log.error("Exception during GET", e);
      return null;
    }
  }

  public static String getText(String url, Map<String, String> headers) {
    return getContent(url, headers, HttpUtil::readerToString);
  }

  public static <T> T loadJson(String url, Function<Reader, T> responseTransformer) {
    return getContent(url, LOAD_JSON_HEADERS, responseTransformer);
  }

  public static JsonNode loadJson(String url) {
    return loadJson(url, JsonUtil::parseJsonNode);
  }

  public static <T> T loadJson(String url, Class<T> clazz) {
    return loadJson(url, r -> JsonUtil.parseObject(r, clazz));
  }

  public static <T> T loadJson(String url, Map<String, String> headers, Class<T> clazz) {
    return getContent(url, headers, r -> JsonUtil.parseObject(r, clazz));
  }

  @SuppressWarnings("unused")
  public static <T> List<T> loadJsonList(String url, Class<T> clazz) {
    return loadJson(url, r -> JsonUtil.parseObjectList(r, clazz));
  }

  @SuppressWarnings("unused")
  public static <T> T loadJson(String url, TypeReference<T> type) {
    return loadJson(url, r -> JsonUtil.parseObjectRef(r, type));
  }

  public static boolean imgExistsForTg(String url) {
    log.info("[imgExistsForTg] {}", url);

    try {
      HttpURLConnection con = connection(url, "HEAD", URL_EXISTS_HEADERS);
      boolean ok = OK_STATUSES.contains(con.getResponseCode()) &&
              SUPPORTED_IMAGES_TYPES.contains(con.getContentType()) &&
              con.getContentLengthLong() < CONTENT_LENGTH_LIMIT;
      log.info("[imgExistsForTg] [{}] Response: {} {}; Content-Type: {} ; Content-Length: {}",
              url, con.getResponseCode(), con.getResponseMessage(), con.getContentType(), con.getContentLengthLong());
      return ok;
    } catch (Exception e) {
      log.error("[imgExistsForTg] {} Exception during url check: {}", url, e.getMessage());
      return false;
    }
  }

  private static String readerToString(Reader r) {
    try {
      return IOUtils.toString(r);
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static HttpURLConnection connection(String url, String method, Map<String, String> headers)
          throws IOException, URISyntaxException {
    HttpURLConnection con = (HttpURLConnection) (new URI(url).toURL()).openConnection();
    con.setConnectTimeout(2000);
    con.setReadTimeout(2000);
    con.setRequestMethod(method);
    if (headers != null) {
      Objects.requireNonNull(con);
      headers.forEach(con::setRequestProperty);
    }

    return con;
  }
}
