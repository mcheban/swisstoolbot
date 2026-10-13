package org.cheban.swisstoolbot.bot;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.objects.FuelPrice;
import org.cheban.swisstoolbot.objects.LocationInfo;
import org.cheban.swisstoolbot.service.*;
import org.cheban.swisstoolbot.util.AbilityMethod;
import org.cheban.swisstoolbot.util.HtmlUtil;
import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.objects.WebResult;
import org.cheban.swisstoolbot.util.MarkdownUtil;
import org.cheban.swisstoolbot.util.ReplyMethod;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.abilitybots.api.objects.*;
import org.telegram.telegrambots.abilitybots.api.util.AbilityUtils;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.telegram.telegrambots.abilitybots.api.util.AbilityUtils.getChatId;

@Slf4j
@Component
@SuppressWarnings("java:S110")
public class SwissToolBot extends AbstractAbilityBot {
  private static final String PROMPT_SEARCH = "Search query:";
  private static final String PROMPT_IMG = "Image search query:";
  private static final String PROMPT_EXCH = "Conversion input:";
  private static final String PROMPT_ASK = "Prompt:";

  private final Set<Long> whitelist;
  private final GeocodeService geocodeService;
  private final WebSearchService webSearchService;
  private final AskService askService;
  private final WeatherService weatherService;
  private final ExchangeRateService exchangeRateService;
  private final FuelPriceService fuelPriceService;
  private ContextDb contextDb;

  public SwissToolBot(BotProperties botProperties,
                      TelegramClient telegramClient,
                      GeocodeService geocodeService,
                      WebSearchService webSearchService,
                      AskService askService,
                      WeatherService weatherService,
                      ExchangeRateService exchangeRateService,
                      FuelPriceService fuelPriceService) {
    super(telegramClient, botProperties.getUsername(), botProperties.getCreatorId());
    this.whitelist = botProperties.getWhitelist();
    this.geocodeService = geocodeService;
    this.webSearchService = webSearchService;
    this.weatherService = weatherService;
    this.exchangeRateService = exchangeRateService;
    this.fuelPriceService = fuelPriceService;
    this.askService = askService;
  }

  @PostConstruct
  void init() {
    onRegister();
    registerCommands();
  }

  private void registerCommands() {
    try {
      telegramClient.execute(SetMyCommands.builder()
          .command(new BotCommand("s", "Web search"))
          .command(new BotCommand("i", "Image search"))
          .command(new BotCommand("w", "Weather forecast"))
          .command(new BotCommand("ask", "Ask AI"))
          .command(new BotCommand("x", "Currency Exchange"))
          .command(new BotCommand("fuel", "NB maximum fuel prices"))
          .command(new BotCommand("l", "Set user location"))
          .command(new BotCommand("doc", "Fetch document by URL"))
          .build());
    } catch (TelegramApiException e) {
      log.error("Could not set commands", e);
    }
  }

  @Override
  public void consume(Update update) {
    this.contextDb = new ContextDb(db, getChatId(update));
    super.consume(update);
  }

  @Override
  protected boolean checkGlobalFlags(Update update) {
    Long userId = AbilityUtils.getUser(update).getId();
    if (!whitelist.contains(userId)) {
      log.error("Permission denied for: {}", userId);
      this.sendText(AbilityUtils.getChatId(update), "Permission denied for " + userId);
      return false;
    } else {
      return super.checkGlobalFlags(update);
    }
  }

  @AbilityMethod
  public Ability hideKeyboard() {
    return Ability.builder()
        .name("hidekb")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doRemoveKeyboard)
        .build();
  }

  @AbilityMethod
  public Ability webSearch() {
    return Ability.builder()
        .name("s")
        .info("Web Search")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doWebSearch)
        .build();
  }

  @AbilityMethod
  public Ability imageSearch() {
    return Ability.builder()
        .name("i")
        .info("Image Search")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doImageSearch)
        .build();
  }

  @AbilityMethod
  public Ability fetchDocument() {
    return Ability.builder()
        .name("doc")
        .info("Fetch document by URL")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doFetchDocument)
        .build();
  }

  @AbilityMethod
  public Ability setLocation() {
    return Ability.builder()
        .name("l")
        .info("Set user location")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doSetLocation)
        .build();
  }

  @AbilityMethod
  public Ability config() {
    return Ability.builder()
        .name("conf")
        .info("Change settings")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doConfig)
        .build();
  }

  @AbilityMethod
  public Ability weather() {
    return Ability.builder()
        .name("w")
        .info("Weather")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doWeather)
        .build();
  }

  @AbilityMethod
  public Ability exchange() {
    return Ability.builder()
        .name("x")
        .info("Currency Exchange")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doCurrencyExchange)
        .build();
  }

  @AbilityMethod
  public Ability fuelPrices() {
    return Ability.builder()
        .name("fuel")
        .info("Fuel Prices")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doFuelPrices)
        .build();
  }

  @AbilityMethod
  public Ability askAi() {
    return Ability.builder()
        .name("ask")
        .info("Ask AI")
        .locality(Locality.ALL)
        .privacy(Privacy.PUBLIC)
        .action(this::doAsk)
        .build();
  }

  @AbilityMethod
  public Ability handleDefaultReply() {
    return Ability.builder()
        .name(DEFAULT)
        .privacy(Privacy.PUBLIC)
        .locality(Locality.ALL)
        .action(this::doDefaultReply)
        .build();
  }

  private void doWebSearch(MessageContext ctx) {
    if (!this.requireArguments(ctx, PROMPT_SEARCH, "what to search")) return;
    this.performWebSearch(ctx.chatId(), String.join(" ", ctx.arguments()));
  }

  @ReplyMethod
  public Reply replyWebSearch() {
    return this.promptReply(PROMPT_SEARCH, this::performWebSearch);
  }

  private void performWebSearch(long chatId, String query) {
    List<WebResult> results = webSearchService.search(query, contextDb.getSearchNum());
    if (results.isEmpty()) {
      this.sendText(chatId, "No results");
    } else {
      results.forEach(r ->
          this.sendHtml(chatId, HtmlUtil.buildLink(r.url(), r.title()) + "\n\n" + r.description()));
    }
  }

  private void doImageSearch(MessageContext ctx) {
    if (!this.requireArguments(ctx, PROMPT_IMG, "what to look for")) return;
    this.performImageSearch(ctx.chatId(), String.join(" ", ctx.arguments()));
  }

  @ReplyMethod
  public Reply replyImageSearch() {
    return this.promptReply(PROMPT_IMG, this::performImageSearch);
  }

  private void performImageSearch(long chatId, String query) {
    List<ImageResult> results =
        webSearchService.searchImages(query, contextDb.getSearchNumImg());
    if (results.isEmpty()) {
      this.sendText(chatId, "No results");
    } else {
      List<InputMedia> inputMedia = results.stream().map(this::buildImageMediaWithData).toList();
      if (results.size() > 10 || !this.silentSendMediaGroup(SendMediaGroup.builder()
          .chatId(Long.toString(chatId))
          .medias(inputMedia)
          .build())) {
        AtomicInteger idx = new AtomicInteger(0);
        results.forEach(img -> this.silentSendPhoto(this.buildSendPhotoWithData(img, idx.getAndIncrement())
            .chatId(Long.toString(chatId))
            .build()));
      }
    }
  }

  private void doFetchDocument(MessageContext ctx) {
    this.checkArguments(ctx);
    this.silentSendDocument(SendDocument.builder()
        .chatId(Long.toString(ctx.chatId()))
        .document(new InputFile(String.join(" ", ctx.arguments())))
        .build());
  }

  private void doDefaultReply(MessageContext ctx) {
    Update update = ctx.update();
    Message msg = update.getMessage();
    if (msg == null) return; // edited messages, callback queries, etc.

    if (msg.hasLocation()) {
      this.doUpdateLocation(ctx);
      return;
    }
    if (!msg.hasText() || msg.isCommand() || msg.isReply()) return;
    if (!msg.isUserMessage()) return; // private chats only

    this.performAsk(ctx.chatId(), msg.getText());
  }

  private void doUpdateLocation(MessageContext ctx) {
    Update update = ctx.update();
    Double lat = update.getMessage().getLocation().getLatitude();
    Double lon = update.getMessage().getLocation().getLongitude();
    contextDb.updateLocationData(lat, lon, geocodeService.location(lat, lon));
    this.doRemoveKeyboard(ctx, "Thanks for sharing your location");
  }

  private void doSetLocation(MessageContext ctx) {
    if (ctx.arguments().length > 0) {
      String args = String.join(" ", ctx.arguments());
      if (args.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) {
        String[] coords = args.split(",");
        Double lat = Double.valueOf(coords[0]);
        Double lon = Double.valueOf(coords[1]);
        contextDb.updateLocationData(lat, lon, geocodeService.location(lat, lon));
        this.silent.send("Successfully changed your location", ctx.chatId());
      } else {
        LocationInfo location = geocodeService.coordinates(args);
        if (location != null) {
          contextDb.updateLocationData(location.lat(), location.lon(), location.name());
          this.silent.send("Successfully changed your location", ctx.chatId());
        } else {
          this.silent.send("Unable to change your location to '" + args + "'", ctx.chatId());
        }
      }
    } else {
      this.requestUserLocation(ctx);
    }
  }

  private void requestUserLocation(MessageContext ctx) {
    KeyboardButton shareLocationBtn = KeyboardButton.builder().text("Share Location").requestLocation(true).build();
    KeyboardRow row = new KeyboardRow(shareLocationBtn);
    ReplyKeyboardMarkup keyboardMarkup = ReplyKeyboardMarkup.builder()
        .keyboardRow(row)
        .resizeKeyboard(true)
        .oneTimeKeyboard(true)
        .build();
    this.silent.execute(SendMessage.builder()
        .chatId(Long.toString(ctx.chatId()))
        .text("Please share your location")
        .replyMarkup(keyboardMarkup)
        .build());
    contextDb.setLocationKeyboardVisible(true);
  }

  private void doRemoveKeyboard(MessageContext ctx) {
    this.doRemoveKeyboard(ctx, "Keyboard hidden");
  }

  private void doRemoveKeyboard(MessageContext ctx, String msg) {
    if (contextDb.isLocationKeyboardVisible()) {
      ReplyKeyboardRemove keyboardMarkup = ReplyKeyboardRemove.builder().removeKeyboard(true).build();
      SendMessage message = SendMessage.builder()
          .chatId(Long.toString(ctx.chatId()))
          .text(msg)
          .replyMarkup(keyboardMarkup)
          .build();
      contextDb.setLocationKeyboardVisible(false);
      this.silent.execute(message);
    }
  }

  private void doWeather(MessageContext ctx) {
    Double lat = contextDb.getLocationLatitude();
    Double lon = contextDb.getLocationLongitude();
    String locName = contextDb.getLocationName();

    if (ctx.arguments().length > 0) {
      String args = String.join(" ", ctx.arguments());
      if (args.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) {
        String[] coords = args.split(",");
        lat = Double.valueOf(coords[0]);
        lon = Double.valueOf(coords[1]);
        locName = geocodeService.location(lat, lon);
      } else {
        LocationInfo location = geocodeService.coordinates(args);
        if (location != null) {
          lat = location.lat();
          lon = location.lon();
          locName = location.name();
        }
      }
    }

    if (lat != null && lon != null) {
      weatherService.forecastMsg(locName, lat, lon)
          .ifPresent(msg -> this.sendText(ctx.chatId(), msg));
    } else {
      this.requestUserLocation(ctx);
    }
  }

  private void doConfig(MessageContext ctx) {
    if (ctx.arguments().length > 0) {
      ConfigOption config = ConfigOption.find(ctx.firstArg());
      if (config != null) {
        if (ctx.arguments().length == 1) {
          this.sendText(ctx.chatId(), config.userName() + "=" + config.getString(contextDb));
        } else {
          try {
            config.setString(ctx.secondArg(), contextDb);
            this.sendText(ctx.chatId(), config.userName() + "=" + config.getString(contextDb));
          } catch (Exception e) {
            this.sendText(ctx.chatId(), "Error writing config + \"" + config.userName() + "\": " + e.getMessage());
          }
        }
      } else {
        this.sendText(ctx.chatId(),
            "Unknown config name; Available names are: " +
                Arrays.stream(ConfigOption.values())
                    .map(ConfigOption::name)
                    .map(String::toLowerCase)
                    .collect(Collectors.joining(", ")));
      }
    } else {
      this.sendText(ctx.chatId(), ConfigOption.getConfigValues(contextDb).entrySet()
          .stream()
          .map(entry -> entry.getKey() + "=" + entry.getValue())
          .collect(Collectors.joining("\n")));
    }
  }

  private void doCurrencyExchange(MessageContext ctx) {
    if (!this.requireArguments(ctx, PROMPT_EXCH, "what to convert")) return;
    this.performCurrencyExchange(ctx.chatId(), String.join(" ", ctx.arguments()));
  }

  @ReplyMethod
  public Reply replyCurrencyExchange() {
    return this.promptReply(PROMPT_EXCH, this::performCurrencyExchange);
  }

  private void performCurrencyExchange(long chatId, String query) {
    exchangeRateService.convert(query)
        .ifPresentOrElse(msg -> this.sendText(chatId, msg),
            () -> this.sendText(chatId, "No results"));
  }

  private void doFuelPrices(MessageContext ctx) {
    List<FuelPrice> prices = fuelPriceService.getPrices();
    if (prices.isEmpty()) {
      this.sendText(ctx.chatId(), "Could not load fuel prices");
      return;
    }
    String msg = prices.stream()
        .map(p -> "%s: %s".formatted(p.name(), p.cents().stripTrailingZeros().toPlainString()))
        .collect(Collectors.joining("\n", "NB maximum fuel prices (incl. HST), ¢ per litre\n\n", ""));
    this.sendText(ctx.chatId(), msg);
  }

  private void doAsk(MessageContext ctx) {
    if (!this.requireArguments(ctx, PROMPT_ASK, "what to ask")) return;
    this.performAsk(ctx.chatId(), String.join(" ", ctx.arguments()));
  }

  @ReplyMethod
  public Reply replyAsk() {
    return this.promptReply(PROMPT_ASK, this::performAsk);
  }

  private void performAsk(long chatId, String query) {
    this.sendHtml(chatId, MarkdownUtil.toTelegramHtml(askService.ask(query)));
  }
}
