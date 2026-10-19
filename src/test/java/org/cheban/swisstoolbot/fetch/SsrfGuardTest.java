package org.cheban.swisstoolbot.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SsrfGuard")
class SsrfGuardTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "https://example.com",
      "http://example.com",
      "HTTPS://Example.com/Path?q=1",
      "example.com",                  // no scheme defaults to https
      "//example.com",                // protocol-relative
      "  https://example.com/a?b=1  " // surrounding whitespace
  })
  void acceptsHttpAndHttpsUrls(String url) {
    assertThat(SsrfGuard.validate(url).getHost()).isEqualTo("example.com");
  }

  @Test
  void lowercasesTheSchemeAndDropsTheFragment() {
    URI uri = SsrfGuard.validate("HTTPS://Example.com/Path?q=1#section");

    assertThat(uri.getScheme()).isEqualTo("https");
    assertThat(uri.toString()).isEqualTo("https://example.com/Path?q=1");
  }

  @Test
  void keepsAnExplicitPort() {
    URI uri = SsrfGuard.validate("http://example.com:8080/x");

    assertThat(uri.getPort()).isEqualTo(8080);
    assertThat(uri.toString()).isEqualTo("http://example.com:8080/x");
  }

  @Test
  @DisplayName("a hostname without a port is not mistaken for a scheme")
  void treatsHostPortWithoutSchemeAsHttps() {
    URI uri = SsrfGuard.validate("example.com:8080/x");

    assertThat(uri.getScheme()).isEqualTo("https");
    assertThat(uri.getHost()).isEqualTo("example.com");
    assertThat(uri.getPort()).isEqualTo(8080);
  }

  @Test
  void normalizesUnicodeHostnamesToPunycode() {
    URI hostWithoutPath = SsrfGuard.validate("https://münchen.example");
    URI hostWithPort = SsrfGuard.validate("http://täst.de:8080/x");

    assertThat(hostWithoutPath.getHost()).isEqualTo("xn--mnchen-3ya.example");
    assertThat(hostWithPort.getHost()).isEqualTo("xn--tst-qla.de");
    assertThat(hostWithPort.getPort()).isEqualTo(8080);
  }

  @Test
  void keepsIpv6LiteralHostsBracketed() {
    URI uri = SsrfGuard.validate("http://[::1]:8080/");

    assertThat(uri.getHost()).isEqualTo("[::1]");
    assertThat(uri.getPort()).isEqualTo(8080);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "",
      "   ",
      "https://",
      "https://example.com:99999/",
      "https://example.com:not-a-port/",
      "https://user:pass@example.com/",
      "https://exa mple.com/",
      "http://foo_bar.example/"  // authority java.net.URI cannot represent
  })
  void rejectsInvalidUrls(String url) {
    assertThat(errorOf(url)).isEqualTo(FetchError.INVALID_URL);
  }

  @Test
  void rejectsNullUrl() {
    assertThat(errorOf(null)).isEqualTo(FetchError.INVALID_URL);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "file:///etc/passwd",
      "ftp://example.com/",
      "gopher://example.com/",
      "javascript:alert(1)",
      "data:text/html,hi"
  })
  @DisplayName("schemes other than http/https are blocked")
  void rejectsNonHttpSchemes(String url) {
    assertThat(errorOf(url)).isEqualTo(FetchError.BLOCKED_URL);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "127.0.0.1",           // loopback
      "127.1.2.3",           // loopback range
      "10.0.0.1",            // private 10/8
      "10.255.255.255",
      "172.16.0.1",          // private 172.16/12
      "172.31.255.255",
      "192.168.1.1",         // private 192.168/16
      "169.254.169.254",     // cloud metadata
      "169.254.1.1",         // link-local
      "0.0.0.0",             // any-local
      "0.1.2.3",             // 0.0.0.0/8
      "100.64.0.1",          // carrier-grade NAT
      "192.0.0.1",           // IETF assignments
      "198.18.0.1",          // benchmarking
      "240.0.0.1",           // reserved
      "255.255.255.255",
      "224.0.0.1",           // multicast
      "::1",                 // IPv6 loopback
      "::",                  // IPv6 any-local
      "fc00::1",             // unique-local fc00::/7
      "fdff::1",
      "fe80::1",             // link-local fe80::/10
      "64:ff9b::7f00:1",     // NAT64 well-known prefix, translates to 127.0.0.1
      "64:ff9b:1::1",        // NAT64 local-use prefix
      "2002:7f00:1::",       // 6to4, embeds 127.0.0.1
      "2001:db8::1",         // documentation
      "100::1",              // discard-only
      "::ffff:127.0.0.1",    // IPv4-mapped loopback
      "::ffff:10.0.0.1",     // IPv4-mapped private
      "::ffff:0:127.0.0.1",  // IPv4-translated (SIIT) loopback
      "::127.0.0.1",         // IPv4-compatible loopback
      "::7f00:1"             // IPv4-compatible loopback, same address in hex
  })
  void blocksNonPublicAddresses(String address) throws Exception {
    assertThat(SsrfGuard.isBlocked(InetAddress.getByName(address))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "8.8.8.8",
      "1.1.1.1",
      "93.184.216.34",
      "172.32.0.1",          // just past 172.16/12
      "100.63.255.255",      // just below carrier-grade NAT
      "100.128.0.0",         // just above carrier-grade NAT
      "198.20.0.1",          // just past the benchmarking range
      "2001:4860:4860::8888",
      "2606:4700::1111"
  })
  void allowsPublicAddresses(String address) throws Exception {
    assertThat(SsrfGuard.isBlocked(InetAddress.getByName(address))).isFalse();
  }

  @Test
  @DisplayName("an IPv4-mapped address built from raw bytes is still recognised as blocked")
  void blocksIpv4MappedLoopbackBuiltFromRawBytes() throws Exception {
    byte[] mapped = new byte[16];
    mapped[10] = (byte) 0xFF;
    mapped[11] = (byte) 0xFF;
    mapped[12] = 127;
    mapped[15] = 1;

    assertThat(SsrfGuard.isBlocked(InetAddress.getByAddress(mapped))).isTrue();
  }

  @Test
  @DisplayName("deprecated IPv4-embedding forms built from raw bytes are blocked too")
  void blocksIpv4TranslatedAndCompatibleFormsBuiltFromRawBytes() throws Exception {
    byte[] translated = new byte[16];   // ::ffff:0:7f00:1, IPv4-translated (SIIT)
    translated[8] = (byte) 0xFF;
    translated[9] = (byte) 0xFF;
    translated[12] = 127;
    translated[15] = 1;

    byte[] compatible = new byte[16];   // ::7f00:1, IPv4-compatible
    compatible[12] = 127;
    compatible[15] = 1;

    assertThat(SsrfGuard.isBlocked(InetAddress.getByAddress(translated))).isTrue();
    assertThat(SsrfGuard.isBlocked(InetAddress.getByAddress(compatible))).isTrue();
    // A genuinely public IPv6 address stays allowed.
    assertThat(SsrfGuard.isBlocked(InetAddress.getByName("2001:4860:4860::8888"))).isFalse();
  }

  @Test
  @DisplayName("a host java.net.URI cannot represent is rejected by validate itself")
  void rejectsHostsWithoutARepresentableHost() {
    // A loopback shorthand resolves to a non-public address without any DNS lookup.
    assertThat(errorOf("http://127.1/")).isEqualTo(FetchError.BLOCKED_URL);
    assertThat(errorOf("http://foo_bar.example/")).isEqualTo(FetchError.INVALID_URL);
  }

  @Test
  void pinsTheFirstPublicAddressOfAnIpLiteral() throws Exception {
    assertThat(SsrfGuard.resolvePinned(SsrfGuard.validate("http://8.8.8.8/"))).isEqualTo(InetAddress.getByName("8.8.8.8"));
  }

  @Test
  void refusesLoopbackHosts() {
    assertThat(errorOfUrl("http://localhost/")).isEqualTo(FetchError.BLOCKED_URL);
    assertThat(errorOfUrl("http://[::1]/")).isEqualTo(FetchError.BLOCKED_URL);
  }

  @Test
  void refusesTheMetadataEndpoint() {
    assertThat(errorOfUrl("http://169.254.169.254/latest/meta-data/")).isEqualTo(FetchError.BLOCKED_URL);
  }

  @Test
  @DisplayName("isUrl accepts explicit http(s), www and bare host-like links")
  void isUrlAcceptsLinks() {
    assertThat(SsrfGuard.isUrl("http://example.com")).isTrue();
    assertThat(SsrfGuard.isUrl("https://example.com/path?q=1#fragment")).isTrue();
    assertThat(SsrfGuard.isUrl("HTTP://EXAMPLE.COM")).isTrue();
    assertThat(SsrfGuard.isUrl("  https://example.com/a  ")).isTrue();
    assertThat(SsrfGuard.isUrl("www.example.com")).isTrue();
    assertThat(SsrfGuard.isUrl("www.example.com/path")).isTrue();
    assertThat(SsrfGuard.isUrl("example.com")).isTrue();
    assertThat(SsrfGuard.isUrl("sub.example.co.uk/article")).isTrue();
    assertThat(SsrfGuard.isUrl("example.com:8080/path?q=1")).isTrue();
    assertThat(SsrfGuard.isUrl("8.8.8.8")).isTrue();
    assertThat(SsrfGuard.isUrl("8.8.8.8:8080/dns")).isTrue();
  }

  @Test
  @DisplayName("a bare host-like token is assumed to be HTTPS")
  void isUrlAssumesHttpsForBareHosts() {
    assertThat(SsrfGuard.validate("example.com").getScheme()).isEqualTo("https");
    assertThat(SsrfGuard.validate("example.com").toString()).isEqualTo("https://example.com/");
  }

  @Test
  @DisplayName("isUrl rejects free text, bare words and dotted non-hosts")
  void isUrlRejectsNonLinks() {
    assertThat(SsrfGuard.isUrl(null)).isFalse();
    assertThat(SsrfGuard.isUrl("")).isFalse();
    assertThat(SsrfGuard.isUrl("   ")).isFalse();
    assertThat(SsrfGuard.isUrl("hello")).isFalse();
    assertThat(SsrfGuard.isUrl("hello world")).isFalse();
    assertThat(SsrfGuard.isUrl("e.g")).isFalse();
    assertThat(SsrfGuard.isUrl("3.14")).isFalse();
    assertThat(SsrfGuard.isUrl("tell me about https://example.com")).isFalse();
    assertThat(SsrfGuard.isUrl("file:///etc/passwd")).isFalse();
    assertThat(SsrfGuard.isUrl("javascript:alert(1)")).isFalse();
    assertThat(SsrfGuard.isUrl("http://")).isFalse();
    assertThat(SsrfGuard.isUrl("http://exa mple.com")).isFalse();
  }

  private static FetchError errorOf(String url) {
    try {
      SsrfGuard.validate(url);
      throw new AssertionError("Expected " + url + " to be rejected");
    } catch (FetchException e) {
      return e.error();
    }
  }

  private static FetchError errorOfUrl(String url) {
    try {
      SsrfGuard.resolvePinned(SsrfGuard.validate(url));
      throw new AssertionError("Expected " + url + " to be rejected");
    } catch (FetchException e) {
      return e.error();
    }
  }
}
