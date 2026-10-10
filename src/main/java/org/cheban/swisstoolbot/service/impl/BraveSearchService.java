package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.objects.WebResult;
import org.cheban.swisstoolbot.service.WebSearchService;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class BraveSearchService implements WebSearchService {
    private static final int PARALLEL_POOL = 8;
    private static final String SEARCH_URL = "https://api.search.brave.com/res/v1/web/search?q=%s&count=%d";
    private static final String IMAGE_SEARCH_URL = "https://api.search.brave.com/res/v1/images/search?q=%s&count=%d";

    private final BotProperties botProperties;

    public List<WebResult> search(String query, int num) {
        List<WebResult> results = List.of();

        try {
            String searchUrl = String.format(SEARCH_URL, HttpUtil.urlEncode(query), num);
            log.info("[search] {}", searchUrl);

            Map<String, String> headers = Map.of(
                    "X-Subscription-Token", botProperties.getBraveSearchApiToken(),
                    "Accept", "application/json");

            WebResults response = HttpUtil.loadJson(searchUrl, headers, WebResults.class);
            results = response.getWeb().getResults().stream()
                    .map(r -> new WebResult(r.getUrl(), r.getTitle())).toList();

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

    private List<ImageResult> searchImagesInternal(String query, int num) {
        List<ImageResult> results = List.of();

        try {
            String searchUrl = String.format(IMAGE_SEARCH_URL, HttpUtil.urlEncode(query), num * 2);
            log.info("[searchImages] {}", searchUrl);

            Map<String, String> headers = Map.of(
                    "X-Subscription-Token", botProperties.getBraveSearchApiToken(),
                    "Accept", "application/json");

            ImageResults response = HttpUtil.loadJson(searchUrl, headers, ImageResults.class);

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
    private static class ImageResults {
        @JsonProperty("results")
        private List<Result> results;

        @JsonIgnoreProperties(ignoreUnknown = true)
        @Data
        public static class Result {
            @JsonProperty("properties")
            private Properties properties;
            @JsonProperty("url")
            private String url;
            @JsonProperty("title")
            private String title;

            public String getSrc() {
                return getProperties().getUrl();
            }

            @JsonIgnoreProperties(ignoreUnknown = true)
            @Data
            public static class Properties {
                @JsonProperty("url")
                private String url;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Data
    private static class WebResults {
        @JsonProperty("web")
        private SectionResult web;

        @JsonIgnoreProperties(ignoreUnknown = true)
        @Data
        public static class SectionResult {
            @JsonProperty("results")
            private List<Result> results;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        @Data
        public static class Result {
            @JsonProperty("url")
            private String url;
            @JsonProperty("title")
            private String title;
        }
    }
}
