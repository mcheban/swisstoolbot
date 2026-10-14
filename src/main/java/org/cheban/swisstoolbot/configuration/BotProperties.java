package org.cheban.swisstoolbot.configuration;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
@ConfigurationProperties("bot")
@Data
@ToString(onlyExplicitlyIncluded = true)
public class BotProperties {
  private String token;
  @ToString.Include
  private String username;
  @ToString.Include
  private long creatorId;
  @ToString.Include
  private Set<Long> whitelist;
  private String positionStackToken;
  private String openWeatherMapToken;
  private String braveSearchApiToken;
  private String braveAnswersApiToken;
}
