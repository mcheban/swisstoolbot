package org.cheban.swisstoolbot.fetch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * Runs the {@code /web} pipeline: fetch → extract → classify → render. A simple page comes back as
 * inline HTML; a rich one is published to telegra.ph. Every failure is turned into a
 * {@link FetchOutcome.Failure} carrying the matching {@link FetchError}, so the bot only has to map
 * one value to one message and {@link #fetch(String)} never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlFetchService {
  /** Author shown on generated telegra.ph pages when the source URL offers no usable host. */
  static final String DEFAULT_AUTHOR = "swisstoolbot";

  private final UrlFetcher urlFetcher;
  private final DocumentExtractor documentExtractor;
  private final InlineClassifier inlineClassifier;
  private final TelegramRenderer telegramRenderer;
  private final TelegraphRenderer telegraphRenderer;

  public FetchOutcome fetch(String url) {
    try {
      FetchedPage page = urlFetcher.fetch(url);
      WebDocument document = documentExtractor.extract(page.body(), page.finalUrl());
      if (inlineClassifier.fitsInline(document)) {
        return new FetchOutcome.Inline(telegramRenderer.render(document));
      }
      log.info("[web] {} is too rich for an inline reply; publishing to telegra.ph", page.finalUrl());
      return new FetchOutcome.Telegraph(telegraphRenderer.publish(document, authorOf(document)));
    } catch (FetchException e) {
      log.info("[web] {} failed: {}", url, e.error());
      return new FetchOutcome.Failure(e.error());
    } catch (RuntimeException e) {
      log.warn("[web] Unexpected failure for {}", url, e);
      return new FetchOutcome.Failure(FetchError.FETCH_FAILED);
    }
  }

  /** Names the telegra.ph page after the site the article came from, so readers see its origin. */
  private static String authorOf(WebDocument document) {
    String source = document.sourceUrl();
    if (source == null || source.isBlank()) {
      return DEFAULT_AUTHOR;
    }
    try {
      String host = URI.create(source).getHost();
      return host == null || host.isBlank() ? DEFAULT_AUTHOR : host;
    } catch (IllegalArgumentException e) {
      return DEFAULT_AUTHOR;
    }
  }
}
