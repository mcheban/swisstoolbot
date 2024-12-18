package org.cheban.swisstoolbot.bot;

import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.util.HtmlUtil;
import org.telegram.abilitybots.api.bot.AbilityBot;
import org.telegram.abilitybots.api.objects.MessageContext;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Slf4j
public abstract class AbstractAbilityBot extends AbilityBot {
  private final long creatorId;

  protected AbstractAbilityBot(String botToken, String botUsername, long creatorId) {
    super(botToken, botUsername);
    this.creatorId = creatorId;
  }

  public long creatorId() {
    return creatorId;
  }

  protected void checkArguments(MessageContext ctx) {
    if (ctx.arguments().length == 0) {
      this.sendText(ctx.chatId(), "No arguments provided");
      throw new IllegalArgumentException();
    }
  }

  protected void sendText(Long chatId, String msg) {
    this.silent.execute(SendMessage.builder()
            .chatId(Long.toString(chatId))
            .text(msg)
            .build());
  }

  protected void sendHtml(Long chatId, String msgHtml) {
    this.silent.execute(SendMessage.builder()
            .chatId(Long.toString(chatId))
            .text(msgHtml)
            .parseMode("HTML")
            .build());
  }

  protected InputMedia buildImageMedia(ImageResult image) {
    log.info("[buildImageMedia] {}", image.src());
    return InputMediaPhoto.builder()
            .media(image.src())
            .caption(HtmlUtil.buildLink(image.url(), image.title()))
            .parseMode("HTML")
            .build();
  }

  protected SendPhoto.SendPhotoBuilder buildSendPhoto(ImageResult image) {
    log.info("[buildSendPhoto] {}", image.src());
    return SendPhoto.builder()
            .photo(new InputFile(image.src()))
            .caption(HtmlUtil.buildLink(image.url(), image.title()))
            .parseMode("HTML");
  }

  protected boolean silentSendMediaGroup(SendMediaGroup method) {
    try {
      this.execute(method);
      return true;
    } catch (TelegramApiException e) {
      log.error("Could not sendMediaGroup", e);
      return false;
    }
  }

  protected void silentSendPhoto(SendPhoto method) {
    try {
      this.execute(method);
    } catch (TelegramApiException e) {
      log.error("Could not send photo", e);
    }

  }

  protected void silentSendDocument(SendDocument method) {
    try {
      this.execute(method);
    } catch (TelegramApiException e) {
      log.error("Could not sendDocument", e);
    }
  }
}
