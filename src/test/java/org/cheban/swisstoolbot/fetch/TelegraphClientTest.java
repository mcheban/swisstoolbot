package org.cheban.swisstoolbot.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.util.JsonUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives {@link TelegraphClient} against a local {@code HttpServer} stub, so the request shape and
 * every failure mapping are covered without ever calling the real telegra.ph API.
 */
@DisplayName("TelegraphClient")
class TelegraphClientTest {
  private static final String TOKEN = "telegraph-access-token";
  private static final String TITLE = "An Article";
  private static final String AUTHOR = "example.com";
  private static final List<Object> CONTENT = List.of(Map.of("tag", "p", "children", List.of("Hello")));

  private final AtomicReference<String> lastMethod = new AtomicReference<>();
  private final AtomicReference<String> lastContentType = new AtomicReference<>();
  private final AtomicReference<String> lastBody = new AtomicReference<>();
  private volatile int status = 200;
  private volatile String response = "{\"ok\":true,\"result\":{\"url\":\"https://telegra.ph/Article-10-17\","
      + "\"path\":\"Article-10-17\"}}";

  private HttpServer server;
  private TelegraphClient client;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    server.createContext("/createPage", this::handleCreatePage);
    server.start();

    BotProperties properties = new BotProperties();
    properties.setTelegraphToken(TOKEN);
    String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/createPage";
    client = new TelegraphClient(properties) {
      @Override
      String apiUrl() {
        return apiUrl;
      }
    };
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  @DisplayName("posts the page as JSON and returns the result URL")
  void postsPageAndReturnsUrl() {
    String url = client.createPage(TITLE, AUTHOR, CONTENT);

    assertThat(url).isEqualTo("https://telegra.ph/Article-10-17");
    assertThat(lastMethod.get()).isEqualTo("POST");
    assertThat(lastContentType.get()).startsWith("application/json");
    JsonNode body = JsonUtil.parseJsonNode(lastBody.get());
    assertThat(body.path("access_token").asText()).isEqualTo(TOKEN);
    assertThat(body.path("title").asText()).isEqualTo(TITLE);
    assertThat(body.path("author_name").asText()).isEqualTo(AUTHOR);
    assertThat(body.path("return_content").asBoolean()).isFalse();
    assertThat(body.path("content").size()).isEqualTo(1);
    assertThat(body.path("content").get(0).path("tag").asText()).isEqualTo("p");
  }

  @Test
  @DisplayName("builds the URL from the path when the response omits it")
  void buildsUrlFromPath() {
    response = "{\"ok\":true,\"result\":{\"path\":\"Article-10-17\"}}";

    assertThat(client.createPage(TITLE, AUTHOR, CONTENT)).isEqualTo("https://telegra.ph/Article-10-17");
  }

  @Test
  @DisplayName("fails when telegra.ph reports an error")
  void failsWhenApiRejectsTheRequest() {
    response = "{\"ok\":false,\"error\":\"CONTENT_TOO_BIG\"}";

    assertThatThrownBy(() -> client.createPage(TITLE, AUTHOR, CONTENT))
        .isInstanceOfSatisfying(FetchException.class,
            e -> assertThat(e.error()).isEqualTo(FetchError.TELEGRAPH_FAILED));
  }

  @Test
  @DisplayName("fails when the successful response carries no page URL")
  void failsWithoutPageUrl() {
    response = "{\"ok\":true,\"result\":{}}";

    assertThatThrownBy(() -> client.createPage(TITLE, AUTHOR, CONTENT))
        .isInstanceOfSatisfying(FetchException.class,
            e -> assertThat(e.error()).isEqualTo(FetchError.TELEGRAPH_FAILED));
  }

  @Test
  @DisplayName("fails on a non-200 response")
  void failsOnNonSuccessStatus() {
    status = 503;

    assertThatThrownBy(() -> client.createPage(TITLE, AUTHOR, CONTENT))
        .isInstanceOfSatisfying(FetchException.class,
            e -> assertThat(e.error()).isEqualTo(FetchError.TELEGRAPH_FAILED));
  }

  @Test
  @DisplayName("fails on a body that is not JSON")
  void failsOnMalformedResponse() {
    response = "<html>not json</html>";

    assertThatThrownBy(() -> client.createPage(TITLE, AUTHOR, CONTENT))
        .isInstanceOfSatisfying(FetchException.class,
            e -> assertThat(e.error()).isEqualTo(FetchError.TELEGRAPH_FAILED));
  }

  @Test
  @DisplayName("targets the public createPage endpoint by default")
  void targetsThePublicEndpoint() {
    assertThat(new TelegraphClient(new BotProperties()).apiUrl())
        .isEqualTo("https://api.telegra.ph/createPage");
  }

  private void handleCreatePage(HttpExchange exchange) throws IOException {
    lastMethod.set(exchange.getRequestMethod());
    lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
    lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
