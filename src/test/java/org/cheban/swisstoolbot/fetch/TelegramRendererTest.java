package org.cheban.swisstoolbot.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TelegramRenderer")
class TelegramRendererTest {
  private static final String TITLE = "Page Title";
  private static final String SOURCE_URL = "https://example.com/page";
  private static final String FOOTER = "<a href=\"" + SOURCE_URL + "\">" + SOURCE_URL + "</a>";

  private final TelegramRenderer renderer = new TelegramRenderer();

  @Test
  @DisplayName("renders the title, the body and the source URL separated by blank lines")
  void rendersTitleBodyAndSource() {
    String html = renderer.render(document(paragraph(text("Hello world."))));

    assertThat(html).isEqualTo("<b>" + TITLE + "</b>\n\nHello world.\n\n" + FOOTER);
  }

  @Test
  @DisplayName("escapes &, < and > in text, code and pre blocks")
  void escapesMarkup() {
    String html = renderer.render(document(
        paragraph(text("Tom & Jerry <b>bold</b> and 5 > 3")),
        new Block.Pre("if (a < b && c > d) {\n  x++;\n}", null)));

    assertThat(html).contains("Tom &amp; Jerry &lt;b&gt;bold&lt;/b&gt; and 5 &gt; 3")
        .contains("<pre>if (a &lt; b &amp;&amp; c &gt; d) {\n  x++;\n}</pre>")
        .doesNotContain("Tom & Jerry");
  }

  @Test
  @DisplayName("renders every heading level as a bold line followed by a blank line")
  void rendersHeadingsAsBoldLines() {
    String html = renderer.render(document(
        new Block.Heading(2, List.of(text("First heading"))),
        new Block.Heading(6, List.of(text("Deep heading"))),
        paragraph(text("Body text."))));

    assertThat(html).isEqualTo("<b>" + TITLE + "</b>\n\n<b>First heading</b>\n\n<b>Deep heading</b>\n\nBody text.\n\n" + FOOTER);
  }

  @Test
  @DisplayName("drops a leading heading that only repeats the title")
  void dropsLeadingHeadingMatchingTitle() {
    String html = renderer.render(new WebDocument(TITLE, SOURCE_URL, List.of(
        new Block.Heading(1, List.of(text(TITLE))),
        paragraph(text("Body text.")))));

    assertThat(html).isEqualTo("<b>" + TITLE + "</b>\n\nBody text.\n\n" + FOOTER);
    assertThat(html).containsOnlyOnce("<b>" + TITLE + "</b>");
  }

  @Test
  @DisplayName("keeps a leading heading that differs from the title")
  void keepsLeadingHeadingThatDiffersFromTitle() {
    String html = renderer.render(new WebDocument(TITLE, SOURCE_URL, List.of(
        new Block.Heading(1, List.of(text("Something else"))),
        paragraph(text("Body text.")))));

    assertThat(html).contains("<b>" + TITLE + "</b>\n\n<b>Something else</b>");
  }

  @Test
  @DisplayName("renders list items as bullets or ordered numbers, indenting nested lists")
  void rendersLists() {
    String html = renderer.render(document(
        new Block.ListBlock(false, List.of(
            item(paragraph(text("First item"))),
            item(paragraph(text("Second item")),
                new Block.ListBlock(false, List.of(item(paragraph(text("Nested item")))))))),
        new Block.ListBlock(true, List.of(
            item(paragraph(text("One"))),
            item(paragraph(text("Two")))))));

    assertThat(html).contains("• First item\n• Second item\n  • Nested item")
        .contains("1. One\n2. Two")
        .doesNotContain("<li", "<ul", "<ol");
  }

  @Test
  @DisplayName("renders a list item whose first block is bold as a bullet with inline markup")
  void rendersListItemsWithInlineMarkup() {
    String html = renderer.render(document(new Block.ListBlock(false, List.of(
        item(paragraph(new Inline.Bold(List.of(text("Bold item"))), text(" with tail")))))));

    assertThat(html).contains("• <b>Bold item</b> with tail");
  }

  @Test
  @DisplayName("renders quotes as blockquotes and preformatted text as pre")
  void rendersQuoteAndPre() {
    String html = renderer.render(document(
        new Block.Quote(List.of(paragraph(text("Quoted paragraph.")))),
        new Block.Quote(List.of(paragraph(text("First")), paragraph(text("Second")))),
        new Block.Pre("int x = 1;", "java"),
        new Block.Pre("plain text", null)));

    assertThat(html).contains("<blockquote>Quoted paragraph.</blockquote>")
        .contains("<blockquote>First\nSecond</blockquote>")
        .contains("<pre><code class=\"language-java\">int x = 1;</code></pre>")
        .contains("<pre>plain text</pre>");
  }

  @Test
  @DisplayName("renders links with an escaped href and falls back to the href as label")
  void rendersLinks() {
    String html = renderer.render(document(
        paragraph(new Inline.Link("https://example.com/a?x=1&y=2", List.of(text("A link")))),
        paragraph(new Inline.Link("https://example.com/empty", List.of()))));

    assertThat(html).contains("<a href=\"https://example.com/a?x=1&amp;y=2\">A link</a>")
        .contains("<a href=\"https://example.com/empty\">https://example.com/empty</a>");
  }

  @Test
  @DisplayName("renders bold, italic, code and line breaks as inline markup or newlines")
  void rendersInlineMarkup() {
    String html = renderer.render(document(paragraph(
        new Inline.Bold(List.of(text("bold"))), text(" "),
        new Inline.Italic(List.of(text("italic"))), text(" "),
        new Inline.Code("inline()"), new Inline.LineBreak(), text("after the break"))));

    assertThat(html).contains("<b>bold</b> <i>italic</i> <code>inline()</code>\nafter the break");
  }

  @Test
  @DisplayName("never emits tags Telegram cannot parse, and skips images")
  void neverEmitsUnsupportedTags() {
    String html = renderer.render(document(
        new Block.Heading(3, List.of(text("Heading"))),
        paragraph(text("Body")),
        new Block.ListBlock(false, List.of(item(paragraph(text("Item"))))),
        new Block.Quote(List.of(paragraph(text("Quote")))),
        new Block.Image("https://example.com/img.png", "Alt text")));

    assertThat(html).doesNotContain("<p>", "<p ", "</p>", "<br", "<br/", "<ul", "<ol", "<li",
        "<h1", "<h2", "<h3", "<h4", "<h5", "<h6", "<div", "<span", "<table", "<tr", "<td", "<img")
        .doesNotContain("Alt text");
  }

  @Test
  @DisplayName("flattens a table to text rows, since tables never reach an inline reply")
  void flattensTables() {
    String html = renderer.render(document(new Block.Table(List.of(
        List.of("Name", "Value"),
        List.of("alpha", "1")))));

    assertThat(html).contains("Name | Value\nalpha | 1");
  }

  @Test
  @DisplayName("sizes the message with the list markers and separators the renderer adds")
  void messageSizeIncludesListMarkersAndSeparators() {
    WebDocument document = document(
        new Block.ListBlock(false, List.of(item(paragraph(text("alpha"))), item(paragraph(text("bravo"))))),
        paragraph(text("tail")));

    String html = renderer.render(document);

    assertThat(html).isEqualTo("<b>" + TITLE + "</b>\n\n• alpha\n• bravo\n\ntail\n\n" + FOOTER);
    assertThat(html.length())
        .as("markers, separators and markup add characters beyond the visible text")
        .isGreaterThan(TITLE.length() + "alphabravotail".length() + SOURCE_URL.length());
  }

  @Test
  @DisplayName("escapes the ampersand before the other characters so entities are not double-escaped")
  void escapesAmpersandFirst() {
    assertThat(TelegramRenderer.escape("a & <b>")).isEqualTo("a &amp; &lt;b&gt;");
  }

  @Test
  @DisplayName("renders what the extractor produces from a real article without unsupported tags")
  void rendersARealExtraction() {
    WebDocument extracted = new DocumentExtractor().extract(fixture("article-all-blocks.html"), SOURCE_URL);

    String html = renderer.render(extracted);

    assertThat(html).startsWith("<b>" + extracted.title() + "</b>").endsWith(FOOTER)
        .contains("• ", "1. ", "<blockquote>", "<pre>")
        .doesNotContain("<p>", "<br", "<ul", "<ol", "<li", "<h1", "<h2", "<table", "<img");
  }

  private static String fixture(String name) {
    try (InputStream in = TelegramRendererTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static WebDocument document(Block... blocks) {
    return new WebDocument(TITLE, SOURCE_URL, List.of(blocks));
  }

  private static Block.Paragraph paragraph(Inline... inlines) {
    return new Block.Paragraph(List.of(inlines));
  }

  private static Block.ListItem item(Block... blocks) {
    return new Block.ListItem(List.of(blocks));
  }

  private static Inline.Text text(String text) {
    return new Inline.Text(text);
  }
}
