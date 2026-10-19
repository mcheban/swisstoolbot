package org.cheban.swisstoolbot.fetch;

import lombok.extern.slf4j.Slf4j;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.extended.Readability4JExtended;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a fetched HTML page into a {@link WebDocument}. Readability4J picks the main content; when
 * it finds nothing the Jsoup heuristic ({@code article} → {@code main} → largest text block) takes
 * over. The chosen container is then flattened by a recursive walk into the framework-free
 * block/inline model: layout wrappers are unwrapped, whitespace is collapsed, empty nodes are
 * dropped and URLs are resolved against the page's final URL.
 */
@Slf4j
@Component
public class DocumentExtractor {
  private static final String REMOVE_SELECTOR =
      "script, style, nav, header, footer, aside, form, iframe, noscript,"
          + " svg, button, select, option, template, math, canvas, video, audio";
  private static final Set<String> HEADING_TAGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");
  private static final Set<String> BLOCK_TAGS = Set.of(
      "p", "ul", "ol", "blockquote", "pre", "img", "table", "hr", "figure", "figcaption",
      "dt", "dd", "address", "h1", "h2", "h3", "h4", "h5", "h6");
  private static final Set<String> INLINE_TAGS = Set.of("a", "b", "strong", "i", "em", "code", "br");
  /** Tags the walk consumes directly through their parent; everything else is a layout wrapper. */
  private static final Set<String> STRUCTURAL_TAGS = Set.of(
      "li", "tr", "td", "th", "caption", "thead", "tbody", "tfoot", "colgroup", "col");
  private static final Pattern LANGUAGE = Pattern.compile("(?:language|lang|highlight)-([A-Za-z0-9+#._-]+)");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");
  /** Schemes the renderers can safely emit; any other scheme is unwrapped to plain text. */
  private static final Set<String> RENDERABLE_SCHEMES = Set.of("http", "https", "mailto");
  private static final int MAX_TITLE_LENGTH = 256;

  /**
   * Extracts the readable content of {@code body}, resolving relative URLs against {@code finalUrl}.
   *
   * @throws FetchException with {@link FetchError#EMPTY_CONTENT} when nothing readable was found
   *                        (typically a JavaScript-rendered page or a paywall)
   */
  public WebDocument extract(String body, String finalUrl) {
    Document document = Jsoup.parse(body, finalUrl);
    String title = resolveTitle(document);
    Set<String> titleEchoes = titleEchoes(document);
    Map<String, String> codeLanguages = indexCodeLanguages(document);
    Element content = mainContent(body, finalUrl);
    if (content == null) {
      content = heuristicMainContent(document);
    }
    content.select(REMOVE_SELECTOR).remove();
    unwrapLayoutWrappers(content);
    List<Block> blocks = applyLanguages(visitChildren(content), codeLanguages);
    if (isLowContent(blocks, titleEchoes)) {
      throw new FetchException(FetchError.EMPTY_CONTENT, "No readable content extracted");
    }
    return new WebDocument(truncateTitle(title), finalUrl, List.copyOf(blocks));
  }

  /**
   * Returns the main content container according to Readability4J, or {@code null} when it cannot
   * find one. Package-private and overridable so tests can exercise the Jsoup fallback without
   * depending on Readability4J's heuristics.
   */
  Element mainContent(String body, String finalUrl) {
    try {
      Article article = new Readability4JExtended(finalUrl, body).parse();
      Element content = article.getArticleContent();
      if (content != null && !content.text().isBlank()) {
        // Re-parse so every element carries finalUrl as base URI and absUrl() resolves relative links.
        return Jsoup.parse(content.html(), finalUrl).body();
      }
    } catch (RuntimeException e) {
      log.debug("[web] Readability4J could not parse {}: {}", finalUrl, e.toString());
    }
    return null;
  }

  private static Element heuristicMainContent(Document document) {
    Element article = document.selectFirst("article");
    if (article != null && !article.text().isBlank()) {
      return article;
    }
    Element main = document.selectFirst("main, [role=main]");
    if (main != null && !main.text().isBlank()) {
      return main;
    }
    Element largest = null;
    int largestLength = -1;
    for (Element candidate : document.select("div, section, article, main")) {
      int length = candidate.text().length();
      if (length > largestLength) {
        largestLength = length;
        largest = candidate;
      }
    }
    return largest != null ? largest : document.body();
  }

  private static String resolveTitle(Document document) {
    for (String candidate : titleCandidates(document)) {
      if (!candidate.isEmpty()) {
        return candidate;
      }
    }
    return "";
  }

  /**
   * Every text a content-less page may echo: {@code og:title}, the {@code <title>} tag and the first
   * heading, in priority order. Readability4J reproduces the document title verbatim when it finds
   * no article, and the echoed tag is not necessarily the one {@link #resolveTitle} prefers (a
   * paywall's {@code og:title} often carries just the headline while {@code <title>} adds the site
   * suffix), so the low-content guard has to compare against all of them.
   */
  private static Set<String> titleEchoes(Document document) {
    Set<String> echoes = new LinkedHashSet<>();
    for (String candidate : titleCandidates(document)) {
      if (!candidate.isEmpty()) {
        echoes.add(candidate);
      }
    }
    return echoes;
  }

  private static List<String> titleCandidates(Document document) {
    Element ogTitle = document.selectFirst("meta[property=og:title], meta[name=og:title]");
    Element h1 = document.selectFirst("h1");
    return List.of(
        collapse(ogTitle != null ? ogTitle.attr("content") : null),
        collapse(document.title()),
        collapse(h1 != null ? h1.text() : null));
  }

  private static String truncateTitle(String title) {
    return title.length() > MAX_TITLE_LENGTH ? title.substring(0, MAX_TITLE_LENGTH) : title;
  }

  /**
   * Unwraps every tag the walk does not handle itself (layout {@code div}/{@code section}/{@code span},
   * unknown/custom elements, underline and similar markup) so only structural and inline tags remain.
   */
  private static void unwrapLayoutWrappers(Element element) {
    for (Element child : new ArrayList<>(element.children())) {
      unwrapLayoutWrappers(child);
      if (!isHandledTag(child.tagName())) {
        child.unwrap();
      }
    }
  }

  private static boolean isHandledTag(String tag) {
    return BLOCK_TAGS.contains(tag) || INLINE_TAGS.contains(tag) || STRUCTURAL_TAGS.contains(tag);
  }

  /** The core walk: turns an element's children into blocks, grouping runs of inline content. */
  private List<Block> visitChildren(Element parent) {
    List<Block> blocks = new ArrayList<>();
    List<Inline> pending = new ArrayList<>();
    for (Node node : parent.childNodes()) {
      if (node instanceof TextNode textNode) {
        appendText(pending, textNode.getWholeText());
      } else if (node instanceof Element element) {
        String tag = element.tagName();
        if (isImageWrapper(element)) {
          flushParagraph(pending, blocks);
          addImages(element, blocks);
        } else if (BLOCK_TAGS.contains(tag)) {
          flushParagraph(pending, blocks);
          addBlock(element, blocks);
        } else {
          collectInline(element, pending);
        }
      }
    }
    flushParagraph(pending, blocks);
    return blocks;
  }

  private void addBlock(Element element, List<Block> blocks) {
    String tag = element.tagName();
    if (HEADING_TAGS.contains(tag)) {
      List<Inline> inlines = inlines(element);
      if (!inlines.isEmpty()) {
        blocks.add(new Block.Heading(tag.charAt(1) - '0', List.copyOf(inlines)));
      }
    } else if (tag.equals("p")) {
      blocks.addAll(visitChildren(element));
    } else if (tag.equals("ul")) {
      addList(element, false, blocks);
    } else if (tag.equals("ol")) {
      addList(element, true, blocks);
    } else if (tag.equals("blockquote")) {
      List<Block> inner = visitChildren(element);
      if (!inner.isEmpty()) {
        blocks.add(new Block.Quote(List.copyOf(inner)));
      }
    } else if (tag.equals("pre")) {
      addPre(element, blocks);
    } else if (tag.equals("table")) {
      addTable(element, blocks);
    } else if (tag.equals("figure")) {
      addImages(element, blocks);
      Element caption = element.selectFirst("figcaption");
      if (caption != null) {
        List<Inline> inlines = inlines(caption);
        if (!inlines.isEmpty()) {
          blocks.add(new Block.Paragraph(List.copyOf(inlines)));
        }
      }
    } else if (tag.equals("figcaption") || tag.equals("dt") || tag.equals("dd") || tag.equals("address")) {
      List<Inline> inlines = inlines(element);
      if (!inlines.isEmpty()) {
        blocks.add(new Block.Paragraph(List.copyOf(inlines)));
      }
    }
    // "hr" is intentionally dropped (never modelled); "img" is handled as an image block by
    // visitChildren before addBlock is ever reached.
  }

  private void addList(Element list, boolean ordered, List<Block> blocks) {
    List<Block.ListItem> items = new ArrayList<>();
    for (Element child : list.children()) {
      if (child.tagName().equals("li")) {
        items.add(new Block.ListItem(List.copyOf(visitChildren(child))));
      }
    }
    if (!items.isEmpty()) {
      blocks.add(new Block.ListBlock(ordered, List.copyOf(items)));
    }
  }

  private static void addPre(Element element, List<Block> blocks) {
    Element code = element.selectFirst("code");
    Element source = code != null ? code : element;
    String text = source.wholeText().strip();
    if (!text.isEmpty()) {
      blocks.add(new Block.Pre(text, detectLanguage(element, code)));
    }
  }

  private static String detectLanguage(Element pre, Element code) {
    String language = matchLanguage(code);
    return language != null ? language : matchLanguage(pre);
  }

  private static String matchLanguage(Element element) {
    if (element == null) {
      return null;
    }
    Matcher matcher = LANGUAGE.matcher(element.className());
    return matcher.find() ? matcher.group(1) : null;
  }

  /**
   * Maps the code text of every {@code <pre>} in the original document to its declared language.
   * Readability4J strips {@code class} attributes from its output, so the language has to be read
   * from the untouched source document and matched back by text.
   */
  private static Map<String, String> indexCodeLanguages(Document document) {
    Map<String, String> languages = new HashMap<>();
    for (Element pre : document.select("pre")) {
      Element code = pre.selectFirst("code");
      Element source = code != null ? code : pre;
      String text = collapse(source.wholeText());
      String language = detectLanguage(pre, code);
      if (!text.isEmpty() && language != null) {
        languages.putIfAbsent(text, language);
      }
    }
    return languages;
  }

  private static List<Block> applyLanguages(List<Block> blocks, Map<String, String> languages) {
    List<Block> result = new ArrayList<>(blocks.size());
    for (Block block : blocks) {
      result.add(applyLanguage(block, languages));
    }
    return result;
  }

  private static Block applyLanguage(Block block, Map<String, String> languages) {
    return switch (block) {
      case Block.Pre(String text, String language) -> {
        if (language != null) {
          yield block;
        }
        String detected = languages.get(collapse(text));
        yield detected != null ? new Block.Pre(text, detected) : block;
      }
      case Block.ListBlock(boolean ordered, List<Block.ListItem> items) -> {
        List<Block.ListItem> mapped = items.stream()
            .map(item -> new Block.ListItem(applyLanguages(item.blocks(), languages)))
            .toList();
        yield new Block.ListBlock(ordered, mapped);
      }
      case Block.Quote(List<Block> blocks) -> new Block.Quote(applyLanguages(blocks, languages));
      case Block.Heading _, Block.Paragraph _, Block.Image _, Block.Table _ -> block;
    };
  }

  private static void addTable(Element element, List<Block> blocks) {
    List<List<String>> rows = new ArrayList<>();
    for (Element row : element.select("tr")) {
      List<String> cells = new ArrayList<>();
      for (Element cell : row.children()) {
        String tag = cell.tagName();
        if (tag.equals("td") || tag.equals("th")) {
          cells.add(collapse(cell.text()));
        }
      }
      if (!cells.isEmpty()) {
        rows.add(List.copyOf(cells));
      }
    }
    if (!rows.isEmpty()) {
      blocks.add(new Block.Table(List.copyOf(rows)));
    }
  }

  private static void addImages(Element element, List<Block> blocks) {
    List<Element> images = element.tagName().equals("img") ? List.of(element) : element.select("img");
    for (Element image : images) {
      String src = absoluteUrl(image, "src");
      if (src != null) {
        blocks.add(new Block.Image(src, image.attr("alt").strip()));
      }
    }
  }

  /** True for an image or for an inline element that merely wraps one (e.g. {@code <a><img></a>}). */
  private static boolean isImageWrapper(Element element) {
    if (element.tagName().equals("img")) {
      return true;
    }
    return INLINE_TAGS.contains(element.tagName())
        && element.text().isBlank()
        && !element.select("img").isEmpty();
  }

  /** Inline content of a block-level element; leading/trailing whitespace of the run is trimmed. */
  private List<Inline> inlines(Element parent) {
    return collectInlines(parent, true);
  }

  /** Inline content nested inside another inline element; surrounding spaces are kept for word joins. */
  private List<Inline> nestedInlines(Element parent) {
    return collectInlines(parent, false);
  }

  private List<Inline> collectInlines(Element parent, boolean trimEnds) {
    List<Inline> collected = new ArrayList<>();
    collectInlineChildren(parent, collected);
    return normalize(collected, trimEnds);
  }

  private void collectInlineChildren(Element parent, List<Inline> collected) {
    for (Node node : parent.childNodes()) {
      if (node instanceof TextNode textNode) {
        appendText(collected, textNode.getWholeText());
      } else if (node instanceof Element element) {
        collectInline(element, collected);
      }
    }
  }

  private void collectInline(Element element, List<Inline> collected) {
    switch (element.tagName()) {
      case "a" -> addLink(element, collected);
      case "b", "strong" -> collected.add(new Inline.Bold(nestedInlines(element)));
      case "i", "em" -> collected.add(new Inline.Italic(nestedInlines(element)));
      case "code" -> collected.add(new Inline.Code(element.text()));
      case "br" -> collected.add(new Inline.LineBreak());
      case "img" -> { /* images are lifted to block level by isImageWrapper */ }
      default -> collectInlineChildren(element, collected);
    }
  }

  private void addLink(Element element, List<Inline> collected) {
    List<Inline> inner = nestedInlines(element);
    if (inner.isEmpty()) {
      return;
    }
    String href = absoluteUrl(element, "href");
    if (href == null || !isRenderableLink(href)) {
      collected.addAll(inner);
    } else {
      collected.add(new Inline.Link(href, List.copyOf(inner)));
    }
  }

  /** Only {@code http}/{@code https}/{@code mailto} links survive; other schemes unwrap to text. */
  private static boolean isRenderableLink(String href) {
    try {
      String scheme = new URI(href).getScheme();
      return scheme != null && RENDERABLE_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static void flushParagraph(List<Inline> pending, List<Block> blocks) {
    if (pending.isEmpty()) {
      return;
    }
    List<Inline> inlines = normalize(pending, true);
    pending.clear();
    if (!inlines.isEmpty()) {
      blocks.add(new Block.Paragraph(List.copyOf(inlines)));
    }
  }

  private static void appendText(List<Inline> collected, String raw) {
    if (raw == null) {
      return;
    }
    String collapsed = WHITESPACE.matcher(raw).replaceAll(" ");
    if (!collapsed.isEmpty()) {
      collected.add(new Inline.Text(collapsed));
    }
  }

  private static List<Inline> normalize(List<Inline> raw, boolean trimEnds) {
    List<Inline> inlines = new ArrayList<>();
    for (Inline inline : raw) {
      Inline normalized = normalizeInline(inline);
      if (normalized != null) {
        inlines.add(normalized);
      }
    }
    collapseTextRuns(inlines);
    trimBoundaries(inlines, trimEnds);
    inlines.removeIf(DocumentExtractor::isEmptyInline);
    collapseTextRuns(inlines);
    return inlines;
  }

  /** Merges adjacent text runs and re-collapses their whitespace into single spaces. */
  private static void collapseTextRuns(List<Inline> inlines) {
    for (int i = 0; i < inlines.size(); i++) {
      if (inlines.get(i) instanceof Inline.Text(String text)) {
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ");
        if (i > 0 && inlines.get(i - 1) instanceof Inline.Text(String previous)) {
          inlines.set(i - 1, new Inline.Text(previous + collapsed));
          inlines.remove(i);
          i--;
        } else {
          inlines.set(i, new Inline.Text(collapsed));
        }
      }
    }
  }

  /**
   * Drops whitespace that would otherwise be duplicated across an inline boundary, so
   * {@code a <b> bold </b> c} becomes {@code a bold c} instead of {@code a  bold  c}.
   */
  private static void trimBoundaries(List<Inline> inlines, boolean trimEnds) {
    for (int i = 0; i < inlines.size(); i++) {
      boolean first = i == 0;
      boolean last = i == inlines.size() - 1;
      if ((first && trimEnds) || (!first && endsWithWhitespace(inlines.get(i - 1)))) {
        inlines.set(i, trimLeading(inlines.get(i)));
      }
      if ((last && trimEnds) || (!last && startsWithWhitespace(inlines.get(i + 1)))) {
        inlines.set(i, trimTrailing(inlines.get(i)));
      }
    }
  }

  private static boolean startsWithWhitespace(List<Inline> inlines) {
    return !inlines.isEmpty() && startsWithWhitespace(inlines.getFirst());
  }

  private static boolean startsWithWhitespace(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) -> !text.isEmpty() && Character.isWhitespace(text.charAt(0));
      case Inline.Bold(List<Inline> inlines) -> startsWithWhitespace(inlines);
      case Inline.Italic(List<Inline> inlines) -> startsWithWhitespace(inlines);
      case Inline.Link(_, List<Inline> inlines) -> startsWithWhitespace(inlines);
      case Inline.Code _ -> false;
      case Inline.LineBreak _ -> true;
    };
  }

  private static boolean endsWithWhitespace(List<Inline> inlines) {
    return !inlines.isEmpty() && endsWithWhitespace(inlines.getLast());
  }

  private static boolean endsWithWhitespace(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) ->
          !text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1));
      case Inline.Bold(List<Inline> inlines) -> endsWithWhitespace(inlines);
      case Inline.Italic(List<Inline> inlines) -> endsWithWhitespace(inlines);
      case Inline.Link(_, List<Inline> inlines) -> endsWithWhitespace(inlines);
      case Inline.Code _ -> false;
      case Inline.LineBreak _ -> true;
    };
  }

  private static Inline trimLeading(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) -> new Inline.Text(text.replaceAll("^\\s+", ""));
      case Inline.Bold(List<Inline> inlines) -> new Inline.Bold(trimLeading(inlines));
      case Inline.Italic(List<Inline> inlines) -> new Inline.Italic(trimLeading(inlines));
      case Inline.Link(String href, List<Inline> inlines) -> new Inline.Link(href, trimLeading(inlines));
      case Inline.Code _, Inline.LineBreak _ -> inline;
    };
  }

  private static List<Inline> trimLeading(List<Inline> inlines) {
    List<Inline> result = new ArrayList<>(inlines);
    while (!result.isEmpty()) {
      Inline first = trimLeading(result.getFirst());
      if (isEmptyInline(first)) {
        result.removeFirst();
        continue;
      }
      result.set(0, first);
      break;
    }
    return result;
  }

  private static Inline trimTrailing(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) -> new Inline.Text(text.replaceAll("\\s+$", ""));
      case Inline.Bold(List<Inline> inlines) -> new Inline.Bold(trimTrailing(inlines));
      case Inline.Italic(List<Inline> inlines) -> new Inline.Italic(trimTrailing(inlines));
      case Inline.Link(String href, List<Inline> inlines) -> new Inline.Link(href, trimTrailing(inlines));
      case Inline.Code _, Inline.LineBreak _ -> inline;
    };
  }

  private static List<Inline> trimTrailing(List<Inline> inlines) {
    List<Inline> result = new ArrayList<>(inlines);
    while (!result.isEmpty()) {
      int last = result.size() - 1;
      Inline trimmed = trimTrailing(result.get(last));
      if (isEmptyInline(trimmed)) {
        result.remove(last);
        continue;
      }
      result.set(last, trimmed);
      break;
    }
    return result;
  }

  private static boolean isEmptyInline(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) -> text.isEmpty();
      case Inline.Bold(List<Inline> inlines) -> inlines.isEmpty();
      case Inline.Italic(List<Inline> inlines) -> inlines.isEmpty();
      case Inline.Link(_, List<Inline> inlines) -> inlines.isEmpty();
      case Inline.Code _, Inline.LineBreak _ -> false;
    };
  }

  private static Inline normalizeInline(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) -> {
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ");
        yield collapsed.isEmpty() ? null : new Inline.Text(collapsed);
      }
      case Inline.Bold(List<Inline> inlines) -> {
        List<Inline> children = normalize(inlines, false);
        yield children.isEmpty() ? null : new Inline.Bold(List.copyOf(children));
      }
      case Inline.Italic(List<Inline> inlines) -> {
        List<Inline> children = normalize(inlines, false);
        yield children.isEmpty() ? null : new Inline.Italic(List.copyOf(children));
      }
      case Inline.Link(String href, List<Inline> inlines) -> {
        List<Inline> children = normalize(inlines, false);
        yield children.isEmpty() ? null : new Inline.Link(href, List.copyOf(children));
      }
      case Inline.Code(String text) -> text == null || text.isEmpty() ? null : inline;
      case Inline.LineBreak _ -> inline;
    };
  }

  /**
   * A conservative low-content guard: a page that yields no text at all, or whose only text is one
   * of the page's title echoes (Readability4J reproduces the title when it finds no article), is
   * treated as empty so JavaScript-only and paywalled pages report {@link FetchError#EMPTY_CONTENT}.
   * Genuinely short articles always carry text beyond the title and are therefore kept.
   */
  private static boolean isLowContent(List<Block> blocks, Set<String> titleEchoes) {
    if (hasNonTextBlock(blocks)) {
      return false;
    }
    String text = collapse(plainText(blocks));
    return text.isEmpty() || titleEchoes.contains(text);
  }

  /** Images and tables are content even when they carry no readable text. */
  private static boolean hasNonTextBlock(List<Block> blocks) {
    return blocks.stream().anyMatch(DocumentExtractor::hasNonTextBlock);
  }

  private static boolean hasNonTextBlock(Block block) {
    return switch (block) {
      case Block.Image _, Block.Table _ -> true;
      case Block.ListBlock(_, List<Block.ListItem> items) ->
          items.stream().anyMatch(item -> hasNonTextBlock(item.blocks()));
      case Block.Quote(List<Block> inner) -> hasNonTextBlock(inner);
      case Block.Heading _, Block.Paragraph _, Block.Pre _ -> false;
    };
  }

  private static String plainText(List<Block> blocks) {
    return blocks.stream().map(DocumentExtractor::plainText).collect(Collectors.joining("\n"));
  }

  private static String plainText(Block block) {
    return switch (block) {
      case Block.Heading(_, List<Inline> inlines) -> plainTextInlines(inlines);
      case Block.Paragraph(List<Inline> inlines) -> plainTextInlines(inlines);
      case Block.ListBlock(_, List<Block.ListItem> items) ->
          items.stream().map(item -> plainText(item.blocks())).collect(Collectors.joining("\n"));
      case Block.Quote(List<Block> inner) -> plainText(inner);
      case Block.Pre(String text, _) -> text;
      case Block.Image(_, String alt) -> alt;
      case Block.Table(List<List<String>> rows) ->
          rows.stream().map(row -> String.join(" ", row)).collect(Collectors.joining("\n"));
    };
  }

  private static String plainTextInlines(List<Inline> inlines) {
    return inlines.stream().map(DocumentExtractor::plainText).collect(Collectors.joining());
  }

  private static String plainText(Inline inline) {
    return switch (inline) {
      case Inline.Text(String text) -> text;
      case Inline.Code(String text) -> text;
      case Inline.Bold(List<Inline> inlines) -> plainTextInlines(inlines);
      case Inline.Italic(List<Inline> inlines) -> plainTextInlines(inlines);
      case Inline.Link(_, List<Inline> inlines) -> plainTextInlines(inlines);
      case Inline.LineBreak _ -> "\n";
    };
  }

  private static String absoluteUrl(Element element, String attribute) {
    String absolute = element.absUrl(attribute);
    if (absolute.isBlank()) {
      absolute = element.attr(attribute);
    }
    absolute = absolute.strip();
    return absolute.isEmpty() ? null : absolute;
  }

  private static String collapse(String value) {
    return value == null ? "" : WHITESPACE.matcher(value).replaceAll(" ").strip();
  }
}
