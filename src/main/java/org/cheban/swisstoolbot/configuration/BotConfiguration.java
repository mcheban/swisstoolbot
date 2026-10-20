package org.cheban.swisstoolbot.configuration;

import lombok.RequiredArgsConstructor;
import org.cheban.swisstoolbot.bot.SwissToolBot;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@EnableCaching
@Configuration
@RequiredArgsConstructor
public class BotConfiguration {

  @Bean
  public TelegramClient telegramClient(BotProperties botProperties) {
    return new OkHttpTelegramClient(botProperties.getToken());
  }

  /**
   * Runs the blocking {@code /web} fetch off the bot's single update-consumer thread, so one slow
   * page cannot stall every other chat. Virtual threads are a good fit: each task blocks on a socket.
   */
  @Bean(destroyMethod = "close")
  public ExecutorService fetchExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean(destroyMethod = "close")
  public TelegramBotsLongPollingApplication telegramBotsApplication(
      BotProperties botProperties, SwissToolBot bot) throws TelegramApiException {
    TelegramBotsLongPollingApplication botsApplication = new TelegramBotsLongPollingApplication();
    botsApplication.registerBot(botProperties.getToken(), bot);
    return botsApplication;
  }
}
