package org.cheban.swisstoolbot.fetch;

import lombok.experimental.UtilityClass;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards every outbound fetch against server-side request forgery: only {@code http}/{@code https}
 * URLs survive {@link #validate(String)}, and only hosts that resolve to a publicly routable address
 * survive {@link #resolvePinned(URI)}. Callers must connect to the address returned by
 * {@code resolvePinned} instead of re-resolving the hostname, otherwise DNS rebinding can swap in an
 * internal address between validation and connect.
 */
@UtilityClass
public class SsrfGuard {
  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
  private static final int MAX_PORT = 65535;
  private static final int IPV4_MAPPED_PREFIX = 10;
  private static final Pattern HIERARCHICAL_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*://");
  private static final Pattern OPAQUE_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+\\-]*:");
  private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
  /**
   * A single link token: an explicit {@code http(s)://} URL, a bare domain (with an alphabetic TLD and
   * optional port/path) or a bare IPv4 literal. Dotted numbers that are not hosts ({@code e.g} is a
   * single-letter TLD, {@code 3.14} a non-alphabetic one) and words without a dot do not match.
   */
  private static final Pattern LINK = Pattern.compile(
      "(?i)^(?:"
          + "https?://\\S+"
          + "|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?"
          + "|(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?:[/?#]\\S*)?"
          + ")$");

  /**
   * True when {@code text} is a single link token that {@link #validate(String)} accepts. Used to
   * route free-form messages to the fetcher while reusing the same URL rules; a bare host is assumed
   * to be HTTPS by {@code validate}. The gate keeps ordinary words from being treated as URLs.
   */
  public static boolean isUrl(String text) {
    if (text == null) {
      return false;
    }
    String candidate = text.trim();
    if (!LINK.matcher(candidate).matches()) {
      return false;
    }
    try {
      validate(candidate);
      return true;
    } catch (FetchException e) {
      return false;
    }
  }

  /**
   * Parses and normalizes a user-supplied URL into an absolute, IDN-normalized {@code http}/{@code https}
   * URI. A missing scheme defaults to {@code https}. Credentials, unsupported schemes and malformed
   * ports are rejected instead of being silently dropped.
   */
  public static URI validate(String url) {
    URI uri;
    try {
      uri = new URI(absolute(url));
    } catch (URISyntaxException e) {
      throw invalid("Malformed URL");
    }

    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!ALLOWED_SCHEMES.contains(scheme)) {
      throw blocked("Unsupported URL scheme: " + uri.getScheme());
    }
    if (uri.getRawUserInfo() != null) {
      throw invalid("Credentials are not allowed in the URL");
    }

    // java.net.URI leaves non-ASCII hosts unresolved, so those have to be read off the raw authority.
    String rawHost = uri.getHost();
    int port = uri.getPort();
    if (rawHost == null || rawHost.isEmpty()) {
      String authority = uri.getRawAuthority();
      if (authority == null || authority.isEmpty()) {
        throw invalid("Missing host");
      }
      if (authority.lastIndexOf('@') >= 0) {
        throw invalid("Credentials are not allowed in the URL");
      }
      if (authority.startsWith("[")) {
        int end = authority.indexOf(']');
        if (end < 0) {
          throw invalid("Malformed IPv6 host");
        }
        rawHost = authority.substring(0, end + 1);
        String rest = authority.substring(end + 1);
        if (rest.startsWith(":")) {
          port = parsePort(rest.substring(1));
        } else if (!rest.isEmpty()) {
          throw invalid("Malformed host");
        }
      } else {
        int colon = authority.lastIndexOf(':');
        if (colon >= 0) {
          rawHost = authority.substring(0, colon);
          port = parsePort(authority.substring(colon + 1));
        } else {
          rawHost = authority;
        }
      }
    }
    if (rawHost.isEmpty()) {
      throw invalid("Missing host");
    }
    if (port < -1 || port > MAX_PORT) {
      throw invalid("Invalid port: " + port);
    }

    String host = normalizeHost(rawHost);
    String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
    String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
    String normalized = scheme + "://" + host + (port == -1 ? "" : ":" + port) + path + query;
    URI result;
    try {
      result = URI.create(normalized);
    } catch (IllegalArgumentException e) {
      throw invalid("Malformed URL");
    }
    if (result.getHost() == null) {
      // java.net.URI drops the host for authorities it cannot represent (for example the loopback
      // shorthand 127.1 or a name containing an underscore). Refuse them here so the returned URI
      // always has a host and callers never have to fail later in resolvePinned.
      throw nonRepresentableHost(host);
    }
    return result;
  }

  /**
   * Resolves the URI host and returns the first address that is safe to connect to. When the hostname
   * resolves to nothing but blocked addresses the request is refused as {@code BLOCKED_URL}.
   */
  public static InetAddress resolvePinned(URI uri) {
    String host = hostForAddress(uri.getHost());
    if (host.isEmpty()) {
      throw invalid("Missing host");
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new FetchException(FetchError.FETCH_FAILED, "Cannot resolve host: " + host);
    }
    for (InetAddress address : addresses) {
      if (!isBlocked(address)) {
        return address;
      }
    }
    throw blocked("Host resolves to a non-public address: " + host);
  }

  /**
   * True for addresses that must never be reached by a user-supplied URL: loopback, any-local,
   * link-local (including the {@code 169.254.169.254} metadata endpoint), site-local, multicast,
   * carrier-grade NAT, reserved blocks, IPv6 unique-local, IPv4-mapped IPv6 forms and IPv6 ranges
   * that embed or translate an IPv4 address.
   */
  public static boolean isBlocked(InetAddress address) {
    if (address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return true;
    }

    byte[] bytes = address.getAddress();
    if (bytes.length == 16) {
      if (isIpv4Mapped(bytes)) {
        return true;
      }
      // fc00::/7 unique-local, fe80::/10 link-local (java.net reports the latter as link-local already)
      if ((bytes[0] & 0xFE) == 0xFC || (bytes[0] == (byte) 0xFE && (bytes[1] & 0xC0) == 0x80)) {
        return true;
      }
      // Ranges that carry an embedded or translated IPv4 address, plus other non-global blocks: the
      // deprecated IPv4-translated ::ffff:0:0:0/96 (SIIT) and IPv4-compatible ::/96 forms, NAT64
      // well-known 64:ff9b::/96 and local-use 64:ff9b:1::/48, 6to4 2002::/16, documentation
      // 2001:db8::/32 and discard-only 100::/64. On an IPv6/NAT64-only network these are the classic
      // way to smuggle a request to an internal IPv4 host past a check that only inspects native
      // IPv6 ranges. The mapped ::ffff:0:0/96 form is handled by isIpv4Mapped above.
      return hasPrefix(bytes, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00) // ::ffff:0:0:0/96 IPv4-translated
          || hasPrefix(bytes, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // ::/96 IPv4-compatible
          || hasPrefix(bytes, 0x00, 0x64, 0xFF, 0x9B, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00) // 64:ff9b::/96
          || hasPrefix(bytes, 0x00, 0x64, 0xFF, 0x9B, 0x00, 0x01)                                  // 64:ff9b:1::/48
          || hasPrefix(bytes, 0x20, 0x02)                                                            // 2002::/16
          || hasPrefix(bytes, 0x20, 0x01, 0x0D, 0xB8)                                                // 2001:db8::/32
          || hasPrefix(bytes, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00);                       // 100::/64
    }
    if (bytes.length == 4) {
      int first = bytes[0] & 0xFF;
      int second = bytes[1] & 0xFF;
      return first == 0                                        // 0.0.0.0/8 "this network"
          || (first == 100 && second >= 64 && second <= 127)   // 100.64.0.0/10 carrier-grade NAT
          || (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0)  // 192.0.0.0/24 IETF assignments
          || (first == 198 && (second == 18 || second == 19))  // 198.18.0.0/15 benchmarking
          || first >= 240;                                     // 240.0.0.0/4 reserved
    }
    return true;
  }

  private static String absolute(String url) {
    String candidate = url == null ? "" : url.trim();
    if (candidate.isEmpty()) {
      throw invalid("Missing URL");
    }
    if (!HIERARCHICAL_SCHEME.matcher(candidate).lookingAt()
        && !OPAQUE_SCHEME.matcher(candidate).lookingAt()) {
      candidate = candidate.startsWith("//") ? "https:" + candidate : "https://" + candidate;
    }
    return candidate;
  }

  private static String normalizeHost(String host) {
    if (host.startsWith("[") && host.endsWith("]")) {
      return "[" + host.substring(1, host.length() - 1).toLowerCase(Locale.ROOT) + "]";
    }
    try {
      return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      throw invalid("Invalid host: " + host);
    }
  }

  private static String hostForAddress(String host) {
    if (host == null) {
      return "";
    }
    return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
  }

  private static int parsePort(String value) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      throw invalid("Invalid port: " + value);
    }
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < IPV4_MAPPED_PREFIX; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }

  private static boolean hasPrefix(byte[] bytes, int... prefix) {
    if (bytes.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((bytes[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Refuses a host that {@link URI} cannot represent (which would leave the validated URI host-less).
   * Purely numeric forms are resolved locally, without DNS, so a loopback shorthand such as
   * {@code 127.1} is reported as {@code BLOCKED_URL} just like its canonical form; everything else is
   * {@code INVALID_URL}.
   */
  private static FetchException nonRepresentableHost(String host) {
    if (NUMERIC_HOST.matcher(host).matches()) {
      try {
        if (isBlocked(InetAddress.getByName(host))) {
          return blocked("Host resolves to a non-public address: " + host);
        }
      } catch (UnknownHostException ignored) {
        // Not a numeric literal after all.
      }
    }
    return invalid("Invalid host: " + host);
  }

  private static FetchException invalid(String message) {
    return new FetchException(FetchError.INVALID_URL, message);
  }

  private static FetchException blocked(String message) {
    return new FetchException(FetchError.BLOCKED_URL, message);
  }
}
