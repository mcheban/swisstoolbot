package org.cheban.swisstoolbot.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

@UtilityClass
public class JsonUtil {
   private static final ObjectMapper MAPPER = new ObjectMapper();

   public static JsonNode parseJsonNode(Reader r) {
      try {
         return MAPPER.readTree(r);
      } catch (IOException e) {
         throw new IllegalArgumentException(e);
      }
   }

   public static JsonNode parseJsonNode(String s) {
      try {
         return MAPPER.readTree(s);
      } catch (IOException e) {
         throw new IllegalArgumentException(e);
      }
   }

   public static <T> T parseObject(Reader r, Class<T> clazz) {
      try {
         return MAPPER.readValue(r, clazz);
      } catch (IOException e) {
         throw new IllegalArgumentException(e);
      }
   }

   public static <T> List<T> parseObjectList(Reader r, Class<T> clazz) {
      try {
         JavaType type = MAPPER.getTypeFactory().constructCollectionType(List.class, clazz);
         return MAPPER.readValue(r, type);
      } catch (IOException e) {
         throw new IllegalArgumentException(e);
      }
   }

   public static <T> T parseObjectRef(Reader r, TypeReference<T> type) {
      try {
         return MAPPER.readValue(r, type);
      } catch (IOException e) {
         throw new IllegalArgumentException(e);
      }
   }
}
