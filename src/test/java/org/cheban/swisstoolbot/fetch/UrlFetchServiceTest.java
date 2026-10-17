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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UrlFetchService")
class UrlFetchServiceTest {
  private static final String URL = "https://example.com/article";
  private static final String TELEGRAPH_URL = "https://telegra.ph/Article-10-17";

  @Mock
  private UrlFetcher urlFetcher;
  @Mock
  private DocumentExtractor documentExtractor;
  @Mock
  private InlineClassifier inlineClassifier;
  @Mock
  private TelegramRenderer telegramRenderer;
  @Mock
  private TelegraphRenderer telegraphRenderer;
  @InjectMocks
  private UrlFetchService service;

  private final WebDocument document =
      new WebDocument("Title", URL, List.of(new Block.Paragraph(List.of(new Inline.Text("Body")))));

  @Test
  @DisplayName("returns the rendered HTML when the document fits an inline reply")
  void returnsInlineHtmlWhenDocumentFits() {
    when(urlFetcher.fetch(URL)).thenReturn(new FetchedPage(URL, "<html></html>"));
    when(documentExtractor.extract("<html></html>", URL)).thenReturn(document);
    when(inlineClassifier.fitsInline(document)).thenReturn(true);
    when(telegramRenderer.render(document)).thenReturn("<b>Title</b>");

    assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Inline("<b>Title</b>"));
    verifyNoInteractions(telegraphRenderer);
  }

  @Test
  @DisplayName("publishes to telegra.ph when the document does not fit an inline reply")
  void publishesToTelegraphWhenDocumentIsTooRich() {
    when(urlFetcher.fetch(URL)).thenReturn(new FetchedPage(URL, "<html></html>"));
    when(documentExtractor.extract("<html></html>", URL)).thenReturn(document);
    when(inlineClassifier.fitsInline(document)).thenReturn(false);
    when(telegraphRenderer.publish(document, "example.com")).thenReturn(TELEGRAPH_URL);

    assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Telegraph(TELEGRAPH_URL));
    verify(telegramRenderer, never()).render(any());
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
  @DisplayName("maps a Telegraph failure (client error or atomic over-cap node) to TELEGRAPH_FAILED")
  void mapsTelegraphFailureToFailure() {
    when(urlFetcher.fetch(URL)).thenReturn(new FetchedPage(URL, "<html></html>"));
    when(documentExtractor.extract("<html></html>", URL)).thenReturn(document);
    when(inlineClassifier.fitsInline(document)).thenReturn(false);
    doThrow(new FetchException(FetchError.TELEGRAPH_FAILED)).when(telegraphRenderer).publish(document, "example.com");

    assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Failure(FetchError.TELEGRAPH_FAILED));
  }

  @Test
  @DisplayName("maps an unexpected runtime failure to FETCH_FAILED")
  void mapsUnexpectedFailureToFetchFailed() {
    when(urlFetcher.fetch(URL)).thenReturn(new FetchedPage(URL, "<html></html>"));
    when(documentExtractor.extract(any(), any())).thenThrow(new IllegalStateException("boom"));

    assertThat(service.fetch(URL)).isEqualTo(new FetchOutcome.Failure(FetchError.FETCH_FAILED));
  }
}
