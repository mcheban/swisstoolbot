package org.cheban.swisstoolbot.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.abilitybots.api.db.DBContext;
import org.telegram.telegrambots.abilitybots.api.db.MapDBContext;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the ConfigOption &lt;-&gt; ContextDb storage contract: an option must be written and read
 * under the same chat-prefixed key, unresolved options fall back to {@link ConfigOption#getDefaultValue()},
 * and {@code /config} rendering must survive both cases.
 */
class ContextDbConfigOptionTest {
  private static final long CHAT_ID = 41689008L;
  private static final long OTHER_CHAT_ID = 38164873L;

  @TempDir
  Path tempDir;

  private DBContext db;
  private ContextDb contextDb;

  @BeforeEach
  void openDb() {
    db = MapDBContext.offlineInstance(tempDir.resolve("botdb").toString());
    contextDb = new ContextDb(db, CHAT_ID);
  }

  @AfterEach
  void closeDb() throws Exception {
    db.clear();
    db.close();
  }

  @Test
  @DisplayName("unset options resolve to their declared defaults (5 / 10)")
  void usesDefaultsWhenNothingStored() {
    assertThat(contextDb.getSearchNum()).isEqualTo(5);
    assertThat(contextDb.getSearchNumImg()).isEqualTo(10);
    assertThat(ConfigOption.SEARCH_NUM.getValue(contextDb)).isEqualTo(5);
    assertThat(ConfigOption.SEARCH_NUM_IMG.getValue(contextDb)).isEqualTo(10);
  }

  @Test
  @DisplayName("/config listing renders numbers, not null and not an NPE")
  void configListingRendersDefaults() {
    assertThat(ConfigOption.getConfigValues(contextDb))
        .hasSize(2)
        .containsEntry("search_num", "5")
        .containsEntry("search_num_img", "10");
  }

  @Test
  @DisplayName("a written option is read back through every accessor")
  void writtenValuesAreReadBack() {
    ConfigOption.SEARCH_NUM.setString("7", contextDb);
    ConfigOption.SEARCH_NUM_IMG.setString("3", contextDb);

    assertThat(contextDb.getSearchNum()).isEqualTo(7);
    assertThat(contextDb.getSearchNumImg()).isEqualTo(3);
    assertThat(ConfigOption.getConfigValues(contextDb))
        .containsEntry("search_num", "7")
        .containsEntry("search_num_img", "3");
  }

  @Test
  @DisplayName("read path uses exactly the key the write path stored under")
  void optionReadAndWriteShareOneKey() {
    ConfigOption.SEARCH_NUM.setString("7", contextDb);

    assertThat(db.contains(CHAT_ID + ":SEARCH_NUM")).isTrue();
    assertThat(contextDb.<Integer> getVar("SEARCH_NUM")).isEqualTo(7);

    contextDb.getSearchNum();

    assertThat(db.contains(CHAT_ID + ":" + CHAT_ID + ":SEARCH_NUM"))
        .as("reading must not invent a second, double chat-prefixed key")
        .isFalse();
  }

  @Test
  @DisplayName("option values are isolated per chat")
  void valuesAreChatScoped() {
    ConfigOption.SEARCH_NUM.setString("7", contextDb);
    ContextDb otherChat = new ContextDb(db, OTHER_CHAT_ID);

    assertThat(otherChat.getSearchNum()).isEqualTo(5);
    assertThat(contextDb.getSearchNum()).isEqualTo(7);
  }

  @Test
  @DisplayName("non-numeric input is rejected and leaves the default in place")
  void rejectsNonNumericValue() {
    assertThatThrownBy(() -> ConfigOption.SEARCH_NUM.setString("abc", contextDb))
        .isInstanceOf(NumberFormatException.class);

    assertThat(contextDb.getSearchNum()).isEqualTo(5);
  }

  @Test
  @DisplayName("find() resolves lower-cased user names")
  void findResolvesUserNames() {
    assertThat(ConfigOption.find("search_num")).isSameAs(ConfigOption.SEARCH_NUM);
    assertThat(ConfigOption.find("search_num_img")).isSameAs(ConfigOption.SEARCH_NUM_IMG);
    assertThat(ConfigOption.find("bogus")).isNull();
  }
}
