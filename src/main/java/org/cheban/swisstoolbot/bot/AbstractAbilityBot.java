package org.cheban.swisstoolbot.bot;

import lombok.extern.slf4j.Slf4j;
import org.cheban.swisstoolbot.objects.ImageResult;
import org.cheban.swisstoolbot.util.HtmlUtil;
import org.telegram.telegrambots.abilitybots.api.bot.AbilityBot;
import org.telegram.telegrambots.abilitybots.api.objects.Flag;
import org.telegram.telegrambots.abilitybots.api.objects.MessageContext;
import org.telegram.telegrambots.abilitybots.api.objects.Reply;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.media.InputMedia;
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.ByteArrayInputStream;
import java.util.function.BiConsumer;

@Slf4j
public abstract class AbstractAbilityBot extends AbilityBot {
  private final long creatorId;

  protected AbstractAbilityBot(TelegramClient telegramClient, String botUsername, long creatorId) {
    super(telegramClient, botUsername);
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

  /** @return true if arguments are present; otherwise sends a force-reply prompt and returns false */
  protected boolean requireArguments(MessageContext ctx, String prompt, String placeholder) {
    if (ctx.arguments().length > 0) return true;
    try {
      this.telegramClient.execute(SendMessage.builder()
              .chatId(Long.toString(ctx.chatId()))
              .text(prompt)
              .replyToMessageId(ctx.update().getMessage().getMessageId())
              .replyMarkup(ForceReplyKeyboard.builder()
                      .forceReply(true)
                      .selective(true)
                      .inputFieldPlaceholder(placeholder)
                      .build())
              .build());
    } catch (TelegramApiException e) {
      log.error("Could not send prompt", e);
    }
    return false;
  }

  protected Reply promptReply(String prompt, BiConsumer<Long, String> action) {
    return Reply.of(
            (_, upd) -> action.accept(upd.getMessage().getChatId(), upd.getMessage().getText()),
            Flag.MESSAGE, Flag.TEXT, Flag.REPLY,
            upd -> prompt.equals(upd.getMessage().getReplyToMessage().getText()));
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
        .parseMode(ParseMode.HTML)
        .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
        .build());
  }

  @SuppressWarnings("unused")
  protected void sendMarkdown(Long chatId, String msgMarkdown) {
    this.silent.execute(SendMessage.builder()
        .chatId(Long.toString(chatId))
        .text(msgMarkdown)
        .parseMode(ParseMode.MARKDOWNV2)
        .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
        .build());
  }

  /**
   * Sends a plain-text message and returns its id, so the caller can later replace it in place.
   *
   * @return the id of the sent message, or {@code null} when Telegram rejected it
   */
  protected Integer sendTextAndGetId(Long chatId, String msg) {
    try {
      Message sent = this.telegramClient.execute(SendMessage.builder()
          .chatId(Long.toString(chatId))
          .text(msg)
          .build());
      return sent == null ? null : sent.getMessageId();
    } catch (TelegramApiException e) {
      log.error("Could not send message", e);
      return null;
    }
  }

  /** Replaces an earlier message with HTML, so long-running requests do not spam the chat. */
  protected void editHtml(Long chatId, Integer messageId, String msgHtml) {
    try {
      this.telegramClient.execute(EditMessageText.builder()
          .chatId(Long.toString(chatId))
          .messageId(messageId)
          .text(msgHtml)
          .parseMode(ParseMode.HTML)
          .linkPreviewOptions(LinkPreviewOptions.builder().isDisabled(true).build())
          .build());
    } catch (TelegramApiException e) {
      log.error("Could not edit message", e);
    }
  }

  @SuppressWarnings("unused")
  protected InputMedia buildImageMedia(ImageResult image) {
    log.info("[buildImageMedia] {}", image.src());
    return InputMediaPhoto.builder()
        .media(image.src())
        .caption(HtmlUtil.buildLink(image.url(), image.title()))
        .parseMode(ParseMode.HTML)
        .build();
  }

  protected InputMedia buildImageMediaWithData(ImageResult image) {
    log.info("[buildImageMediaWithData] {}", image.src());

    return InputMediaPhoto.builder()
        .media(new ByteArrayInputStream(image.data()), HtmlUtil.uploadName(image.src(), image.ext(), 0))
        .caption(HtmlUtil.buildLink(image.url(), image.title()))
        .parseMode(ParseMode.HTML)
        .build();
  }

  @SuppressWarnings("unused")
  protected SendPhoto.SendPhotoBuilder<?, ?> buildSendPhoto(ImageResult image) {
    log.info("[buildSendPhoto] {}", image.src());
    return SendPhoto.builder()
        .photo(new InputFile(image.src()))
        .caption(HtmlUtil.buildLink(image.url(), image.title()))
        .parseMode(ParseMode.HTML);
  }

  protected SendPhoto.SendPhotoBuilder<?, ?> buildSendPhotoWithData(ImageResult image, int num) {
    log.info("[buildSendPhotoWithData] {}", image.src());
    return SendPhoto.builder()
        .photo(new InputFile(new ByteArrayInputStream(image.data()), HtmlUtil.uploadName(image.src(), image.ext(), num)))
        .caption(HtmlUtil.buildLink(image.url(), image.title()))
        .parseMode(ParseMode.HTML);
  }

  protected boolean silentSendMediaGroup(SendMediaGroup method) {
    try {
      this.telegramClient.execute(method);
      return true;
    } catch (TelegramApiException e) {
      log.error("Could not sendMediaGroup", e);
      return false;
    }
  }

  protected void silentSendPhoto(SendPhoto method) {
    try {
      this.telegramClient.execute(method);
    } catch (TelegramApiException e) {
      log.error("Could not send photo", e);
    }

  }

  protected void silentSendDocument(SendDocument method) {
    try {
      this.telegramClient.execute(method);
    } catch (TelegramApiException e) {
      log.error("Could not sendDocument", e);
    }
  }
}
