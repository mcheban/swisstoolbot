package org.cheban.swisstoolbot.fetch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.util.JsonUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link WebDocument} into telegra.ph Node JSON.
 *
 * <p>{@link #render(WebDocument)} maps every block to the tags telegra.ph accepts and splits the
 * result into pages that each stay under the API's 64 KB {@code content} limit. {@link
 * #publish(WebDocument, String)} then creates the pages in reverse order, so each page but the last
 * can end with a {@code Part N →} link to its successor, and returns the first page's URL.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegraphRenderer {
  /** telegra.ph's limit for the serialized {@code content} array. */
  static final int MAX_CONTENT_BYTES = 64 * 1024;
  /** Room kept free on every page for the {@code Part N →} link appended by {@link #publish}. */
  static final int CHAIN_LINK_RESERVE = 1024;
  private static final long PAGE_BUDGET = MAX_CONTENT_BYTES - CHAIN_LINK_RESERVE;

  private static final String TAG = "tag";
  private static final String ATTRS = "attrs";
  private static final String CHILDREN = "children";
  private static final String HREF = "href";
  private static final String SRC = "src";

  private static final long UL_WRAPPER = jsonBytes(container("ul", List.of()));
  private static final long OL_WRAPPER = jsonBytes(container("ol", List.of()));

  private final TelegraphClient telegraphClient;

  /**
   * Renders the document into one or more page contents, each small enough for telegra.ph. Pages are
   * split at top-level block boundaries (a long list is split at item boundaries), so no node is ever
   * cut in half.
   */
  public List<List<Object>> render(WebDocument document) {
    List<List<Object>> pages = new ArrayList<>();
    Page page = new Page();
    for (Unit unit : unitsOf(document.blocks())) {
      switch (unit) {
        case Unit.Atomic atomic -> addAtomic(atomic.node(), page, pages);
        case Unit.Items items -> addItems(items, page, pages);
      }
    }
    if (!page.isEmpty() || pages.isEmpty()) {
      pages.add(List.copyOf(page.nodes()));
    }
    return pages;
  }

  /**
   * Publishes every page and returns the first page's URL. Pages are created last-to-first so the
   * URL of the successor is already known when the {@code Part N →} link is added to a page.
   */
  public String publish(WebDocument document, String author) {
    List<List<Object>> pages = render(document);
    String nextUrl = null;
    String firstUrl = null;
    for (int index = pages.size() - 1; index >= 0; index--) {
      List<Object> content = new ArrayList<>(pages.get(index));
      if (nextUrl != null) {
        content.add(chainLink(index + 2, nextUrl));
      }
      ensureWithinLimit(content, index + 1, pages.size());
      nextUrl = telegraphClient.createPage(document.title(), author, content);
      firstUrl = nextUrl;
    }
    return firstUrl;
  }

  /**
   * Fails loudly instead of sending telegra.ph an oversized page it would reject: only a genuinely
   * atomic node (e.g. an {@code img} whose {@code src} alone exceeds the budget) can reach this.
   */
  private static void ensureWithinLimit(List<Object> content, int part, int parts) {
    long bytes = jsonBytes(content);
    if (bytes > MAX_CONTENT_BYTES) {
      log.error("[telegraph] Refusing to publish part {}/{}: {} bytes exceed the {} byte limit",
          part, parts, bytes, MAX_CONTENT_BYTES);
      throw new FetchException(FetchError.TELEGRAPH_FAILED, "Page content exceeds the 64 KB limit");
    }
  }

  private static void addAtomic(Object node, Page page, List<List<Object>> pages) {
    for (Object part : splitToFit(node, PAGE_BUDGET)) {
      long size = jsonBytes(part);
      if (!page.isEmpty() && page.bytesWith(size) > PAGE_BUDGET) {
        flush(page, pages);
      }
      page.add(size, part);
    }
  }

  private static void addItems(Unit.Items items, Page page, List<List<Object>> pages) {
    List<Object> pending = new ArrayList<>(items.items());
    long wrapper = items.ordered() ? OL_WRAPPER : UL_WRAPPER;
    while (!pending.isEmpty()) {
      long available = PAGE_BUDGET - page.bytes() - (page.isEmpty() ? 0 : 1) - wrapper;
      int taken = 0;
      long sum = 0;
      while (taken < pending.size()) {
        long next = sum + jsonBytes(pending.get(taken)) + (taken == 0 ? 0 : 1);
        if (next > available) {
          break;
        }
        sum = next;
        taken++;
      }
      if (taken == 0) {
        if (!page.isEmpty()) {
          flush(page, pages);
          continue;
        }
        // A single entry does not fit a page on its own: treat it like any other oversized node.
        addAtomic(pending.removeFirst(), page, pages);
        continue;
      }
      List<Object> children = new ArrayList<>(pending.subList(0, taken));
      pending.subList(0, taken).clear();
      page.add(wrapper + sum, container(items.ordered() ? "ol" : "ul", children));
    }
  }

  private static void flush(Page page, List<List<Object>> pages) {
    pages.add(List.copyOf(page.nodes()));
    page.reset();
  }

  /**
   * Splits a node that alone exceeds {@code budget} into an ordered list of nodes that each fit it,
   * recursing into nested containers (and text runs) so no reachable subtree can escape the budget.
   * Only a node with no splittable children is returned as-is; {@link #publish} then refuses to send
   * the oversized page rather than silently exceeding the API limit.
   */
  private static List<Object> splitToFit(Object node, long budget) {
    if (node instanceof String text) {
      return splitText(text, budget);
    }
    if (jsonBytes(node) <= budget) {
      return List.of(node);
    }
    List<Object> children = childrenOf(node);
    if (children == null || children.isEmpty()) {
      log.warn("[telegraph] A single {} node of {} bytes cannot be split within the page budget",
          tagOf(node), jsonBytes(node));
      return List.of(node);
    }
    // The budget available to the serialized children array: the wrapper (tag/attrs/braces) is fixed.
    long innerBudget = budget - (jsonBytes(withChildren(node, List.of())) - 2);
    long childBudget = innerBudget - 2;   // a lone child also carries the array's "[]"
    if (childBudget < 1) {
      log.warn("[telegraph] A single {} node of {} bytes leaves no room to split its children",
          tagOf(node), jsonBytes(node));
      return List.of(node);
    }
    List<Object> parts = new ArrayList<>();
    List<Object> chunk = new ArrayList<>();
    long chunkBytes = 2;   // the children array's surrounding "[]"
    for (Object child : children) {
      for (Object piece : splitToFit(child, childBudget)) {
        long addition = jsonBytes(piece) + (chunk.isEmpty() ? 0 : 1);
        if (!chunk.isEmpty() && chunkBytes + addition > innerBudget) {
          parts.add(withChildren(node, chunk));
          chunk = new ArrayList<>();
          chunkBytes = 2;
          addition = jsonBytes(piece);
        }
        chunk.add(piece);
        chunkBytes += addition;
      }
    }
    if (!chunk.isEmpty()) {
      parts.add(withChildren(node, chunk));
    }
    return parts.isEmpty() ? List.of(node) : parts;
  }

  /** Splits a text run into pieces whose serialized JSON form stays within {@code budget}. */
  private static List<Object> splitText(String text, long budget) {
    if (jsonBytes(text) <= budget) {
      return List.of(text);
    }
    if (budget < 2) {
      log.warn("[telegraph] A single text run of {} bytes cannot be split within the page budget",
          jsonBytes(text));
      return List.of(text);
    }
    List<Object> pieces = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = maxFittingEnd(text, start, budget);
      pieces.add(text.substring(start, end));
      start = end;
    }
    return pieces;
  }

  /** Largest substring starting at {@code start} whose serialized form still fits {@code budget}. */
  private static int maxFittingEnd(String text, int start, long budget) {
    int low = start + 1;
    int high = (int) Math.min(text.length(), start + budget);   // every character is at least one byte
    int end = low;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      if (jsonBytes(text.substring(start, mid)) <= budget) {
        end = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    if (end < text.length() && end - 1 > start && Character.isHighSurrogate(text.charAt(end - 1))) {
      return end - 1;   // never split a surrogate pair across two pieces
    }
    return end;
  }

  private static String tagOf(Object node) {
    return node instanceof Map<?, ?> map ? String.valueOf(map.get(TAG)) : "leaf";
  }

  private static List<Unit> unitsOf(List<Block> blocks) {
    List<Unit> units = new ArrayList<>();
    for (Block block : blocks) {
      switch (block) {
        case Block.Heading heading -> {
          List<Object> inlines = inlineNodes(heading.inlines());
          if (!inlines.isEmpty()) {
            units.add(new Unit.Atomic(container(heading.level() <= 2 ? "h3" : "h4", inlines)));
          }
        }
        case Block.Paragraph paragraph -> {
          List<Object> inlines = inlineNodes(paragraph.inlines());
          if (!inlines.isEmpty()) {
            units.add(new Unit.Atomic(container("p", inlines)));
          }
        }
        case Block.ListBlock list ->
            units.add(new Unit.Items(list.ordered(), list.items().stream().map(TelegraphRenderer::itemNode).toList()));
        case Block.Quote quote -> units.add(new Unit.Atomic(quoteNode(quote)));
        case Block.Pre pre -> units.add(new Unit.Atomic(preNode(pre)));
        case Block.Image image -> {
          if (!image.src().isBlank()) {
            units.add(new Unit.Atomic(imageNode(image.src())));
          }
        }
        case Block.Table table -> units.add(new Unit.Items(false, tableRows(table)));
      }
    }
    return units;
  }

  private static Object itemNode(Block.ListItem item) {
    List<Object> children = new ArrayList<>();
    for (Block block : item.blocks()) {
      switch (block) {
        case Block.ListBlock nested -> children.add(listNode(nested));
        case Block.Paragraph paragraph -> children.addAll(inlineNodes(paragraph.inlines()));
        case Block.Heading heading -> children.addAll(inlineNodes(heading.inlines()));
        case Block.Pre pre -> children.add(preNode(pre));
        case Block.Quote quote -> children.add(quoteNode(quote));
        case Block.Image image -> {
          if (!image.src().isBlank()) {
            children.add(imageNode(image.src()));
          }
        }
        case Block.Table table -> {
          List<Object> rows = tableRows(table);
          if (!rows.isEmpty()) {
            children.add(container("ul", rows));
          }
        }
      }
    }
    return container("li", children);
  }

  private static Object listNode(Block.ListBlock list) {
    return container(list.ordered() ? "ol" : "ul",
        list.items().stream().map(TelegraphRenderer::itemNode).toList());
  }

  private static Object preNode(Block.Pre pre) {
    return container("pre", List.of(pre.text()));
  }

  private static Object quoteNode(Block.Quote quote) {
    return container("blockquote", inlineBlocks(quote.blocks()));
  }

  /** {@code blockquote} only takes inline children, so nested blocks are flattened with {@code br}. */
  private static List<Object> inlineBlocks(List<Block> blocks) {
    List<Object> nodes = new ArrayList<>();
    for (Block block : blocks) {
      List<Object> inlines = switch (block) {
        case Block.Paragraph paragraph -> inlineNodes(paragraph.inlines());
        case Block.Heading heading -> inlineNodes(heading.inlines());
        case Block.Pre pre -> List.of(container("code", List.of(pre.text())));
        case Block.Quote nested -> List.of(quoteNode(nested));
        case Block.ListBlock list -> listInlineNodes(list);
        case Block.Table table -> tableInlineNodes(table);
        case Block.Image ignored -> List.of();
      };
      if (inlines.isEmpty()) {
        continue;
      }
      if (!nodes.isEmpty()) {
        nodes.add(br());
      }
      nodes.addAll(inlines);
    }
    return nodes;
  }

  private static List<Object> listInlineNodes(Block.ListBlock list) {
    List<Object> nodes = new ArrayList<>();
    int index = 1;
    for (Block.ListItem item : list.items()) {
      if (!nodes.isEmpty()) {
        nodes.add(br());
      }
      nodes.add(list.ordered() ? index + ". " : "• ");
      nodes.addAll(inlineBlocks(item.blocks()));
      index++;
    }
    return nodes;
  }

  private static List<Object> tableInlineNodes(Block.Table table) {
    List<Object> nodes = new ArrayList<>();
    for (List<String> row : table.rows()) {
      String text = rowText(row);
      if (text.isBlank()) {
        continue;
      }
      if (!nodes.isEmpty()) {
        nodes.add(br());
      }
      nodes.add(text);
    }
    return nodes;
  }

  /** A table has no telegra.ph equivalent, so every row becomes one list entry; ragged rows are fine. */
  private static List<Object> tableRows(Block.Table table) {
    List<Object> rows = new ArrayList<>();
    for (List<String> row : table.rows()) {
      String text = rowText(row);
      if (!text.isBlank()) {
        rows.add(container("li", List.of(text)));
      }
    }
    return rows;
  }

  private static String rowText(List<String> row) {
    return row.stream()
        .filter(cell -> cell != null && !cell.isBlank())
        .reduce((left, right) -> left + " | " + right)
        .orElse("");
  }

  private static List<Object> inlineNodes(List<Inline> inlines) {
    List<Object> nodes = new ArrayList<>();
    for (Inline inline : inlines) {
      switch (inline) {
        case Inline.Text text -> {
          if (!text.text().isEmpty()) {
            nodes.add(text.text());
          }
        }
        case Inline.Bold bold -> nodes.add(container("strong", inlineNodes(bold.inlines())));
        case Inline.Italic italic -> nodes.add(container("em", inlineNodes(italic.inlines())));
        case Inline.Code code -> nodes.add(container("code", List.of(code.text())));
        case Inline.Link link -> nodes.add(linkNode(link));
        case Inline.LineBreak ignored -> nodes.add(br());
      }
    }
    return nodes;
  }

  private static Object linkNode(Inline.Link link) {
    List<Object> children = link.inlines().isEmpty() ? List.of(link.href()) : inlineNodes(link.inlines());
    return container("a", Map.of(HREF, link.href()), children);
  }

  private static Object chainLink(int part, String nextUrl) {
    return container("p", List.of(container("a", Map.of(HREF, nextUrl), List.of("Part " + part + " →"))));
  }

  private static Object container(String tag, List<Object> children) {
    return container(tag, Map.of(), children);
  }

  private static Object container(String tag, Map<String, String> attrs, List<Object> children) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put(TAG, tag);
    if (!attrs.isEmpty()) {
      node.put(ATTRS, attrs);
    }
    node.put(CHILDREN, children);
    return node;
  }

  /** {@code br} is a void tag and carries no children. */
  private static Object br() {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put(TAG, "br");
    return node;
  }

  /** {@code img} is a void tag; only its absolute {@code src} survives, the alt text is dropped. */
  private static Object imageNode(String src) {
    Map<String, Object> node = new LinkedHashMap<>();
    node.put(TAG, "img");
    node.put(ATTRS, Map.of(SRC, src));
    return node;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> childrenOf(Object node) {
    return node instanceof Map<?, ?> map && map.get(CHILDREN) instanceof List<?> children
        ? (List<Object>) children
        : null;
  }

  @SuppressWarnings("unchecked")
  private static Object withChildren(Object node, List<Object> children) {
    Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) node);
    copy.put(CHILDREN, new ArrayList<>(children));
    return copy;
  }

  private static long jsonBytes(Object node) {
    return JsonUtil.serializeObject(node).getBytes(StandardCharsets.UTF_8).length;
  }

  private sealed interface Unit {
    record Atomic(Object node) implements Unit {
    }

    record Items(boolean ordered, List<Object> items) implements Unit {
    }
  }

  /** The page being packed: its nodes plus the byte size of the serialized JSON array. */
  private static final class Page {
    private final List<Object> nodes = new ArrayList<>();
    private long bytes = 2;   // the surrounding "[]"

    private boolean isEmpty() {
      return nodes.isEmpty();
    }

    private List<Object> nodes() {
      return nodes;
    }

    private long bytes() {
      return bytes;
    }

    private long bytesWith(long nodeBytes) {
      return bytes + nodeBytes + (nodes.isEmpty() ? 0 : 1);
    }

    private void add(long nodeBytes, Object node) {
      bytes = bytesWith(nodeBytes);
      nodes.add(node);
    }

    private void reset() {
      nodes.clear();
      bytes = 2;
    }
  }
}
