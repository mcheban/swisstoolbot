package org.cheban.swisstoolbot.bot;

import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.fetch.FetchCache;
import org.cheban.swisstoolbot.fetch.FetchError;
import org.cheban.swisstoolbot.fetch.FetchOutcome;
import org.cheban.swisstoolbot.fetch.FetchRateLimiter;
import org.cheban.swisstoolbot.fetch.UrlFetchService;
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
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
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
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the /l, /w and /web abilities with every external interaction mocked: Telegram client,
 * geocoding, weather and web-fetch services. {@code contextDb} is a private field that production
 * code only initializes inside {@code consume(Update)}, so it is wired here over a throw-away chat id.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SwissToolBot")
class SwissToolBotTest {
  private static final long USER_ID = 41689008L;

  private static final String WEB_URL = "https://example.com/article";
  private static final int FETCHING_MESSAGE_ID = 42;

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
  @Mock
  private UrlFetchService urlFetchService;
  @Mock
  private FetchRateLimiter fetchRateLimiter;
  @Mock
  private FetchCache fetchCache;
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

  private MessageContext contextWithText(String text) {
    Chat chat = new Chat(chatId, "private");
    User user = new User(USER_ID, "tester", false);
    Message message = new Message();
    message.setMessageId(7);
    message.setChat(chat);
    message.setFrom(user);
    message.setText(text);
    Update update = new Update();
    update.setMessage(message);
    return MessageContext.newContext(update, user, chatId, swissToolBot);
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

  /**
   * Wires the /web pipeline as production does: the rate limiter admits the call and the cache runs
   * the loader, so a test only has to stub {@code urlFetchService.fetch(url)} to shape the outcome.
   */
  private void stubWebPipeline() {
    lenient().when(fetchRateLimiter.tryAcquire(anyLong())).thenReturn(true);
    lenient().when(fetchCache.getOrCompute(anyString(), any())).thenAnswer(invocation ->
        invocation.<Function<String, FetchOutcome>>getArgument(1).apply(invocation.getArgument(0)));
  }

  /** Stubs the "Fetching…" message so the /web reply has an id it can edit in place. */
  private void stubFetchingPrompt() {
    Message message = new Message();
    message.setMessageId(FETCHING_MESSAGE_ID);
    try {
      when(telegramClient.execute(any(SendMessage.class))).thenReturn(message);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private List<EditMessageText> editedMessages() {
    ArgumentCaptor<EditMessageText> captor = ArgumentCaptor.forClass(EditMessageText.class);
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

  @Test
  @DisplayName("/web without arguments asks for a URL and never calls the fetcher")
  void webWithoutArgumentsPromptsForUrl() {
    swissToolBot.web().action().accept(contextWithArguments());

    assertThat(sentMessage().getText()).isEqualTo("URL:");
    assertThat(sentMessage().getReplyMarkup()).isNotNull();
    verifyNoInteractions(urlFetchService);
  }

  @Test
  @DisplayName("/web trims stray whitespace off the URL before fetching")
  void webTrimsTheUrl() {
    stubFetchingPrompt();
    stubWebPipeline();
    when(urlFetchService.fetch(WEB_URL)).thenReturn(new FetchOutcome.Inline(List.of("<b>Title</b>")));

    swissToolBot.web().action().accept(contextWithArguments(" ", WEB_URL, " "));

    assertThat(editedMessages().getFirst().getText()).isEqualTo("<b>Title</b>");
  }

  @Test
  @DisplayName("/web <url> sends Fetching… and edits that same message with the inline HTML")
  void webWithInlineResultEditsTheFetchingMessage() {
    stubFetchingPrompt();
    stubWebPipeline();
    when(urlFetchService.fetch(WEB_URL)).thenReturn(new FetchOutcome.Inline(List.of("<b>Title</b>\n\nBody")));

    swissToolBot.web().action().accept(contextWithArguments(WEB_URL));

    assertThat(sentMessage().getText()).startsWith("Fetching");
    EditMessageText edited = editedMessages().getFirst();
    assertThat(edited.getChatId()).isEqualTo(String.valueOf(chatId));
    assertThat(edited.getMessageId()).isEqualTo(FETCHING_MESSAGE_ID);
    assertThat(edited.getText()).isEqualTo("<b>Title</b>\n\nBody");
    assertThat(edited.getParseMode()).isEqualTo(ParseMode.HTML);
    assertThat(edited.getLinkPreviewOptions().getIsDisabled()).isTrue();
  }

  @Test
  @DisplayName("/web edits the first rendered page and sends the remaining pages as new messages")
  void webWithMultipleMessagesEditsAndSendsTheRest() {
    stubFetchingPrompt();
    stubWebPipeline();
    when(urlFetchService.fetch(WEB_URL))
        .thenReturn(new FetchOutcome.Inline(List.of("page 1", "page 2")));

    swissToolBot.web().action().accept(contextWithArguments(WEB_URL));

    assertThat(editedMessages()).singleElement()
        .extracting(EditMessageText::getText).isEqualTo("page 1");
    List<SendMessage> sent = sentMessages();
    assertThat(sent.getLast().getText()).isEqualTo("page 2");
    assertThat(sent.getLast().getParseMode()).isEqualTo(ParseMode.HTML);
  }

  @Test
  @DisplayName("/web edits the message with a distinct text for every failure kind")
  void webEditsWithADistinctMessageForEveryFailure() {
    stubFetchingPrompt();
    stubWebPipeline();

    for (FetchError error : FetchError.values()) {
      when(urlFetchService.fetch(WEB_URL)).thenReturn(new FetchOutcome.Failure(error));
      swissToolBot.web().action().accept(contextWithArguments(WEB_URL));
    }

    assertThat(editedMessages()).extracting(EditMessageText::getText)
        .hasSize(FetchError.values().length)
        .doesNotContainNull()
        .doesNotHaveDuplicates()
        .contains("No readable content found: the page may need JavaScript or a subscription.");
  }

  @Test
  @DisplayName("/web sends the result as a new message when the Fetching prompt has no id")
  void webSendsTheResultWhenThePromptHasNoId() {
    stubWebPipeline();
    when(urlFetchService.fetch(WEB_URL)).thenReturn(new FetchOutcome.Inline(List.of("<b>Title</b>")));

    swissToolBot.web().action().accept(contextWithArguments(WEB_URL));

    assertThat(sentMessages()).hasSize(2);
    assertThat(sentMessages().getLast().getText()).isEqualTo("<b>Title</b>");
    assertThat(sentMessages().getLast().getParseMode()).isEqualTo(ParseMode.HTML);
  }

  @Test
  @DisplayName("/web replies with the rate-limited message and never fetches when over the limit")
  void webRateLimitedDoesNotFetch() {
    stubFetchingPrompt();
    when(fetchRateLimiter.tryAcquire(chatId)).thenReturn(false);

    swissToolBot.web().action().accept(contextWithArguments(WEB_URL));

    assertThat(editedMessages()).singleElement()
        .extracting(EditMessageText::getText)
        .isEqualTo("Too many requests, please try again in a moment.");
    verifyNoInteractions(urlFetchService, fetchCache);
  }

  @Test
  @DisplayName("/web serves every request through the cache instead of fetching directly")
  void webServesRequestsThroughTheCache() {
    stubFetchingPrompt();
    when(fetchRateLimiter.tryAcquire(anyLong())).thenReturn(true);
    when(fetchCache.getOrCompute(eq(WEB_URL), any())).thenReturn(new FetchOutcome.Inline(List.of("<b>Cached</b>")));

    swissToolBot.web().action().accept(contextWithArguments(WEB_URL));
    swissToolBot.web().action().accept(contextWithArguments(WEB_URL));

    assertThat(editedMessages()).hasSize(2)
        .allSatisfy(message -> assertThat(message.getText()).isEqualTo("<b>Cached</b>"));
    verify(fetchCache, times(2)).getOrCompute(eq(WEB_URL), any());
    verifyNoInteractions(urlFetchService);
  }

  @Test
  @DisplayName("a message that is just a URL is routed to the /web fetcher")
  void urlMessageIsFetched() {
    stubFetchingPrompt();
    stubWebPipeline();
    when(urlFetchService.fetch(WEB_URL)).thenReturn(new FetchOutcome.Inline(List.of("<b>Title</b>")));

    swissToolBot.handleDefaultReply().action().accept(contextWithText(WEB_URL));

    assertThat(editedMessages()).singleElement()
        .extracting(EditMessageText::getText).isEqualTo("<b>Title</b>");
  }

  @Test
  @DisplayName("a bare host-like message is treated as an https URL and fetched")
  void bareHostMessageIsFetched() {
    stubFetchingPrompt();
    stubWebPipeline();
    when(urlFetchService.fetch("example.com")).thenReturn(new FetchOutcome.Inline(List.of("<b>Title</b>")));

    swissToolBot.handleDefaultReply().action().accept(contextWithText("example.com"));

    assertThat(editedMessages()).singleElement()
        .extracting(EditMessageText::getText).isEqualTo("<b>Title</b>");
  }

  @Test
  @DisplayName("a plain text message still goes to the AI")
  void plainTextMessageGoesToAi() {
    when(askService.ask("hello")).thenReturn("hi");

    swissToolBot.handleDefaultReply().action().accept(contextWithText("hello"));

    verify(askService).ask("hello");
    verifyNoInteractions(urlFetchService);
  }

  @Test
  @DisplayName("/doc <url> still re-sends the raw URL as a document and never touches the /web pipeline")
  void docStillReSendsTheRawUrlAsADocument() {
    swissToolBot.fetchDocument().action().accept(contextWithArguments(WEB_URL));

    ArgumentCaptor<SendDocument> captor = ArgumentCaptor.forClass(SendDocument.class);
    try {
      verify(telegramClient).execute(captor.capture());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    SendDocument document = captor.getValue();
    assertThat(document.getChatId()).isEqualTo(String.valueOf(chatId));
    assertThat(document.getDocument().getAttachName()).isEqualTo(WEB_URL);
    verifyNoInteractions(urlFetchService, fetchRateLimiter, fetchCache);
  }
}
