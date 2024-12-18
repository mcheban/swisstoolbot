package org.cheban.swisstoolbot.bot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Getter
@RequiredArgsConstructor
public enum ConfigOption {
  SEARCH_NUM(5) {
    @Override
    void setString(String value, ContextDb contextDb) {
      contextDb.setSearchNum(Integer.parseInt(value));
    }

    @Override
    String getString(ContextDb contextDb) {
      return String.valueOf(contextDb.getSearchNum());
    }
  },
  SEARCH_NUM_IMG(5) {
    @Override
    void setString(String value, ContextDb contextDb) {
      contextDb.setSearchNumImg(Integer.parseInt(value));
    }

    @Override
    String getString(ContextDb contextDb) {
      return String.valueOf(contextDb.getSearchNumImg());
    }
  };

  private final Object defaultValue;

  public static ConfigOption find(String name) {
    for (ConfigOption item : values()) {
      if (item.name().toLowerCase().equals(name)) {
        return item;
      }
    }
    return null;
  }

  abstract void setString(String value, ContextDb contextDb);
  abstract String getString(ContextDb contextDb);

  public String userName() {
    return name().toLowerCase();
  }

  public static Map<String, String> getConfigValues(ContextDb contextDb) {
    return Arrays.stream(values()).collect(Collectors.toMap(ConfigOption::userName, v -> v.getString(contextDb)));
  }
}
