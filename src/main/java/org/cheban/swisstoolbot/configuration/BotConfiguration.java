package org.cheban.swisstoolbot.configuration;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.cheban.swisstoolbot.bot.SwissToolBot;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
@RequiredArgsConstructor
public class BotConfiguration {
  private final SwissToolBot bot;

  @PostConstruct
  public void init() throws TelegramApiException {
    TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
    botsApi.registerBot(bot);
  }
}
