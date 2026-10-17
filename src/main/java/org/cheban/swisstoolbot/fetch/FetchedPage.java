package org.cheban.swisstoolbot.fetch;

/**
 * A successfully downloaded page: the URL that was actually fetched (after redirects) and its raw
 * HTML body.
 */
public record FetchedPage(String finalUrl, String body) {
}
