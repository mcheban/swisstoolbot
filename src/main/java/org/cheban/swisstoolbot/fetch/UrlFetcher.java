package org.cheban.swisstoolbot.fetch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.util.HttpUtil;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Downloads a user-supplied page over a socket that is deliberately pinned to the address vetted by
 * {@link SsrfGuard}, so the hostname can never be re-resolved between validation and connect
 * (DNS rebinding). {@code java.net.http.HttpClient} is not used because it offers neither an address
 * pinning hook nor the ability to send a {@code Host} header different from the connected address.
 *
 * <p>Redirects are followed manually: every hop goes back through {@link SsrfGuard}, so a public URL
 * cannot bounce the fetcher onto an internal address. Cookies and credentials are never sent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UrlFetcher {
  private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
  private static final int MAX_HEADER_LINES = 200;
  private static final Charset HEADER_CHARSET = StandardCharsets.ISO_8859_1;

  @Value("${fetch.connect-timeout-ms:3000}")
  private final int connectTimeoutMs;
  @Value("${fetch.read-timeout-ms:5000}")
  private final int readTimeoutMs;
  @Value("${fetch.max-body-bytes:5242880}")
  private final long maxBodyBytes;
  @Value("${fetch.max-redirects:5}")
  private final int maxRedirects;

  /**
   * Fetches {@code url} and returns the HTML document at the end of the redirect chain.
   *
   * @throws FetchException with {@code INVALID_URL}, {@code BLOCKED_URL}, {@code NOT_HTML},
   *                        {@code TOO_LARGE} or {@code FETCH_FAILED}
   */
  public FetchedPage fetch(String url) {
    URI uri = SsrfGuard.validate(url);
    for (int redirects = 0; ; redirects++) {
      Response response = download(uri);
      if (REDIRECT_STATUSES.contains(response.status())) {
        if (redirects >= maxRedirects) {
          throw new FetchException(FetchError.FETCH_FAILED, "Too many redirects");
        }
        uri = SsrfGuard.validate(resolveLocation(uri, response.headers().get("location")));
        continue;
      }
      if (response.status() != 200) {
        throw new FetchException(FetchError.FETCH_FAILED, "HTTP status " + response.status());
      }
      if (!isHtml(response.headers())) {
        throw new FetchException(FetchError.NOT_HTML, "Response is not HTML");
      }
      return new FetchedPage(uri.toString(), decode(response));
    }
  }

  /**
   * Resolves and validates the address to connect to. Kept overridable so tests can pin a locally
   * hosted server without weakening the production guard for real hosts.
   */
  InetAddress pinnedAddress(URI uri) {
    return SsrfGuard.resolvePinned(uri);
  }

  private Response download(URI uri) {
    InetAddress pinned = pinnedAddress(uri);
    int port = portOf(uri);
    log.info("[web] Fetching {} via {}", uri, pinned.getHostAddress());
    try (Socket socket = openSocket(uri, pinned, port)) {
      writeRequest(socket, uri, port);
      InputStream in = new BufferedInputStream(socket.getInputStream());
      String statusLine = readLine(in);
      if (statusLine == null) {
        throw new FetchException(FetchError.FETCH_FAILED, "Empty response");
      }
      int status = parseStatus(statusLine);
      Map<String, String> headers = readHeaders(in);
      byte[] body = status == 200 ? readBody(in, headers) : null;
      return new Response(status, headers, body);
    } catch (IOException e) {
      log.info("[web] Fetch of {} failed: {}", uri, e.toString());
      throw new FetchException(FetchError.FETCH_FAILED, e.getMessage());
    }
  }

  private Socket openSocket(URI uri, InetAddress pinned, int port) throws IOException {
    Socket plain = new Socket();
    try {
      plain.connect(new InetSocketAddress(pinned, port), connectTimeoutMs);
      plain.setSoTimeout(readTimeoutMs);
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        return plain;
      }
      String host = hostName(uri);
      SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
      SSLParameters parameters = ssl.getSSLParameters();
      parameters.setEndpointIdentificationAlgorithm("HTTPS");
      setServerName(parameters, host);
      ssl.setSSLParameters(parameters);
      ssl.startHandshake();
      ssl.setSoTimeout(readTimeoutMs);
      return ssl;
    } catch (IOException e) {
      plain.close();
      throw e;
    }
  }

  private void writeRequest(Socket socket, URI uri, int port) throws IOException {
    String target = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    if (uri.getRawQuery() != null) {
      target += "?" + uri.getRawQuery();
    }
    String hostHeader = uri.getHost() + (port == defaultPort(uri.getScheme()) ? "" : ":" + port);
    String request = "GET " + target + " HTTP/1.1\r\n"
        + "Host: " + hostHeader + "\r\n"
        + "User-Agent: " + HttpUtil.USER_AGENT + "\r\n"
        + "Accept: text/html,application/xhtml+xml;q=0.9,*/*;q=0.8\r\n"
        + "Accept-Encoding: gzip\r\n"
        + "Accept-Language: en-US,en;q=0.9\r\n"
        + "Connection: close\r\n"
        + "\r\n";
    OutputStream out = socket.getOutputStream();
    out.write(request.getBytes(HEADER_CHARSET));
    out.flush();
  }

  private byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
    boolean chunked = headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked");
    boolean gzipped = "gzip".equalsIgnoreCase(firstToken(headers.get("content-encoding")));
    long declaredLength = contentLength(headers.get("content-length"));
    if (declaredLength > maxBodyBytes) {
      throw new FetchException(FetchError.TOO_LARGE, "Content-Length " + declaredLength + " exceeds the limit");
    }

    InputStream stream = chunked ? new ChunkedInputStream(in) : in;
    if (gzipped) {
      stream = new GZIPInputStream(stream);
    }
    int limit = (int) Math.min(maxBodyBytes, Integer.MAX_VALUE - 8);
    byte[] body = declaredLength >= 0 && !chunked && !gzipped
        ? stream.readNBytes((int) declaredLength)
        : stream.readNBytes(limit + 1);
    if (body.length > maxBodyBytes) {
      throw new FetchException(FetchError.TOO_LARGE, "Response body exceeds the limit");
    }
    return body;
  }

  private static Map<String, String> readHeaders(InputStream in) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    for (int lines = 0; ; lines++) {
      String line = readLine(in);
      if (line == null || line.isEmpty()) {
        return headers;
      }
      if (lines >= MAX_HEADER_LINES) {
        throw new FetchException(FetchError.FETCH_FAILED, "Too many response headers");
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      headers.merge(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
          line.substring(colon + 1).trim(), (first, second) -> first + ", " + second);
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        byte[] line = buffer.toByteArray();
        int length = line.length > 0 && line[line.length - 1] == '\r' ? line.length - 1 : line.length;
        return new String(line, 0, length, HEADER_CHARSET);
      }
      buffer.write(b);
    }
    return buffer.size() == 0 ? null : buffer.toString(HEADER_CHARSET);
  }

  private static int parseStatus(String statusLine) {
    String[] parts = statusLine.split(" ", 3);
    if (parts.length < 2) {
      throw new FetchException(FetchError.FETCH_FAILED, "Malformed status line: " + statusLine);
    }
    try {
      return Integer.parseInt(parts[1].trim());
    } catch (NumberFormatException e) {
      throw new FetchException(FetchError.FETCH_FAILED, "Malformed status line: " + statusLine);
    }
  }

  private static String resolveLocation(URI uri, String location) {
    if (location == null || location.isBlank()) {
      throw new FetchException(FetchError.FETCH_FAILED, "Redirect without Location");
    }
    try {
      return uri.resolve(location.trim()).toString();
    } catch (IllegalArgumentException e) {
      throw new FetchException(FetchError.FETCH_FAILED, "Invalid redirect location: " + location);
    }
  }

  private static boolean isHtml(Map<String, String> headers) {
    String contentType = headers.get("content-type");
    return contentType != null && firstToken(contentType).toLowerCase(Locale.ROOT).startsWith("text/html");
  }

  private static String decode(Response response) {
    return new String(response.body(), charsetOf(response.headers().get("content-type")));
  }

  private static Charset charsetOf(String contentType) {
    if (contentType != null) {
      for (String parameter : contentType.split(";")) {
        String trimmed = parameter.trim();
        if (trimmed.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
          try {
            return Charset.forName(trimmed.substring("charset=".length()).replace("\"", "").trim());
          } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
          }
        }
      }
    }
    return StandardCharsets.UTF_8;
  }

  private static void setServerName(SSLParameters parameters, String host) {
    try {
      parameters.setServerNames(List.of(new SNIHostName(host)));
    } catch (IllegalArgumentException e) {
      log.debug("[web] Not setting SNI for IP literal {}", host);   // e.g. https://93.184.216.34/
    }
  }

  private static String firstToken(String value) {
    if (value == null) {
      return "";
    }
    int semicolon = value.indexOf(';');
    return (semicolon < 0 ? value : value.substring(0, semicolon)).trim();
  }

  private static long contentLength(String value) {
    if (value == null) {
      return -1L;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  private static int portOf(URI uri) {
    return uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
  }

  private static int defaultPort(String scheme) {
    return "https".equalsIgnoreCase(scheme) ? 443 : 80;
  }

  private static String hostName(URI uri) {
    String host = uri.getHost();
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private record Response(int status, Map<String, String> headers, byte[] body) {
  }

  /**
   * Minimal {@code Transfer-Encoding: chunked} decoder; the JDK exposes no public equivalent and
   * {@code HttpClient} is unusable here because of the pinning requirement.
   */
  private static final class ChunkedInputStream extends InputStream {
    private final InputStream in;
    private long remaining;
    private boolean finished;

    private ChunkedInputStream(InputStream in) {
      this.in = in;
    }

    @Override
    public int read() throws IOException {
      byte[] single = new byte[1];
      int count = read(single, 0, 1);
      return count < 0 ? -1 : single[0] & 0xFF;
    }

    @Override
    public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
      if (finished) {
        return -1;
      }
      while (remaining == 0) {
        if (!readChunkSize()) {
          finished = true;
          return -1;
        }
      }
      int count = in.read(buffer, offset, (int) Math.min(length, remaining));
      if (count < 0) {
        throw new IOException("Unexpected end of chunked body");
      }
      remaining -= count;
      if (remaining == 0) {
        expectBlankLine();
      }
      return count;
    }

    private boolean readChunkSize() throws IOException {
      while (true) {
        String line = readLine(in);
        if (line == null) {
          throw new IOException("Missing chunk size");
        }
        int extension = line.indexOf(';');
        String size = (extension < 0 ? line : line.substring(0, extension)).trim();
        if (size.isEmpty()) {
          continue;
        }
        long value;
        try {
          value = Long.parseLong(size, 16);
        } catch (NumberFormatException e) {
          throw new IOException("Malformed chunk size: " + size);
        }
        if (value == 0) {
          skipTrailers();
          return false;
        }
        remaining = value;
        return true;
      }
    }

    private void expectBlankLine() throws IOException {
      String line = readLine(in);
      if (line == null || !line.isEmpty()) {
        throw new IOException("Malformed chunk terminator");
      }
    }

    private void skipTrailers() throws IOException {
      String trailer = readLine(in);
      while (trailer != null && !trailer.isEmpty()) {
        trailer = readLine(in);
      }
    }
  }
}
