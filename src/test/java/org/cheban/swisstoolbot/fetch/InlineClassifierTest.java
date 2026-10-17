package org.cheban.swisstoolbot.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InlineClassifier")
class InlineClassifierTest {
  private static final String TITLE = "Page Title";
  private static final String SOURCE_URL = "https://example.com/page";
  private static final String SEPARATOR = "\n\n";

  private final TelegramRenderer renderer = new TelegramRenderer();
  private final InlineClassifier classifier = new InlineClassifier(renderer);

  @Test
  @DisplayName("accepts a small article without images, tables or deep lists")
  void acceptsSmallArticle() {
    WebDocument document = document(
        new Block.Heading(2, List.of(text("Intro"))),
        paragraph(text("A short paragraph.")),
        new Block.ListBlock(false, List.of(item(paragraph(text("One"))), item(paragraph(text("Two"))))));

    assertThat(classifier.fitsInline(document)).isTrue();
  }

  @Test
  @DisplayName("accepts a document whose rendering is at the length gate and rejects one character more")
  void renderedLengthThresholdFlipsDecision() {
    int room = InlineClassifier.MAX_RENDERED_LENGTH - renderedOverhead();
    WebDocument atLimit = document(paragraph(text("a".repeat(room))));
    WebDocument overLimit = document(paragraph(text("a".repeat(room + 1))));

    assertThat(renderer.render(atLimit)).hasSize(InlineClassifier.MAX_RENDERED_LENGTH);
    assertThat(classifier.fitsInline(atLimit)).isTrue();
    assertThat(classifier.fitsInline(overLimit)).isFalse();
  }

  @Test
  @DisplayName("measures the rendered message, so list markers and separators count against the gate")
  void countsRenderedMarkupAgainstTheLengthGate() {
    int items = 280;
    int itemLength = 12;
    WebDocument document = listDocument(items, itemLength);

    assertThat(TITLE.length() + items * itemLength + SOURCE_URL.length())
        .as("the plain text projection stays within the old length budget")
        .isLessThanOrEqualTo(InlineClassifier.MAX_RENDERED_LENGTH);
    assertThat(renderer.render(document).length())
        .as("the rendered message would be rejected by Telegram")
        .isGreaterThan(4096);
    assertThat(classifier.fitsInline(document)).isFalse();
  }

  @Test
  @DisplayName("accepts the largest list that still renders within the gate and rejects one item more")
  void listBoundaryFlipsDecision() {
    WebDocument accepted = listDocument(227, 12);
    WebDocument rejected = listDocument(228, 12);

    assertThat(renderer.render(accepted)).hasSizeLessThanOrEqualTo(4096);
    assertThat(classifier.fitsInline(accepted)).isTrue();
    assertThat(classifier.fitsInline(rejected)).isFalse();
  }

  @Test
  @DisplayName("rejects a heading-heavy document that would exceed Telegram's entity limit")
  void entityThresholdFlipsDecision() {
    assertThat(classifier.fitsInline(headingsDocument(InlineClassifier.MAX_ENTITIES - 3))).isTrue();
    assertThat(classifier.fitsInline(headingsDocument(InlineClassifier.MAX_ENTITIES - 2))).isFalse();
  }

  @Test
  @DisplayName("counts every entity-producing tag, including the title and the source URL footer")
  void countsEntityProducingTags() {
    assertThat(InlineClassifier.entityCount(renderer.render(document(paragraph(text("plain"))))))
        .as("title bold + source URL link")
        .isEqualTo(2);

    String rich = renderer.render(document(
        new Block.Heading(2, List.of(text("H"))),
        new Block.Quote(List.of(paragraph(new Inline.Bold(List.of(text("b")))))),
        new Block.Pre("code", "java")));

    assertThat(InlineClassifier.entityCount(rich))
        .as("title bold, heading bold, blockquote, nested bold, pre and code, source URL link")
        .isEqualTo(7);
  }

  @Test
  @DisplayName("rejects a document holding an image")
  void rejectsImages() {
    assertThat(classifier.fitsInline(document(new Block.Image(SOURCE_URL + "/img.png", "Alt")))).isFalse();
  }

  @Test
  @DisplayName("rejects a document holding a table")
  void rejectsTables() {
    assertThat(classifier.fitsInline(document(new Block.Table(List.of(List.of("a", "b")))))).isFalse();
  }

  @Test
  @DisplayName("rejects an image nested inside a list item or a quote")
  void rejectsNestedImages() {
    WebDocument inList = document(new Block.ListBlock(false,
        List.of(item(paragraph(text("Item")), new Block.Image(SOURCE_URL + "/img.png", "Alt")))));
    WebDocument inQuote = document(new Block.Quote(
        List.of(new Block.Image(SOURCE_URL + "/img.png", "Alt"))));

    assertThat(classifier.fitsInline(inList)).isFalse();
    assertThat(classifier.fitsInline(inQuote)).isFalse();
  }

  @Test
  @DisplayName("accepts lists nested once and rejects lists nested twice")
  void listDepthThresholdFlipsDecision() {
    WebDocument nestedOnce = document(new Block.ListBlock(false, List.of(item(
        paragraph(text("Top")),
        new Block.ListBlock(false, List.of(item(paragraph(text("Nested")))))))));
    WebDocument nestedTwice = document(new Block.ListBlock(false, List.of(item(
        paragraph(text("Top")),
        new Block.ListBlock(false, List.of(item(
            paragraph(text("Nested")),
            new Block.ListBlock(false, List.of(item(paragraph(text("Deepest"))))))))))));

    assertThat(PlainText.maxListDepth(nestedOnce.blocks())).isEqualTo(2);
    assertThat(PlainText.maxListDepth(nestedTwice.blocks())).isEqualTo(3);
    assertThat(classifier.fitsInline(nestedOnce)).isTrue();
    assertThat(classifier.fitsInline(nestedTwice)).isFalse();
  }

  /** Characters the renderer adds around a single paragraph: title, separators and the footer. */
  private int renderedOverhead() {
    return renderer.render(new WebDocument(TITLE, SOURCE_URL, List.of())).length() + SEPARATOR.length();
  }

  private static WebDocument listDocument(int items, int itemLength) {
    List<Block.ListItem> entries = new ArrayList<>();
    for (int i = 0; i < items; i++) {
      entries.add(item(paragraph(text("a".repeat(itemLength)))));
    }
    return document(new Block.ListBlock(false, entries));
  }

  private static WebDocument headingsDocument(int headings) {
    List<Block> blocks = new ArrayList<>();
    for (int i = 0; i < headings; i++) {
      blocks.add(new Block.Heading(2, List.of(text("H"))));
    }
    return new WebDocument(TITLE, SOURCE_URL, blocks);
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
