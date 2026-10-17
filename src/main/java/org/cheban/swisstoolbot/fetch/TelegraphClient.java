package org.cheban.swisstoolbot.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.cheban.swisstoolbot.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper around the telegra.ph API. Only {@code createPage} is used: the account token is
 * created once out-of-band and read from {@link BotProperties}, so the bot never calls
 * {@code createAccount} at runtime.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegraphClient {
  static final String CREATE_PAGE_URL = "https://api.telegra.ph/createPage";
  private static final String PAGE_BASE_URL = "https://telegra.ph/";

  private final BotProperties botProperties;

  /**
   * Publishes one page and returns its public URL.
   *
   * @throws FetchException with {@code TELEGRAPH_FAILED} when the API rejects the request or answers
   *                        without a page URL
   */
  public String createPage(String title, String author, List<Object> content) {
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("access_token", botProperties.getTelegraphToken());
    request.put("title", title);
    request.put("author_name", author);
    request.put("content", content);
    request.put("return_content", false);

    String response = HttpUtil.postJson(apiUrl(), JsonUtil.serializeObject(request));
    String url = pageUrl(parse(response));
    log.info("[telegraph] Published \"{}\" at {}", title, url);
    return url;
  }

  /** Overridable so tests can point the client at a local stub without touching the real API. */
  String apiUrl() {
    return CREATE_PAGE_URL;
  }

  private static String pageUrl(JsonNode response) {
    if (!response.path("ok").asBoolean(false)) {
      throw new FetchException(FetchError.TELEGRAPH_FAILED, "telegra.ph rejected the request");
    }
    JsonNode result = response.path("result");
    String url = result.path("url").asText("");
    if (!url.isBlank()) {
      return url;
    }
    String path = result.path("path").asText("");
    if (!path.isBlank()) {
      return PAGE_BASE_URL + path;
    }
    throw new FetchException(FetchError.TELEGRAPH_FAILED, "telegra.ph response has no page URL");
  }

  private static JsonNode parse(String response) {
    if (response == null || response.isBlank()) {
      throw new FetchException(FetchError.TELEGRAPH_FAILED, "Empty response from telegra.ph");
    }
    try {
      return JsonUtil.parseJsonNode(response);
    } catch (RuntimeException e) {
      throw new FetchException(FetchError.TELEGRAPH_FAILED, "Malformed response from telegra.ph");
    }
  }
}
