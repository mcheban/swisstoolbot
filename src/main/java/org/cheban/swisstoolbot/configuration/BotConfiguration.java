package org.cheban.swisstoolbot.configuration;

import lombok.RequiredArgsConstructor;
import org.cheban.swisstoolbot.bot.SwissToolBot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@RequiredArgsConstructor
public class BotConfiguration {

  @Bean
  public TelegramClient telegramClient(BotProperties botProperties) {
    return new OkHttpTelegramClient(botProperties.getToken());
  }

  @Bean(destroyMethod = "close")
  public TelegramBotsLongPollingApplication telegramBotsApplication(
          BotProperties botProperties, SwissToolBot bot) throws TelegramApiException {
    TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
    botsApplication.registerBot(botProperties.getToken(), bot);
    return botsApplication;
  }
}
