package org.cheban.swisstoolbot.bot;

import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.objects.LocationInfo;
import org.cheban.swisstoolbot.service.AskService;
import org.cheban.swisstoolbot.service.ExchangeRateService;
import org.cheban.swisstoolbot.service.FuelPriceService;
import org.cheban.swisstoolbot.service.GeocodeService;
import org.cheban.swisstoolbot.service.WeatherService;
import org.cheban.swisstoolbot.service.WebSearchService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.abilitybots.api.db.DBContext;
import org.telegram.telegrambots.abilitybots.api.objects.MessageContext;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the /l and /w abilities with every external interaction mocked: Telegram client,
 * geocoding and weather services. {@code contextDb} is a private field that production code only
 * initializes inside {@code consume(Update)}, so it is wired here over a throw-away chat id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SwissToolBot")
class SwissToolBotTest {
  private static final long USER_ID = 41689008L;

  /**
   * AbilityBot's 2-arg constructor uses its {@code botUsername} argument as the MapDB file path
   * (MapDBContext.onlineInstance), so the value below is deliberately a temp file and not a Telegram handle.
   */
  private static final String DB_FILE = System.getProperty("java.io.tmpdir") + "/swisstoolbot-abilities-test.db";

  private static final AtomicLong NEXT_CHAT_ID = new AtomicLong(7_000_000L);

  @Mock
  private TelegramClient telegramClient;
  @Mock
  private GeocodeService geocodeService;
  @Mock
  private WebSearchService webSearchService;
  @Mock
  private AskService askService;
  @Mock
  private WeatherService weatherService;
  @Mock
  private ExchangeRateService exchangeRateService;
  @Mock
  private FuelPriceService fuelPriceService;
  @Spy
  private BotProperties botProperties = testProperties();
  @InjectMocks
  private SwissToolBot swissToolBot;

  private long chatId;
  private ContextDb contextDb;

  private static BotProperties testProperties() {
    BotProperties properties = new BotProperties();
    properties.setUsername(DB_FILE);
    properties.setToken("123456789:test-token");
    properties.setCreatorId(USER_ID);
    properties.setWhitelist(Set.of(USER_ID));
    return properties;
  }

  @BeforeEach
  void wireContextDb() {
    chatId = NEXT_CHAT_ID.incrementAndGet();
    contextDb = new ContextDb(swissToolBot.getDb(), chatId);
    ReflectionTestUtils.setField(swissToolBot, "contextDb", contextDb);
  }

  @AfterEach
  void releaseDb() throws Exception {
    DBContext db = swissToolBot.getDb();
    db.clear();
    db.close();
  }

  @AfterAll
  static void deleteDbFiles() throws IOException {
    Path dir = Path.of(DB_FILE).getParent();
    try (Stream<Path> files = Files.list(dir)) {
      files.filter(p -> p.getFileName().startsWith(Path.of(DB_FILE).getFileName().toString())).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (IOException e) {
          throw new java.io.UncheckedIOException(e);
        }
      });
    }
  }

  private MessageContext contextWithArguments(String... arguments) {
    Chat chat = new Chat(chatId, "private");
    User user = new User(USER_ID, "tester", false);
    Message message = new Message();
    message.setMessageId(7);
    message.setChat(chat);
    message.setFrom(user);
    Update update = new Update();
    update.setMessage(message);
    return MessageContext.newContext(update, user, chatId, swissToolBot, arguments);
  }

  private SendMessage sentMessage() {
    ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
    try {
      verify(telegramClient).execute(captor.capture());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return captor.getValue();
  }

  private List<SendMessage> sentMessages() {
    ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
    try {
      verify(telegramClient, atLeastOnce()).execute(captor.capture());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return captor.getAllValues();
  }

  @Test
  @DisplayName("/l 46.15,-6.1 stores the pair and reverse-geocodes the name")
  void setLocationWithCoordinatePair() {
    when(geocodeService.location(46.15, -6.1)).thenReturn("Riverview, NB");

    swissToolBot.setLocation().action().accept(contextWithArguments("46.15,-6.1"));

    assertThat(contextDb.getLocationLatitude()).isEqualTo(46.15);
    assertThat(contextDb.getLocationLongitude()).isEqualTo(-6.1);
    assertThat(contextDb.getLocationName()).isEqualTo("Riverview, NB");
    assertThat(sentMessage().getText()).isEqualTo("Successfully changed your location");
    assertThat(sentMessage().getChatId()).isEqualTo(String.valueOf(chatId));
    verify(geocodeService, never()).coordinates(any());
  }

  @Test
  @DisplayName("/l splits the pair over several arguments with stray spaces")
  void setLocationWithCoordinatesSplitAcrossArguments() {
    when(geocodeService.location(46.15, -6.1)).thenReturn("Riverview, NB");

    swissToolBot.setLocation().action().accept(contextWithArguments(" 46.15 ", ",", "  -6.1"));

    assertThat(contextDb.getLocationLatitude()).isEqualTo(46.15);
    assertThat(contextDb.getLocationLongitude()).isEqualTo(-6.1);
    assertThat(sentMessage().getText()).isEqualTo("Successfully changed your location");
  }

  @Test
  @DisplayName("/l Moncton NB falls back to the place-name lookup")
  void setLocationWithPlaceName() {
    when(geocodeService.coordinates("Moncton NB")).thenReturn(new LocationInfo(46.09, -64.78, "Moncton, NB"));

    swissToolBot.setLocation().action().accept(contextWithArguments("Moncton", "NB"));

    assertThat(contextDb.getLocationLatitude()).isEqualTo(46.09);
    assertThat(contextDb.getLocationName()).isEqualTo("Moncton, NB");
    assertThat(sentMessage().getText()).isEqualTo("Successfully changed your location");
    verify(geocodeService, never()).location(anyDouble(), anyDouble());
  }

  @Test
  @DisplayName("/l on an unresolvable name reports the failure and keeps the old location")
  void setLocationWithUnknownPlace() {
    contextDb.updateLocationData(45.0, -65.0, "Old Place, NB");
    when(geocodeService.coordinates("Nowhereville")).thenReturn(null);

    swissToolBot.setLocation().action().accept(contextWithArguments("Nowhereville"));

    assertThat(contextDb.getLocationLatitude()).isEqualTo(45.0);
    assertThat(contextDb.getLocationName()).isEqualTo("Old Place, NB");
    assertThat(sentMessage().getText()).isEqualTo("Unable to change your location to 'Nowhereville'");
  }

  @Test
  @DisplayName("/l with an out-of-range pair is refused and never reverse-geocoded")
  void setLocationWithOutOfRangePair() {
    contextDb.updateLocationData(45.0, -65.0, "Old Place, NB");
    when(geocodeService.coordinates("999,999")).thenReturn(null);

    swissToolBot.setLocation().action().accept(contextWithArguments("999,999"));

    assertThat(contextDb.getLocationLatitude()).isEqualTo(45.0);
    assertThat(sentMessage().getText()).isEqualTo("Unable to change your location to '999,999'");
    verify(geocodeService, never()).location(anyDouble(), anyDouble());
  }

  @Test
  @DisplayName("/l without arguments asks the user to share a location instead of calling any service")
  void setLocationWithoutArgumentsRequestsSharedLocation() {
    swissToolBot.setLocation().action().accept(contextWithArguments());

    assertThat(sentMessage().getText()).isEqualTo("Please share your location");
    assertThat(sentMessage().getReplyMarkup()).isNotNull();
    assertThat(contextDb.isLocationKeyboardVisible()).isTrue();
    verifyNoInteractions(geocodeService, weatherService);
  }

  @Test
  @DisplayName("/w 46.15,-6.1 forecasts at the pair and does not overwrite the stored location")
  void weatherWithCoordinatePair() {
    when(geocodeService.location(46.15, -6.1)).thenReturn("Riverview, NB");
    when(weatherService.forecastMsg("Riverview, NB", 46.15, -6.1)).thenReturn(Optional.of("Sunny, 12 C"));

    swissToolBot.weather().action().accept(contextWithArguments("46.15, -6.1"));

    assertThat(sentMessage().getText()).isEqualTo("Sunny, 12 C");
    assertThat(sentMessage().getChatId()).isEqualTo(String.valueOf(chatId));
    assertThat(contextDb.getLocationLatitude()).isNull();
    assertThat(contextDb.getLocationName()).isNull();
    verify(geocodeService, never()).coordinates(any());
  }

  @Test
  @DisplayName("/w with a coordinate pair keeps asking for reverse geocoding even when it returns null")
  void weatherWithCoordinatePairAndUnresolvedName() {
    when(geocodeService.location(46.15, -6.1)).thenReturn(null);
    when(weatherService.forecastMsg(null, 46.15, -6.1)).thenReturn(Optional.of("Cloudy, 4 C"));

    swissToolBot.weather().action().accept(contextWithArguments("46.15,-6.1"));

    assertThat(sentMessage().getText()).isEqualTo("Cloudy, 4 C");
  }

  @Test
  @DisplayName("/w Moncton NB resolves the name first and never reverse-geocodes")
  void weatherWithPlaceName() {
    when(geocodeService.coordinates("Moncton NB")).thenReturn(new LocationInfo(46.09, -64.78, "Moncton, NB"));
    when(weatherService.forecastMsg("Moncton, NB", 46.09, -64.78)).thenReturn(Optional.of("Windy, 9 C"));

    swissToolBot.weather().action().accept(contextWithArguments("Moncton", "NB"));

    assertThat(sentMessage().getText()).isEqualTo("Windy, 9 C");
    verify(geocodeService, never()).location(anyDouble(), anyDouble());
  }

  @Test
  @DisplayName("/w without arguments uses the stored location")
  void weatherWithoutArgumentsUsesStoredLocation() {
    contextDb.updateLocationData(46.15, -6.1, "Riverview, NB");
    when(weatherService.forecastMsg("Riverview, NB", 46.15, -6.1)).thenReturn(Optional.of("Fog, 2 C"));

    swissToolBot.weather().action().accept(contextWithArguments());

    assertThat(sentMessage().getText()).isEqualTo("Fog, 2 C");
    verifyNoInteractions(geocodeService);
  }

  @Test
  @DisplayName("/w with no forecast to show sends nothing")
  void weatherWithEmptyForecastSendsNothing() {
    contextDb.updateLocationData(46.15, -6.1, "Riverview, NB");
    when(weatherService.forecastMsg("Riverview, NB", 46.15, -6.1)).thenReturn(Optional.empty());

    swissToolBot.weather().action().accept(contextWithArguments());

    verifyNoInteractions(telegramClient);
  }

  @Test
  @DisplayName("/w without arguments and without a stored location asks the user")
  void weatherWithoutArgumentsOrStoredLocationRequestsLocation() {
    swissToolBot.weather().action().accept(contextWithArguments());

    assertThat(sentMessages().getFirst().getText()).isEqualTo("Please share your location");
    assertThat(contextDb.isLocationKeyboardVisible()).isTrue();
    verifyNoInteractions(geocodeService, weatherService);
  }

  @Test
  @DisplayName("/w with a non-coordinate, unresolvable argument still uses the stored location")
  void weatherWithUnresolvableNameFallsBackToStoredLocation() {
    contextDb.updateLocationData(46.15, -6.1, "Riverview, NB");
    when(geocodeService.coordinates("Atlantis")).thenReturn(null);
    when(weatherService.forecastMsg("Riverview, NB", 46.15, -6.1)).thenReturn(Optional.of("Rain, 7 C"));

    swissToolBot.weather().action().accept(contextWithArguments("Atlantis"));

    assertThat(sentMessage().getText()).isEqualTo("Rain, 7 C");
  }

  @Test
  @DisplayName("/w with an out-of-range pair falls back to the stored location")
  void weatherWithOutOfRangePairFallsBackToStoredLocation() {
    contextDb.updateLocationData(46.15, -6.1, "Riverview, NB");
    when(geocodeService.coordinates("99.9,-6.1")).thenReturn(null);
    when(weatherService.forecastMsg("Riverview, NB", 46.15, -6.1)).thenReturn(Optional.of("Snow, -1 C"));

    swissToolBot.weather().action().accept(contextWithArguments("99.9,-6.1"));

    assertThat(sentMessage().getText()).isEqualTo("Snow, -1 C");
    verify(geocodeService, never()).location(anyDouble(), anyDouble());
  }
}
