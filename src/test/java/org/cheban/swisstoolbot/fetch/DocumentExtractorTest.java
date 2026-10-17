package org.cheban.swisstoolbot.fetch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DocumentExtractor")
class DocumentExtractorTest {
  private static final String SOURCE_URL = "https://example.com/page";

  private final DocumentExtractor extractor = new DocumentExtractor();
  private final DocumentExtractor fallbackExtractor = new FallbackExtractor();
  private final DocumentExtractor classStrippingExtractor = new ClassStrippingExtractor();

  @Test
  @DisplayName("extracts every block and inline type from a rich article")
  void extractsEveryBlockAndInlineType() {
    WebDocument document = fallbackExtractor.extract(fixture("article-all-blocks.html"), SOURCE_URL);

    assertThat(document.title()).isEqualTo("All blocks fixture");
    assertThat(document.sourceUrl()).isEqualTo(SOURCE_URL);

    List<Block> blocks = document.blocks();
    assertThat(blocks).hasSize(12);

    assertThat(blocks.get(0)).isInstanceOfSatisfying(Block.Heading.class, heading -> {
      assertThat(heading.level()).isEqualTo(2);
      assertThat(inlineText(heading.inlines())).isEqualTo("First heading");
    });

    assertThat(blocks.get(1)).isInstanceOfSatisfying(Block.Paragraph.class, paragraph -> {
      assertThat(inlineText(paragraph.inlines())).isEqualTo(
          "Plain paragraph with bold, italic, inline(), a relative link and an absolute link.");
      assertThat(paragraph.inlines()).contains(
          new Inline.Bold(List.of(new Inline.Text("bold"))),
          new Inline.Italic(List.of(new Inline.Text("italic"))),
          new Inline.Code("inline()"),
          new Inline.Link("https://example.com/relative/path", List.of(new Inline.Text("relative link"))),
          new Inline.Link("https://example.com/abs", List.of(new Inline.Text("absolute link"))));
    });

    assertThat(blocks.get(2)).isInstanceOfSatisfying(Block.Paragraph.class,
        paragraph -> assertThat(paragraph.inlines()).contains(new Inline.LineBreak()));

    assertThat(blocks.get(3)).isInstanceOfSatisfying(Block.ListBlock.class, list -> {
      assertThat(list.ordered()).isFalse();
      assertThat(list.items()).hasSize(2);
      assertThat(textOf(list.items().get(0).blocks())).isEqualTo("First item");
      assertThat(list.items().get(1).blocks()).first().isInstanceOfSatisfying(Block.Paragraph.class,
          paragraph -> assertThat(paragraph.inlines()).contains(new Inline.Bold(List.of(new Inline.Text("bold")))));
      assertThat(list.items().get(1).blocks()).last().isInstanceOfSatisfying(Block.ListBlock.class, nested -> {
        assertThat(nested.ordered()).isFalse();
        assertThat(textOf(nested.items().get(0).blocks())).isEqualTo("Nested item");
      });
    });

    assertThat(blocks.get(4)).isInstanceOfSatisfying(Block.ListBlock.class, list -> {
      assertThat(list.ordered()).isTrue();
      assertThat(list.items()).extracting(item -> textOf(item.blocks()))
          .containsExactly("Ordered one", "Ordered two");
    });

    assertThat(blocks.get(5)).isInstanceOfSatisfying(Block.Quote.class,
        quote -> assertThat(textOf(quote.blocks())).isEqualTo("Quoted paragraph."));

    assertThat(blocks.get(6)).isEqualTo(new Block.Pre("int x = 1;", "java"));

    assertThat(blocks.get(7)).isEqualTo(new Block.Image("https://example.com/img/relative.png", "Relative image"));
    assertThat(blocks.get(8)).isInstanceOfSatisfying(Block.Paragraph.class,
        paragraph -> assertThat(inlineText(paragraph.inlines())).isEqualTo("A caption"));
    assertThat(blocks.get(9)).isEqualTo(new Block.Image("https://cdn.example.com/absolute.jpg", "Absolute image"));

    assertThat(blocks.get(10)).isEqualTo(new Block.Table(List.of(
        List.of("Name", "Value"),
        List.of("alpha", "1"),
        List.of("beta", "2"))));

    assertThat(blocks.get(11)).isInstanceOfSatisfying(Block.Paragraph.class,
        paragraph -> assertThat(inlineText(paragraph.inlines())).isEqualTo("Wrapped text in layout containers."));
  }

  @Test
  @DisplayName("removes script, nav, header, footer and other non-content elements")
  void removesClutter() {
    WebDocument document = fallbackExtractor.extract(fixture("article-all-blocks.html"), SOURCE_URL);

    String text = textOf(document.blocks());
    assertThat(text).doesNotContain("must be removed")
        .doesNotContain("aside clutter").doesNotContain("form clutter").doesNotContain("iframe fallback")
        .doesNotContain("button clutter").doesNotContain("option clutter").doesNotContain("template clutter")
        .doesNotContain("svg clutter").doesNotContain("math clutter").doesNotContain("canvas fallback")
        .doesNotContain("video fallback").doesNotContain("audio fallback");
    // The clutter must have been removed, not merely replaced by extra blocks.
    assertThat(document.blocks()).hasSize(12);
  }

  @Test
  @DisplayName("unwraps links with unsupported schemes to plain text")
  void unwrapsUnsupportedLinkSchemes() {
    String html = "<html><body><article>"
        + "<p>Ada <a href=\"javascript:void(0)\">invented</a> the "
        + "<a href=\"data:text/html,x\">engine</a> and can be reached by "
        + "<a href=\"mailto:ada@example.com\">mail</a>.</p>"
        + "</article></body></html>";

    WebDocument document = fallbackExtractor.extract(html, SOURCE_URL);

    List<Inline> inlines = ((Block.Paragraph) document.blocks().get(0)).inlines();
    assertThat(inlines).filteredOn(Inline.Link.class::isInstance)
        .extracting(inline -> ((Inline.Link) inline).href())
        .containsExactly("mailto:ada@example.com");
    assertThat(inlineText(inlines)).isEqualTo("Ada invented the engine and can be reached by mail.");
  }

  @Test
  @DisplayName("re-collapses whitespace at inline boundaries")
  void collapsesWhitespaceAtInlineBoundaries() {
    String html = "<html><body><article>"
        + "<p>Hello <span> </span> there</p>"
        + "<p>a <b> bold </b> c</p>"
        + "<p>x <a href=\"/l\"> link </a> y</p>"
        + "</article></body></html>";

    List<Block> blocks = fallbackExtractor.extract(html, SOURCE_URL).blocks();

    assertThat(inlineText(((Block.Paragraph) blocks.get(0)).inlines())).isEqualTo("Hello there");
    assertThat(inlineText(((Block.Paragraph) blocks.get(1)).inlines())).isEqualTo("a bold c");
    assertThat(inlineText(((Block.Paragraph) blocks.get(2)).inlines())).isEqualTo("x link y");
  }

  @Test
  @DisplayName("keeps a single word boundary across adjoining inlines")
  void keepsSingleSpaceBetweenAdjoiningInlines() {
    String html = "<html><body><article><p><b>red</b> <i>green</i> blue</p></article></body></html>";

    WebDocument document = fallbackExtractor.extract(html, SOURCE_URL);

    assertThat(inlineText(((Block.Paragraph) document.blocks().get(0)).inlines()))
        .isEqualTo("red green blue");
  }

  @Test
  @DisplayName("recovers the <pre> language from the original document when the content lost its classes")
  void recoversCodeLanguageWhenClassesAreStripped() {
    WebDocument stripped = classStrippingExtractor.extract(fixture("article-all-blocks.html"), SOURCE_URL);
    assertThat(stripped.blocks()).contains(new Block.Pre("int x = 1;", "java"));

    // The real Readability4J path strips the class too, so it must recover the same language.
    WebDocument readable = extractor.extract(fixture("article-all-blocks.html"), SOURCE_URL);
    assertThat(readable.blocks()).contains(new Block.Pre("int x = 1;", "java"));
  }

  @Test
  @DisplayName("a paywall or JS-only page is reported as EMPTY_CONTENT")
  void reportsPaywallAsEmptyContent() {
    assertThatThrownBy(() -> extractor.extract(fixture("paywall.html"), SOURCE_URL))
        .isInstanceOf(FetchException.class)
        .extracting(e -> ((FetchException) e).error())
        .isEqualTo(FetchError.EMPTY_CONTENT);

    assertThatThrownBy(() -> fallbackExtractor.extract(fixture("paywall.html"), SOURCE_URL))
        .isInstanceOf(FetchException.class)
        .extracting(e -> ((FetchException) e).error())
        .isEqualTo(FetchError.EMPTY_CONTENT);
  }

  @Test
  @DisplayName("a content-less page is reported as EMPTY_CONTENT even when og:title differs from <title>")
  void reportsPaywallAsEmptyContentWhenOgTitleDiffers() {
    // Readability4J echoes the <title> text, which is not the title the extractor would resolve
    // (og:title wins there), so the guard has to know every title candidate.
    assertThatThrownBy(() -> extractor.extract(fixture("paywall-og-title.html"), SOURCE_URL))
        .isInstanceOf(FetchException.class)
        .extracting(e -> ((FetchException) e).error())
        .isEqualTo(FetchError.EMPTY_CONTENT);

    assertThatThrownBy(() -> fallbackExtractor.extract(fixture("paywall-og-title.html"), SOURCE_URL))
        .isInstanceOf(FetchException.class)
        .extracting(e -> ((FetchException) e).error())
        .isEqualTo(FetchError.EMPTY_CONTENT);
  }

  @Test
  @DisplayName("a short but genuine article is not treated as empty")
  void keepsShortButRealArticle() {
    String html = "<html><head><title>Council meeting delayed</title></head>"
        + "<body><article><h1>Council meeting delayed</h1>"
        + "<p>The council meeting was delayed until Thursday.</p></article></body></html>";

    WebDocument document = fallbackExtractor.extract(html, SOURCE_URL);

    assertThat(textOf(document.blocks())).contains("delayed until Thursday");
  }

  @Test
  @DisplayName("an image-only page is not treated as empty")
  void keepsImageOnlyPage() {
    String html = "<html><head><title>Infographic</title></head>"
        + "<body><article><img src=\"/info.png\" alt=\"\"></article></body></html>";

    WebDocument document = fallbackExtractor.extract(html, SOURCE_URL);

    assertThat(document.blocks()).containsExactly(new Block.Image("https://example.com/info.png", ""));
  }

  @Test
  @DisplayName("Readability4J is used as the primary extractor")
  void usesReadability4JFirst() {
    RecordingExtractor recording = new RecordingExtractor();

    WebDocument document = recording.extract(fixture("readability-article.html"), SOURCE_URL);

    assertThat(recording.content()).isNotNull();
    assertThat(textOf(document.blocks())).contains("quick brown fox jumps over the lazy dog");
  }

  @Test
  @DisplayName("falls back to the Jsoup heuristic when Readability4J finds nothing")
  void fallsBackToJsoupHeuristic() {
    WebDocument document = fallbackExtractor.extract(fixture("readability-article.html"), SOURCE_URL);

    assertThat(textOf(document.blocks())).contains("quick brown fox jumps over the lazy dog");
  }

  @Test
  @DisplayName("the Jsoup heuristic prefers main over the largest block")
  void prefersMainContainer() {
    String html = "<html><body>"
        + "<div><p>sidebar with a fairly long sentence to tempt the largest-block rule.</p></div>"
        + "<main><p>the real main content</p></main>"
        + "</body></html>";

    WebDocument document = fallbackExtractor.extract(html, SOURCE_URL);

    assertThat(textOf(document.blocks())).contains("the real main content")
        .doesNotContain("sidebar");
  }

  @Test
  @DisplayName("an empty extraction is reported as EMPTY_CONTENT")
  void reportsEmptyContent() {
    assertThatThrownBy(() -> fallbackExtractor.extract(fixture("empty-content.html"), SOURCE_URL))
        .isInstanceOf(FetchException.class)
        .extracting(e -> ((FetchException) e).error())
        .isEqualTo(FetchError.EMPTY_CONTENT);

    assertThatThrownBy(() -> extractor.extract(fixture("empty-content.html"), SOURCE_URL))
        .isInstanceOf(FetchException.class)
        .extracting(e -> ((FetchException) e).error())
        .isEqualTo(FetchError.EMPTY_CONTENT);
  }

  @Test
  @DisplayName("og:title wins over the <title> tag")
  void prefersOpenGraphTitle() {
    assertThat(extractor.extract(fixture("article-all-blocks.html"), SOURCE_URL).title())
        .isEqualTo("All blocks fixture");
  }

  @Test
  void fallsBackToTheTitleTag() {
    String html = "<html><head><title>  Document    title </title></head>"
        + "<body><article><p>content</p></article></body></html>";

    assertThat(fallbackExtractor.extract(html, SOURCE_URL).title()).isEqualTo("Document title");
  }

  @Test
  void fallsBackToTheFirstHeading() {
    String html = "<html><body><article><h1>Only heading</h1><p>content</p></article></body></html>";

    assertThat(fallbackExtractor.extract(html, SOURCE_URL).title()).isEqualTo("Only heading");
  }

  @Test
  void truncatesLongTitles() {
    String title = "a".repeat(300);
    String html = "<html><head><title>" + title + "</title></head>"
        + "<body><article><p>content</p></article></body></html>";

    assertThat(fallbackExtractor.extract(html, SOURCE_URL).title()).isEqualTo("a".repeat(256));
  }

  @Test
  @DisplayName("collapses whitespace and drops empty nodes")
  void collapsesWhitespaceAndDropsEmptyNodes() {
    String html = "<html><body><article>"
        + "<p>   spaced     out    words  </p>"
        + "<p>   </p>"
        + "<span>   </span>"
        + "<p>Second</p>"
        + "</article></body></html>";

    WebDocument document = fallbackExtractor.extract(html, SOURCE_URL);

    assertThat(document.blocks()).containsExactly(
        new Block.Paragraph(List.of(new Inline.Text("spaced out words"))),
        new Block.Paragraph(List.of(new Inline.Text("Second"))));
  }

  private static String fixture(String name) {
    try (InputStream stream = DocumentExtractorTest.class.getResourceAsStream("/fixtures/" + name)) {
      if (stream == null) {
        throw new IllegalArgumentException("Missing fixture: " + name);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String textOf(List<Block> blocks) {
    return blocks.stream().map(DocumentExtractorTest::textOf).collect(Collectors.joining("\n"));
  }

  private static String textOf(Block block) {
    if (block instanceof Block.Heading heading) {
      return inlineText(heading.inlines());
    }
    if (block instanceof Block.Paragraph paragraph) {
      return inlineText(paragraph.inlines());
    }
    if (block instanceof Block.ListBlock list) {
      return list.items().stream().map(item -> textOf(item.blocks())).collect(Collectors.joining("\n"));
    }
    if (block instanceof Block.Quote quote) {
      return textOf(quote.blocks());
    }
    if (block instanceof Block.Pre pre) {
      return pre.text();
    }
    if (block instanceof Block.Image image) {
      return image.alt();
    }
    if (block instanceof Block.Table table) {
      return table.rows().stream().map(row -> String.join(" ", row)).collect(Collectors.joining("\n"));
    }
    return "";
  }

  private static String inlineText(List<Inline> inlines) {
    return inlines.stream().map(DocumentExtractorTest::textOf).collect(Collectors.joining());
  }

  private static String textOf(Inline inline) {
    if (inline instanceof Inline.Text text) {
      return text.text();
    }
    if (inline instanceof Inline.Code code) {
      return code.text();
    }
    if (inline instanceof Inline.Bold bold) {
      return inlineText(bold.inlines());
    }
    if (inline instanceof Inline.Italic italic) {
      return inlineText(italic.inlines());
    }
    if (inline instanceof Inline.Link link) {
      return inlineText(link.inlines());
    }
    if (inline instanceof Inline.LineBreak) {
      return "\n";
    }
    return "";
  }

  /** Skips Readability4J so the Jsoup heuristic can be tested deterministically. */
  private static final class FallbackExtractor extends DocumentExtractor {
    @Override
    Element mainContent(String body, String finalUrl) {
      return null;
    }
  }

  /** Simulates Readability4J's class stripping while keeping the shared Jsoup walk intact. */
  private static final class ClassStrippingExtractor extends DocumentExtractor {
    @Override
    Element mainContent(String body, String finalUrl) {
      Element article = Jsoup.parse(body, finalUrl).selectFirst("article");
      if (article != null) {
        article.select("[class]").removeAttr("class");
      }
      return article;
    }
  }

  /** Records the container Readability4J produced so a test can prove the primary path ran. */
  private static final class RecordingExtractor extends DocumentExtractor {
    private Element content;

    @Override
    Element mainContent(String body, String finalUrl) {
      content = super.mainContent(body, finalUrl);
      return content;
    }

    private Element content() {
      return content;
    }
  }
}
