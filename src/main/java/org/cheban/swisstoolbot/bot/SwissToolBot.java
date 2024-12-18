package org.cheban.swisstoolbot.bot;

import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.configuration.BotProperties;
import org.cheban.swisstoolbot.objects.LocationInfo;
import org.cheban.swisstoolbot.service.GeocodeService;
import org.cheban.swisstoolbot.service.WeatherService;
import org.cheban.swisstoolbot.service.WebSearchService;
import org.cheban.swisstoolbot.util.AbilityMethod;
import org.cheban.swisstoolbot.util.HtmlUtil;
import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.objects.WebResult;
import org.springframework.stereotype.Component;
import org.telegram.abilitybots.api.objects.Ability;
import org.telegram.abilitybots.api.objects.Flag;
import org.telegram.abilitybots.api.objects.Locality;
import org.telegram.abilitybots.api.objects.MessageContext;
import org.telegram.abilitybots.api.objects.Privacy;
import org.telegram.abilitybots.api.util.AbilityUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.telegram.abilitybots.api.util.AbilityUtils.getChatId;

@Slf4j
@Component
@SuppressWarnings("java:S110")
public class SwissToolBot extends AbstractAbilityBot {
  private final Set<Long> whitelist;
  private final GeocodeService geocodeService;
  private final WebSearchService webSearchService;
  private final WeatherService weatherService;
  private ContextDb contextDb;

  public SwissToolBot(BotProperties botProperties,
                      GeocodeService geocodeService,
                      WebSearchService webSearchService,
                      WeatherService weatherService) {
    super(botProperties.getToken(), botProperties.getUsername(), botProperties.getCreatorId());
    this.whitelist = botProperties.getWhitelist();
    this.geocodeService = geocodeService;
    this.webSearchService = webSearchService;
    this.weatherService = weatherService;
  }

  @Override
  public void onUpdateReceived(Update update) {
    this.contextDb = new ContextDb(db(), getChatId(update));
    super.onUpdateReceived(update);
  }

  @Override
  protected boolean checkGlobalFlags(Update update) {
    Long userId = AbilityUtils.getUser(update).getId();
    if (!whitelist.contains(userId)) {
      this.sendText(AbilityUtils.getChatId(update), "Permission denied for " + userId);
      return false;
    } else {
      return super.checkGlobalFlags(update);
    }
  }

  @AbilityMethod
  public Ability updateLocation() {
    return Ability.builder()
            .name("default")
            .flag(Flag.LOCATION)
            .locality(Locality.ALL)
            .privacy(Privacy.PUBLIC)
            .action(this::doUpdateLocation)
            .build();
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

  private void doWebSearch(MessageContext ctx) {
    this.checkArguments(ctx);
    List<WebResult> results = webSearchService.search(String.join(" ", ctx.arguments()), contextDb.getSearchNum());
    if (results.isEmpty()) {
      this.sendText(ctx.chatId(), "No results");
    } else {
      results.forEach(r -> this.sendHtml(ctx.chatId(), HtmlUtil.buildLink(r.url(), r.title())));
    }
  }

  private void doImageSearch(MessageContext ctx) {
    this.checkArguments(ctx);
    List<ImageResult> results =
            webSearchService.searchImages(String.join(" ", ctx.arguments()), contextDb.getSearchNumImg());
    if (results.isEmpty()) {
      this.sendText(ctx.chatId(), "No results");
    } else {
      List<InputMedia> inputMedia = results.stream().map(this::buildImageMedia).toList();
      if (results.size() > 10 || !this.silentSendMediaGroup(new SendMediaGroup(Long.toString(ctx.chatId()), inputMedia))) {
        results.forEach(img -> this.silentSendPhoto(this.buildSendPhoto(img)
                .chatId(Long.toString(ctx.chatId()))
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
    ReplyKeyboardMarkup keyboardMarkup = new ReplyKeyboardMarkup();
    keyboardMarkup.setResizeKeyboard(true);
    keyboardMarkup.setOneTimeKeyboard(true);
    KeyboardButton shareLocationBtn = KeyboardButton.builder().text("Share Location").requestLocation(true).build();
    KeyboardRow row = new KeyboardRow();
    row.add(shareLocationBtn);
    keyboardMarkup.setKeyboard(List.of(row));
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
      SendMessage message = new SendMessage();
      message.setChatId(Long.toString(ctx.chatId()));
      message.setText(msg);
      ReplyKeyboardRemove keyboardMarkup = ReplyKeyboardRemove.builder().removeKeyboard(true).build();
      message.setReplyMarkup(keyboardMarkup);
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

}