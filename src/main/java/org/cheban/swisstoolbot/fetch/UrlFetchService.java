package org.cheban.swisstoolbot.fetch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Runs the {@code /web} pipeline: fetch → extract → render. The page is always rendered as one or
 * more Telegram HTML messages. Every failure is turned into a {@link FetchOutcome.Failure} carrying
 * the matching {@link FetchError}, so the bot only has to map one value to one message and
 * {@link #fetch(String)} never throws.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UrlFetchService {
  private final UrlFetcher urlFetcher;
  private final DocumentExtractor documentExtractor;
  private final TelegramRenderer telegramRenderer;

  public FetchOutcome fetch(String url) {
    try {
      FetchedPage page = urlFetcher.fetch(url);
      WebDocument document = documentExtractor.extract(page.body(), page.finalUrl());
      return new FetchOutcome.Inline(telegramRenderer.render(document));
    } catch (FetchException e) {
      log.info("[web] {} failed: {} ({})", url, e.error(), e.getMessage());
      return new FetchOutcome.Failure(e.error());
    } catch (RuntimeException e) {
      log.warn("[web] Unexpected failure for {}", url, e);
      return new FetchOutcome.Failure(FetchError.FETCH_FAILED);
    }
  }
}
