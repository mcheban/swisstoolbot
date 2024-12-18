package org.cheban.swisstoolbot.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@ConfigurationProperties("bot")
@Data
public class BotProperties {
  private String token;
  private String username;
  private long creatorId;
  private Set<Long> whitelist;
  private String positionStackToken;
  private String openWeatherMapToken;
}
