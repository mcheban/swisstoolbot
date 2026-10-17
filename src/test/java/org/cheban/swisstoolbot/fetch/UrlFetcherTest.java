package org.cheban.swisstoolbot.fetch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link UrlFetcher} against a real {@code com.sun.net.httpserver.HttpServer} on loopback.
 * Only the address pinning for {@code 127.0.0.1} is overridden (the guard rightly refuses loopback);
 * every other host still goes through the production {@link SsrfGuard}.
 */
@DisplayName("UrlFetcher")
class UrlFetcherTest {
  private static final int CONNECT_TIMEOUT_MS = 3_000;
  private static final int READ_TIMEOUT_MS = 5_000;
  private static final long MAX_BODY_BYTES = 1_000_000L;

  private static final String HTML = "<html><body><h1>Hello</h1></body></html>";

  private HttpServer server;
  private UrlFetcher fetcher;
  private String origin;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.createContext("/html", exchange -> respond(exchange, "text/html; charset=utf-8", bytes(HTML)));
    server.createContext("/plain", exchange -> respond(exchange, "text/plain", bytes("not html")));
    server.createContext("/missing", exchange -> respondNoBody(exchange, 404));
    server.createContext("/big", exchange -> respond(exchange, "text/html", new byte[4096]));
    server.createContext("/big-chunked", exchange -> respondChunked(exchange, new byte[4096]));
    server.createContext("/padded", exchange -> {
      int size = Integer.parseInt(exchange.getRequestURI().getQuery().substring("n=".length()));
      respond(exchange, "text/html", paddedHtml(size));
    });
    server.createContext("/chunked", exchange -> respondChunked(exchange, bytes("<html><body>chunked body</body></html>")));
    server.createContext("/gzip", UrlFetcherTest::respondGzip);
    server.createContext("/redirect", exchange -> redirect(exchange, "/html"));
    server.createContext("/chain", exchange -> redirect(exchange, "/redirect"));
    server.createContext("/relative", exchange -> redirect(exchange, "html"));
    server.createContext("/loop", exchange -> redirect(exchange, "/loop"));
    server.createContext("/no-location", exchange -> respondNoBody(exchange, 302));
    server.createContext("/private", exchange -> redirect(exchange, "http://10.9.9.9/"));
    server.createContext("/file", exchange -> redirect(exchange, "file:///etc/passwd"));
    server.start();

    origin = "http://127.0.0.1:" + server.getAddress().getPort();
    fetcher = new LocalUrlFetcher(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_BODY_BYTES, 5);
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  @DisplayName("returns the HTML body and the final URL")
  void fetchesHtmlPage() {
    FetchedPage page = fetcher.fetch(origin + "/html");

    assertThat(page.body()).contains("<h1>Hello</h1>");
    assertThat(page.finalUrl()).isEqualTo(origin + "/html");
  }

  @Test
  void decodesChunkedBodies() {
    assertThat(fetcher.fetch(origin + "/chunked").body()).contains("chunked body");
  }

  @Test
  void decodesGzipBodies() {
    assertThat(fetcher.fetch(origin + "/gzip").body()).contains("gzipped body");
  }

  @Test
  void followsARedirectChain() {
    FetchedPage page = fetcher.fetch(origin + "/chain");

    assertThat(page.finalUrl()).isEqualTo(origin + "/html");
    assertThat(page.body()).contains("<h1>Hello</h1>");
  }

  @Test
  void followsARelativeRedirect() {
    assertThat(fetcher.fetch(origin + "/relative").finalUrl()).isEqualTo(origin + "/html");
  }

  @Test
  @DisplayName("a non-HTML content type is rejected")
  void rejectsNonHtmlContentType() {
    assertThat(errorOf(origin + "/plain")).isEqualTo(FetchError.NOT_HTML);
  }

  @Test
  @DisplayName("a non-200 status is reported as a fetch failure")
  void rejectsNonSuccessStatus() {
    assertThat(errorOf(origin + "/missing")).isEqualTo(FetchError.FETCH_FAILED);
  }

  @Test
  @DisplayName("a body larger than the cap is rejected even when Content-Length declares it")
  void rejectsOversizedBody() {
    UrlFetcher small = new LocalUrlFetcher(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 512L, 5);

    assertThat(errorOf(small, origin + "/big")).isEqualTo(FetchError.TOO_LARGE);
  }

  @Test
  void streamedBodyOverTheCapIsRejected() {
    UrlFetcher capped = new LocalUrlFetcher(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 1024L, 5);

    assertThat(errorOf(capped, origin + "/big-chunked")).isEqualTo(FetchError.TOO_LARGE);
    assertThat(errorOf(capped, origin + "/padded?n=1025")).isEqualTo(FetchError.TOO_LARGE);
  }

  @Test
  void bodyExactlyAtTheCapIsAccepted() {
    UrlFetcher capped = new LocalUrlFetcher(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, 1024L, 5);

    assertThat(capped.fetch(origin + "/padded?n=1024").body()).hasSize(1024);
  }

  @Test
  void rejectsTooManyRedirects() {
    UrlFetcher strict = new LocalUrlFetcher(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_BODY_BYTES, 3);

    assertThat(errorOf(strict, origin + "/loop")).isEqualTo(FetchError.FETCH_FAILED);
  }

  @Test
  void rejectsARedirectWithoutLocation() {
    assertThat(errorOf(origin + "/no-location")).isEqualTo(FetchError.FETCH_FAILED);
  }

  @Test
  @DisplayName("a redirect onto a private address is blocked on the second hop")
  void blocksRedirectToPrivateAddress() {
    assertThat(errorOf(origin + "/private")).isEqualTo(FetchError.BLOCKED_URL);
  }

  @Test
  @DisplayName("a redirect onto a non-HTTP scheme is blocked on the second hop")
  void blocksRedirectToUnsupportedScheme() {
    assertThat(errorOf(origin + "/file")).isEqualTo(FetchError.BLOCKED_URL);
  }

  @Test
  void rejectsBlockedUrlBeforeConnecting() {
    assertThat(errorOf("http://169.254.169.254/latest/meta-data/")).isEqualTo(FetchError.BLOCKED_URL);
  }

  @Test
  void rejectsInvalidUrl() {
    assertThat(errorOf("http://exa mple.com/")).isEqualTo(FetchError.INVALID_URL);
  }

  private FetchError errorOf(String url) {
    return errorOf(fetcher, url);
  }

  private static FetchError errorOf(UrlFetcher fetcher, String url) {
    try {
      fetcher.fetch(url);
      throw new AssertionError("Expected " + url + " to be rejected");
    } catch (FetchException e) {
      return e.error();
    }
  }

  private static byte[] bytes(String body) {
    return body.getBytes(StandardCharsets.UTF_8);
  }

  private static void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", contentType);
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private static void respondNoBody(HttpExchange exchange, int status) throws IOException {
    exchange.sendResponseHeaders(status, -1);
    exchange.close();
  }

  private static void redirect(HttpExchange exchange, String location) throws IOException {
    exchange.getResponseHeaders().add("Location", location);
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  /** {@code sendResponseHeaders(200, 0)} makes the server emit a chunked response with no length. */
  private static void respondChunked(HttpExchange exchange, byte[] body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      int half = body.length / 2;
      out.write(body, 0, half);
      out.flush();
      out.write(body, half, body.length - half);
    }
  }

  private static byte[] paddedHtml(int size) {
    byte[] head = bytes("<html><body>");
    byte[] tail = bytes("</body></html>");
    byte[] body = new byte[size];
    System.arraycopy(head, 0, body, 0, head.length);
    System.arraycopy(tail, 0, body, size - tail.length, tail.length);
    Arrays.fill(body, head.length, size - tail.length, (byte) 'x');
    return body;
  }

  private static void respondGzip(HttpExchange exchange) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
      gzip.write(bytes("<html><body>gzipped body</body></html>"));
    }
    byte[] body = buffer.toByteArray();
    exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
    exchange.getResponseHeaders().add("Content-Encoding", "gzip");
    exchange.sendResponseHeaders(200, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  /** Pins {@code 127.0.0.1} to the local server; every other host uses the real SSRF guard. */
  private static final class LocalUrlFetcher extends UrlFetcher {
    private LocalUrlFetcher(int connectTimeoutMs, int readTimeoutMs, long maxBodyBytes, int maxRedirects) {
      super(connectTimeoutMs, readTimeoutMs, maxBodyBytes, maxRedirects);
    }

    @Override
    InetAddress pinnedAddress(URI uri) {
      if ("127.0.0.1".equals(uri.getHost())) {
        try {
          return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
          throw new IllegalStateException(e);
        }
      }
      return super.pinnedAddress(uri);
    }
  }
}
