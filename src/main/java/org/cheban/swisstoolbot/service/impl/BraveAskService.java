package org.cheban.swisstoolbot.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.service.AskService;
import org.cheban.swisstoolbot.util.JsonUtil;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class BraveAskService implements AskService {
  private static final String API_URL = "https://api.search.brave.com/res/v1/chat/completions";
  private static final String MODEL = "brave";

  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();

  private final BotProperties botProperties;

  @Override
  public String ask(String query) {
    try {
      ChatRequest body = new ChatRequest();
      body.setModel(MODEL);
      body.setStream(false);
      body.setCountry("ca");
      body.setLanguage("en");
      body.setMessages(List.of(new Message("user", query)));   // API supports exactly one user message

      HttpRequest req = HttpRequest.newBuilder(URI.create(API_URL))
          .timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("X-Subscription-Token", botProperties.getBraveAnswersApiToken())
          .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.serializeObject(body)))
          .build();

      HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        log.error("Brave answers failed: {} {}", resp.statusCode(), resp.body());
        return errorMessage(resp);
      }

      ChatResponse parsed = JsonUtil.parseObject(resp.body(), ChatResponse.class);
      return parsed.text().map(String::trim).filter(s -> !s.isEmpty()).orElse("No answer");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "Could not get an answer";
    } catch (Exception e) {
      log.error("Brave answers error: {}", e.toString());
      return "Could not get an answer";
    }
  }

  private static String errorMessage(HttpResponse<String> resp) {
    try {
      ErrorResponse err = JsonUtil.parseObject(resp.body(), ErrorResponse.class);
      if (err.getError() != null) {
        return "Could not get an answer: %s%s".formatted(
            err.getError().getCode() != null ? err.getError().getCode() : "HTTP " + resp.statusCode(),
            err.getError().getDetail() != null ? " (" + err.getError().getDetail() + ")" : "");
      }
    } catch (Exception ignored) {
      // fall through
    }
    return "Could not get an answer (HTTP " + resp.statusCode() + ")";
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChatRequest {
    private String model;
    private boolean stream;
    private String country;
    private String language;
    private List<Message> messages;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Message {
    private String role;
    private String content;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ChatResponse {
    private List<Choice> choices;

    Optional<String> text() {
      if (choices == null || choices.isEmpty()) return Optional.empty();
      Choice c = choices.getFirst();
      if (c.getMessage() != null && c.getMessage().getContent() != null) {
        return Optional.of(c.getMessage().getContent());
      }
      if (c.getDelta() != null && c.getDelta().getContent() != null) {
        return Optional.of(c.getDelta().getContent());
      }
      return Optional.empty();
    }
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Choice {
    private Message message;   // OpenAI non-streaming shape
    private Message delta;     // shape documented in the schema
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ErrorResponse {
    private ApiError error;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ApiError {
    private String code;
    private String detail;
    private Integer status;
  }
}
