package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.objects.WebResult;
import org.cheban.swisstoolbot.service.WebSearchService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DuckDuckGoWebSearchService implements WebSearchService {
  private static final int PARALLEL_POOL = 8;
  private static final String SEARCH_URL = "https://duckduckgo.com/?q=%s";
  private static final String SEARCH_HTML_URL = "https://html.duckduckgo.com/html/?q=%s";
  private static final String IMAGE_SEARCH_URL = "https://duckduckgo.com/i.js?q=%s&vqd=%s";
  private static final Pattern VQD_PATTERN = Pattern.compile("vqd=([0-9-]+)&");
  private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
  public static final Map<String, String> SEARCH_HEADERS = Map.of("User-Agent", USER_AGENT, "Accept", "image/jpeg,image/png;q=0.9,*/*;q=0.8");
  public static final Map<String, String> SEARCH_IMAGES_HEADERS = Map.of("User-Agent", USER_AGENT, "Referer", "https://duckduckgo.com/", "Accept-Language", "en-US,en;q=0.9", "Accept", "application/json;q=0.9,*/*;q=0.8");

  public List<WebResult> search(String query, int num) {
    List<WebResult> results = List.of();

    try {
      String searchUrl = String.format(SEARCH_HTML_URL, HttpUtil.urlEncode(query));
      log.info("[search] {}", searchUrl);
      Document doc = Jsoup.connect(searchUrl).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.182 Safari/537.36").get();
      results = doc.select("#links .result .result__body .result__title .result__a").stream()
              .limit(num)
              .map(link -> new WebResult(link.absUrl("href"), link.text())).toList();

    } catch (Exception e) {
      log.error("Exception during Google search", e);
    }

    return results;
  }

  public List<ImageResult> searchImages(String query, int num) {
    try (ForkJoinPool fjp = new ForkJoinPool(PARALLEL_POOL)) {
      return fjp.submit(() -> searchImagesInternal(query, num)).get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static List<ImageResult> searchImagesInternal(String query, int num) {
    List<ImageResult> results = List.of();

    try {
      String searchUrl = String.format(SEARCH_URL, HttpUtil.urlEncode(query));
      String responseBody = HttpUtil.getText(searchUrl, SEARCH_HEADERS);
      if (StringUtils.isBlank(responseBody)) {
        throw new IllegalStateException("Failed to get response from DDG");
      }

      Matcher vqdMatcher = VQD_PATTERN.matcher(responseBody);
      if (!vqdMatcher.find()) {
        throw new IllegalStateException("Unable to parse vqd");
      }

      String vqd = vqdMatcher.group(1);
      DDGImageResults response =
              HttpUtil.loadJson(String.format(IMAGE_SEARCH_URL, HttpUtil.urlEncode(query), HttpUtil.urlEncode(vqd)),
                      SEARCH_IMAGES_HEADERS,
                      DDGImageResults.class);
      if (response != null && response.getResults() != null) {
        results = response.getResults().parallelStream()
                .limit(num * 2L)
                .map(r -> new ImageResult(r.getSrc(), r.getUrl(), r.getTitle()))
                .filter(im -> HttpUtil.imgExistsForTg(im.src()))
                .limit(num)
                .toList();
      }

      log.info("[searchImages] build results");
    } catch (Exception e) {
      log.error("Exception during DDG image search", e);
    }

    return results;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  @Data
  private static class DDGImageResults {
    @JsonProperty("results")
    private List<DDGImageResults.DDGImageResult> results;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    public static class DDGImageResult {
      @JsonProperty("image")
      private String src;
      @JsonProperty("url")
      private String url;
      @JsonProperty("title")
      private String title;
    }
  }
}
