package org.cheban.swisstoolbot.fetch;

import org.cheban.swisstoolbot.util.JsonUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TelegraphRenderer")
class TelegraphRendererTest {
  private static final String TITLE = "Page Title";
  private static final String SOURCE_URL = "https://example.com/page";
  private static final String AUTHOR = "example.com";
  private static final long PAGE_BUDGET = TelegraphRenderer.MAX_CONTENT_BYTES - TelegraphRenderer.CHAIN_LINK_RESERVE;

  private static final Set<String> ALLOWED_TAGS = Set.of("a", "aside", "b", "blockquote", "br", "code", "em",
      "figcaption", "figure", "h3", "h4", "hr", "i", "iframe", "img", "li", "ol", "p", "pre", "s", "strong", "u",
      "ul", "video");
  private static final Set<String> ALLOWED_ATTRS = Set.of("href", "src");

  private final TelegraphClient telegraphClient = mock(TelegraphClient.class);
  private final TelegraphRenderer renderer = new TelegraphRenderer(telegraphClient);

  @Test
  @DisplayName("maps every block and inline type to a telegra.ph node")
  void mapsModelToNodes() {
    WebDocument document = document(
        new Block.Heading(1, List.of(text("Top"))),
        new Block.Heading(3, List.of(text("Deep"))),
        paragraph(new Inline.Bold(List.of(text("bold"))), text(" "),
            new Inline.Italic(List.of(text("italic"))), text(" "),
            new Inline.Code("code()"),
            new Inline.Link("https://example.com/x", List.of(text("link")))),
        new Block.Pre("int x = 1;", "java"),
        new Block.Quote(List.of(paragraph(text("quoted")))),
        new Block.Image("https://example.com/img.png", "Alt text"),
        new Block.ListBlock(true, List.of(item(paragraph(text("one"))), item(paragraph(text("two"))))));

    List<List<Object>> pages = renderer.render(document);

    assertThat(pages).hasSize(1);
    List<Object> content = pages.getFirst();
    assertThat(content).hasSize(7);
    assertThat(tag(content.get(0))).isEqualTo("h3");
    assertThat(tag(content.get(1))).isEqualTo("h4");
    List<Object> inlines = children(content.get(2));
    assertThat(tag(inlines.get(0))).isEqualTo("strong");
    assertThat(inlines.get(1)).isEqualTo(" ");
    assertThat(tag(inlines.get(2))).isEqualTo("em");
    assertThat(tag(inlines.get(4))).isEqualTo("code");
    assertThat(tag(inlines.get(5))).isEqualTo("a");
    assertThat(attrs(inlines.get(5))).containsEntry("href", "https://example.com/x");
    assertThat(tag(content.get(3))).isEqualTo("pre");
    assertThat(children(content.get(3))).containsExactly("int x = 1;");
    assertThat(tag(content.get(4))).isEqualTo("blockquote");
    assertThat(tag(content.get(5))).isEqualTo("img");
    assertThat(attrs(content.get(5))).containsEntry("src", "https://example.com/img.png");
    assertThat(content.get(5)).isInstanceOf(Map.class);
    assertThat(attrs(content.get(5))).doesNotContainKey("alt");
    assertThat(tag(content.get(6))).isEqualTo("ol");
    assertThat(tag(children(content.get(6)).getFirst())).isEqualTo("li");
  }

  @Test
  @DisplayName("renders a line break as br and preserves an empty link's href as label")
  void rendersLineBreaksAndEmptyLinks() {
    List<Object> content = renderer.render(document(paragraph(
        text("before"), new Inline.LineBreak(), new Inline.Link("https://example.com/only", List.of()))))
        .getFirst();

    List<Object> inlines = children(content.getFirst());
    assertThat(inlines.get(0)).isEqualTo("before");
    assertThat(tag(inlines.get(1))).isEqualTo("br");
    assertThat(tag(inlines.get(2))).isEqualTo("a");
    assertThat(children(inlines.get(2))).containsExactly("https://example.com/only");
  }

  @Test
  @DisplayName("only emits tags and attributes telegra.ph accepts")
  void onlyEmitsAllowedTagsAndAttributes() {
    List<Object> content = renderer.render(document(
        new Block.Heading(2, List.of(text("Heading"))),
        paragraph(new Inline.Bold(List.of(text("b"))), new Inline.Italic(List.of(text("i"))),
            new Inline.Code("c"), new Inline.Link("https://example.com/l", List.of(text("l")))),
        new Block.ListBlock(false, List.of(item(paragraph(new Inline.Bold(List.of(text("item"))))))),
        new Block.Quote(List.of(paragraph(text("quote")), new Block.Pre("pre", null))),
        new Block.Image("https://example.com/i.png", "alt"),
        new Block.Table(List.of(List.of("a", "b")))))
        .getFirst();

    content.forEach(TelegraphRendererTest::assertOnlyAllowedNodes);
  }

  @Test
  @DisplayName("flattens a table into list rows and tolerates ragged rows")
  void flattensTables() {
    List<Object> content = renderer.render(document(new Block.Table(List.of(
        List.of("Name", "Value"),
        List.of("alpha", "1", "extra"),
        List.of("beta"),
        List.of())))).getFirst();

    assertThat(content).hasSize(1);
    assertThat(tag(content.getFirst())).isEqualTo("ul");
    List<Object> rows = children(content.getFirst());
    assertThat(rows).hasSize(3);
    assertThat(children(rows.get(0))).containsExactly("Name | Value");
    assertThat(children(rows.get(1))).containsExactly("alpha | 1 | extra");
    assertThat(children(rows.get(2))).containsExactly("beta");
    assertThat(rows).allSatisfy(row -> assertThat(tag(row)).isEqualTo("li"));
  }

  @Test
  @DisplayName("keeps a nested list inside its parent list item")
  void keepsNestedLists() {
    List<Object> content = renderer.render(document(new Block.ListBlock(false, List.of(
        item(paragraph(text("parent")), new Block.ListBlock(false, List.of(item(paragraph(text("child"))))))))))
        .getFirst();

    List<Object> itemChildren = children(children(content.getFirst()).getFirst());
    assertThat(itemChildren).hasSize(2);
    assertThat(itemChildren.get(0)).isEqualTo("parent");
    assertThat(tag(itemChildren.get(1))).isEqualTo("ul");
    assertThat(children(itemChildren.get(1))).hasSize(1);
  }

  @Test
  @DisplayName("flattens quote contents into inline children separated by br")
  void flattensQuotesToInlineChildren() {
    List<Object> content = renderer.render(document(new Block.Quote(List.of(
        paragraph(text("first")),
        new Block.Pre("code text", null),
        new Block.ListBlock(false, List.of(item(paragraph(text("entry")))))))))
        .getFirst();

    assertThat(tag(content.getFirst())).isEqualTo("blockquote");
    List<Object> children = children(content.getFirst());
    assertThat(children.get(0)).isEqualTo("first");
    assertThat(tag(children.get(1))).isEqualTo("br");
    assertThat(tag(children.get(2))).isEqualTo("code");
    assertThat(tag(children.get(3))).isEqualTo("br");
    assertThat(children.get(4)).isEqualTo("• ");
    assertThat(children.get(5)).isEqualTo("entry");
  }

  @Test
  @DisplayName("renders an empty document as a single empty page")
  void rendersEmptyDocument() {
    assertThat(renderer.render(new WebDocument(TITLE, SOURCE_URL, List.of()))).containsExactly(List.of());
  }

  @Test
  @DisplayName("splits exactly at the page budget (64 KB minus the Part N → reserve): at the boundary one page, one block more two")
  void splitsAtTheExactContentBoundary() {
    long perBlock = paragraphBytes(1_000) + 1;   // one node plus the separator inside the array
    int capacity = (int) ((PAGE_BUDGET - 1) / perBlock);

    List<List<Object>> atCap = renderer.render(document(paragraphs(1_000, capacity)));
    assertThat(atCap).hasSize(1);
    assertThat(pageBytes(atCap.getFirst())).isEqualTo(1 + capacity * perBlock);
    assertThat(pageBytes(atCap.getFirst())).isLessThanOrEqualTo(PAGE_BUDGET);

    List<List<Object>> overCap = renderer.render(document(paragraphs(1_000, capacity + 1)));
    assertThat(overCap).hasSize(2);
    assertThat(overCap).allSatisfy(page -> assertThat(pageBytes(page)).isLessThanOrEqualTo(PAGE_BUDGET));
  }

  @Test
  @DisplayName("splits a long list at item boundaries without cutting an entry")
  void splitsLongListsAtItemBoundaries() {
    List<Block.ListItem> items = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      items.add(item(paragraph(text("x".repeat(400)))));
    }

    List<List<Object>> pages = renderer.render(document(new Block.ListBlock(false, items)));

    assertThat(pages).hasSizeGreaterThan(1);
    assertThat(pages).allSatisfy(page -> {
      assertThat(page).hasSize(1);
      assertThat(tag(page.getFirst())).isEqualTo("ul");
      assertThat(pageBytes(page)).isLessThanOrEqualTo(PAGE_BUDGET);
    });
    int renderedItems = pages.stream().mapToInt(page -> children(page.getFirst()).size()).sum();
    assertThat(renderedItems).isEqualTo(200);
  }

  @Test
  @DisplayName("publishes pages in reverse order so each links forward with Part N →")
  @SuppressWarnings("unchecked")
  void publishesPagesInReverseOrderWithPartLinks() {
    int capacity = (int) ((PAGE_BUDGET - 1) / (paragraphBytes(3_000) + 1));
    WebDocument document = document(paragraphs(3_000, 3 * capacity));
    List<List<Object>> pages = renderer.render(document);
    assertThat(pages).hasSize(3);

    List<String> urls = List.of("https://telegra.ph/part-3", "https://telegra.ph/part-2", "https://telegra.ph/part-1");
    AtomicInteger created = new AtomicInteger();
    when(telegraphClient.createPage(anyString(), anyString(), anyList()))
        .thenAnswer(_ -> urls.get(created.getAndIncrement()));

    String firstUrl = renderer.publish(document, AUTHOR);

    assertThat(firstUrl).isEqualTo("https://telegra.ph/part-1");
    ArgumentCaptor<List<Object>> captor = ArgumentCaptor.forClass(List.class);
    verify(telegraphClient, times(3)).createPage(eq(TITLE), eq(AUTHOR), captor.capture());
    List<List<Object>> received = captor.getAllValues();
    assertThat(received.get(0)).isEqualTo(pages.get(2));                        // last page first, no link
    assertThat(received.get(1).subList(0, pages.get(1).size())).isEqualTo(pages.get(1));
    assertThat(chainHref(received.get(1))).isEqualTo("https://telegra.ph/part-3");
    assertThat(chainText(received.get(1))).isEqualTo("Part 3 →");
    assertThat(received.get(2).subList(0, pages.getFirst().size())).isEqualTo(pages.getFirst());
    assertThat(chainHref(received.get(2))).isEqualTo("https://telegra.ph/part-2");
    assertThat(chainText(received.get(2))).isEqualTo("Part 2 →");
  }

  @Test
  @DisplayName("reserves room for the Part N → link so every published page stays under 64 KB")
  @SuppressWarnings("unchecked")
  void reservesRoomForTheChainLink() {
    int capacity = (int) ((PAGE_BUDGET - 1) / (paragraphBytes(5_000) + 1));
    WebDocument document = document(paragraphs(5_000, 2 * capacity));
    String nextUrl = "https://telegra.ph/Some-Very-Long-Page-Slug-That-Is-Still-Realistic-10-17";
    when(telegraphClient.createPage(anyString(), anyString(), anyList()))
        .thenAnswer(invocation -> {
          List<?> content = invocation.getArgument(2);
          return content.isEmpty() ? nextUrl : nextUrl + "-prev";
        });

    renderer.publish(document, AUTHOR);

    ArgumentCaptor<List<Object>> captor = ArgumentCaptor.forClass(List.class);
    verify(telegraphClient, times(2)).createPage(eq(TITLE), eq(AUTHOR), captor.capture());
    assertThat(captor.getAllValues()).allSatisfy(
        content -> assertThat(serialized(content)).isLessThanOrEqualTo(TelegraphRenderer.MAX_CONTENT_BYTES));
    assertThat(chainHref(captor.getAllValues().get(1))).startsWith("https://telegra.ph/");
  }

  @Test
  @DisplayName("splits an oversized nested list inside one item so every published page stays under 64 KB")
  void splitsOversizedNestedSubtree() {
    Block.ListBlock nested = new Block.ListBlock(false, itemsOf(400, 200));
    WebDocument document = document(new Block.ListBlock(false, List.of(item(paragraph(text("parent")), nested))));

    List<List<Object>> pages = published(renderer, telegraphClient, document);

    assertThat(pages).hasSizeGreaterThan(1);
    assertThat(pages).allSatisfy(
        page -> assertThat(serialized(page)).isLessThanOrEqualTo(TelegraphRenderer.MAX_CONTENT_BYTES));
  }

  @Test
  @DisplayName("splits an oversized table nested in one item so every published page stays under 64 KB")
  void splitsOversizedTableInsideItem() {
    List<List<String>> rows = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      rows.add(List.of("row " + i, "value ".repeat(80)));
    }
    WebDocument document = document(new Block.ListBlock(false, List.of(
        item(paragraph(text("parent")), new Block.Table(rows)))));

    List<List<Object>> pages = published(renderer, telegraphClient, document);

    assertThat(pages).hasSizeGreaterThan(1);
    assertThat(pages).allSatisfy(
        page -> assertThat(serialized(page)).isLessThanOrEqualTo(TelegraphRenderer.MAX_CONTENT_BYTES));
  }

  @Test
  @DisplayName("splits a single huge inline run so every published page stays under 64 KB")
  void splitsHugeInlineRuns() {
    String huge = "x".repeat(200_000);
    List<WebDocument> documents = List.of(
        document(paragraph(new Inline.Bold(List.of(text(huge))))),
        document(paragraph(new Inline.Code(huge))),
        document(paragraph(new Inline.Link("https://example.com/huge", List.of(text(huge))))));

    for (WebDocument document : documents) {
      TelegraphClient client = mock(TelegraphClient.class);

      List<List<Object>> pages = published(new TelegraphRenderer(client), client, document);

      assertThat(pages).hasSizeGreaterThan(1);
      assertThat(pages).allSatisfy(
          page -> assertThat(serialized(page)).isLessThanOrEqualTo(TelegraphRenderer.MAX_CONTENT_BYTES));
    }
  }

  @Test
  @DisplayName("splits a deeply nested list so every published page stays under 64 KB")
  void splitsDeeplyNestedLists() {
    Block.ListBlock nested = new Block.ListBlock(false, itemsOf(2_000, 40));
    for (int depth = 0; depth < 50; depth++) {
      nested = new Block.ListBlock(false, List.of(item(paragraph(text("level " + depth)), nested)));
    }

    List<List<Object>> pages = published(renderer, telegraphClient, document(nested));

    assertThat(pages).hasSizeGreaterThan(1);
    assertThat(pages).allSatisfy(
        page -> assertThat(serialized(page)).isLessThanOrEqualTo(TelegraphRenderer.MAX_CONTENT_BYTES));
  }

  @Test
  @DisplayName("splits multibyte text by UTF-8 byte size so every page stays under 64 KB and the text round-trips")
  @SuppressWarnings("unchecked")
  void splitsMultibyteTextByByteSizeAndPreservesIt() {
    String source = "→é😀".repeat(30_000);
    List<WebDocument> documents = List.of(
        document(paragraph(text(source))),
        document(paragraph(new Inline.Bold(List.of(text(source))))),
        document(new Block.Pre(source, null)));

    for (WebDocument document : documents) {
      TelegraphClient client = mock(TelegraphClient.class);
      when(client.createPage(anyString(), anyString(), anyList()))
          .thenReturn("https://telegra.ph/Multibyte-Slug-10-17");

      new TelegraphRenderer(client).publish(document, AUTHOR);

      ArgumentCaptor<List<Object>> captor = ArgumentCaptor.forClass(List.class);
      verify(client, atLeastOnce()).createPage(eq(TITLE), eq(AUTHOR), captor.capture());
      List<List<Object>> pages = captor.getAllValues();

      assertThat(pages).hasSizeGreaterThan(1);
      assertThat(pages).allSatisfy(
          page -> assertThat(serialized(page)).isLessThanOrEqualTo(TelegraphRenderer.MAX_CONTENT_BYTES));

      StringBuilder reconstructed = new StringBuilder();
      for (int index = pages.size() - 1; index >= 0; index--) {   // captured pages are in reverse order
        withoutChainFooter(pages.get(index)).forEach(node -> appendText(node, reconstructed));
      }
      String text = reconstructed.toString();
      assertThat(text).isEqualTo(source);
      assertThat(text).doesNotContain("\uFFFD");
      assertThat(hasLoneSurrogate(text)).isFalse();
    }
  }

  @Test
  @DisplayName("refuses to publish a genuinely atomic node larger than the cap instead of sending it")
  void refusesAtomicOversizedNode() {
    WebDocument document = document(new Block.Image("https://example.com/" + "x".repeat(70_000), "alt"));

    FetchException failure = catchThrowableOfType(FetchException.class, () -> renderer.publish(document, AUTHOR));

    assertThat(failure).isNotNull();
    assertThat(failure.error()).isEqualTo(FetchError.TELEGRAPH_FAILED);
    verify(telegraphClient, never()).createPage(anyString(), anyString(), anyList());
  }

  @SuppressWarnings("unchecked")
  private static List<List<Object>> published(TelegraphRenderer renderer, TelegraphClient client, WebDocument document) {
    when(client.createPage(anyString(), anyString(), anyList()))
        .thenReturn("https://telegra.ph/Preserved-Article-Slug-10-17");

    renderer.publish(document, AUTHOR);

    ArgumentCaptor<List<Object>> captor = ArgumentCaptor.forClass(List.class);
    verify(client, atLeastOnce()).createPage(eq(TITLE), eq(AUTHOR), captor.capture());
    return captor.getAllValues();
  }

  private static void assertOnlyAllowedNodes(Object node) {
    if (node instanceof String) {
      return;
    }
    assertThat(tag(node)).isIn(ALLOWED_TAGS);
    assertThat(attrs(node).keySet()).isSubsetOf(ALLOWED_ATTRS);
    List<Object> children = children(node);
    if (children != null) {
      children.forEach(TelegraphRendererTest::assertOnlyAllowedNodes);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object node) {
    return (Map<String, Object>) node;
  }

  private static String tag(Object node) {
    return (String) asMap(node).get("tag");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> children(Object node) {
    return (List<Object>) asMap(node).get("children");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> attrs(Object node) {
    return (Map<String, String>) asMap(node).getOrDefault("attrs", Map.of());
  }

  private static String chainHref(List<Object> content) {
    Object paragraph = content.getLast();
    assertThat(tag(paragraph)).isEqualTo("p");
    Object link = children(paragraph).getFirst();
    assertThat(tag(link)).isEqualTo("a");
    return attrs(link).get("href");
  }

  private static String chainText(List<Object> content) {
    Object link = children(content.getLast()).getFirst();
    return (String) children(link).getFirst();
  }

  /** Drops the trailing {@code Part N →} footer so the article text can be reconstructed. */
  private static List<Object> withoutChainFooter(List<Object> page) {
    return isChainFooter(page.getLast()) ? page.subList(0, page.size() - 1) : page;
  }

  private static boolean isChainFooter(Object node) {
    if (!(node instanceof Map<?, ?>) || !"p".equals(tag(node))) {
      return false;
    }
    List<Object> children = children(node);
    if (children == null || children.size() != 1 || !(children.getFirst() instanceof Map<?, ?> link)) {
      return false;
    }
    if (!"a".equals(tag(link)) || !attrs(link).containsKey("href")) {
      return false;
    }
    List<Object> label = children(link);
    return label != null && label.size() == 1 && label.getFirst() instanceof String text
        && text.startsWith("Part ") && text.endsWith("→");
  }

  /** Appends every string leaf in document order, so a split page's text can be concatenated back. */
  private static void appendText(Object node, StringBuilder out) {
    if (node instanceof String text) {
      out.append(text);
    } else if (node instanceof Map<?, ?>) {
      List<Object> children = children(node);
      if (children != null) {
        children.forEach(child -> appendText(child, out));
      }
    }
  }

  private static boolean hasLoneSurrogate(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (Character.isHighSurrogate(text.charAt(i))) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
          return true;
        }
        i++;
      } else if (Character.isLowSurrogate(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static long pageBytes(List<Object> page) {
    return serialized(page);
  }

  private static long serialized(Object node) {
    return JsonUtil.serializeObject(node).getBytes(StandardCharsets.UTF_8).length;
  }

  private static long paragraphBytes(int textLength) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("tag", "p");
    node.put("children", List.of("x".repeat(textLength)));
    return serialized(node);
  }

  private static Block.Paragraph[] paragraphs(int textLength, int count) {
    Block.Paragraph[] blocks = new Block.Paragraph[count];
    Arrays.setAll(blocks, _ -> paragraph(text("x".repeat(textLength))));
    return blocks;
  }

  private static List<Block.ListItem> itemsOf(int textLength, int count) {
    List<Block.ListItem> items = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      items.add(item(paragraph(text("x".repeat(textLength)))));
    }
    return items;
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
