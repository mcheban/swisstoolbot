package org.cheban.swisstoolbot.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlFetchService")
class UrlFetchServiceTest {
  private static final String URL = "https://example.com/article";
  private static final List<String> MESSAGES = List.of("<b>Title</b>");

  @Mock
  private UrlFetcher urlFetcher;
  @Mock
  private DocumentExtractor documentExtractor;
  @Mock
  private TelegramRenderer telegramRenderer;
  @InjectMocks
  private UrlFetchService service;

  private final WebDocument document =
      new WebDocument("Title", URL, List.of(new Block.Paragraph(List.of(new Inline.Text("Body")))));

  @Test
  @DisplayName("returns the rendered messages when the page was extracted successfully")
  void returnsRenderedMessages() {
    when(urlFetcher.fetch(URL)).thenReturn(new FetchedPage(URL, "<html></html>"));
    when(documentExtractor.extract("<html></html>", URL)).thenReturn(document);
    when(telegramRenderer.render(document)).thenReturn(MESSAGES);

    assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Inline(MESSAGES));
  }

  @Test
  @DisplayName("maps every FetchException to a Failure carrying its error")
  void mapsFetchExceptionToFailure() {
    for (FetchError error : FetchError.values()) {
      doThrow(new FetchException(error, "boom")).when(urlFetcher).fetch(URL);

      assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Failure(error));
    }
  }

  @Test
  @DisplayName("maps an unexpected runtime failure to FETCH_FAILED")
  void mapsUnexpectedFailureToFetchFailed() {
    when(urlFetcher.fetch(URL)).thenReturn(new FetchedPage(URL, "<html></html>"));
    when(documentExtractor.extract(any(), any())).thenThrow(new IllegalStateException("boom"));

    assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Failure(FetchError.FETCH_FAILED));
  }
}
