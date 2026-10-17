package org.cheban.swisstoolbot.fetch;

import java.util.List;

/**
 * Framework-free representation of the readable content of a web page. It deliberately carries no
 * Jsoup types so the renderers and the classifier stay decoupled from the HTML parser.
 */
public record WebDocument(String title, String sourceUrl, List<Block> blocks) {
}
