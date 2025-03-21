package com.google.udmi.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.udmi.util.GeneralUtils.fromJsonString;
import static com.google.udmi.util.GeneralUtils.toJsonString;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonParser.Feature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collection of utilities for working with json things.
 */
public abstract class JsonUtil {

  public static final String JSON_EXT = "json";
  public static final String JSON_SUFFIX = ".json";
  private static final ObjectMapper STRICT_MAPPER = new ObjectMapper()
      .enable(Feature.ALLOW_COMMENTS)
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .setDateFormat(new CleanDateFormat())
      .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature())
      .registerModule(NanSerializer.TO_NULL) // NaN is not valid JSON, so squash it now.
      .setSerializationInclusion(Include.NON_NULL);
  public static final ObjectMapper OBJECT_MAPPER = STRICT_MAPPER.copy()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  public static final ObjectMapper TERSE_MAPPER = OBJECT_MAPPER.copy()
      .disable(SerializationFeature.INDENT_OUTPUT);

  /**
   * Convert the json string to a generic map object.
   *
   * @param input input string
   * @return input as map object
   */
  public static Map<String, Object> asMap(String input) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = fromString(TreeMap.class, input);
    return map;
  }

  /**
   * Convert the json object to a generic map object.
   *
   * @param input input file
   * @return input as map object
   */
  public static Map<String, Object> asMap(File input) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = loadFile(TreeMap.class, input);
    return map;
  }

  /**
   * Convert the json object to a generic map object.
   *
   * @param input input object
   * @return input as map object
   */
  public static Map<String, Object> asMap(Object input) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = convertTo(TreeMap.class, input);
    return map;
  }

  /**
   * Convert a generic object to a specific class.
   *
   * @param targetClass result class
   * @param message object to convert
   * @param <T> class parameter
   * @return converted object
   */
  public static <T> T convertTo(Class<T> targetClass, Object message) {
    requireNonNull(targetClass, "target class is null");
    return message == null ? null : fromString(targetClass, stringify(message));
  }

  /**
   * Convert a generic object to a specific class with strict field mappings.
   *
   * @param targetClass result class
   * @param message object to convert
   * @param <T> class parameter
   * @return converted object
   */
  public static <T> T convertToStrict(Class<T> targetClass, Object message) {
    requireNonNull(targetClass, "target class is null");
    try {
      return message == null ? null : fromStringStrict(targetClass, stringify(message));
    } catch (Exception e) {
      throw new RuntimeException("While converting to " + targetClass.getName(), e);
    }
  }

  public static <T> T fromString(Class<T> targetClass, String messageString) {
    requireNonNull(targetClass, "target class is null");
    try {
      return OBJECT_MAPPER.readValue(messageString, checkNotNull(targetClass, "target class"));
    } catch (Exception e) {
      throw new RuntimeException("While converting to " + targetClass.getName(), e);
    }
  }

  public static <T> T fromStringStrict(Class<T> targetClass, String messageString) {
    requireNonNull(targetClass, "target class is null");
    try {
      return STRICT_MAPPER.readValue(messageString, checkNotNull(targetClass, "target class"));
    } catch (Exception e) {
      throw new RuntimeException("While converting to " + targetClass.getName(), e);
    }
  }

  /**
   * Get a Date object parsed from a string representation.
   *
   * @param timestamp string representation
   * @return Date object
   */
  public static Date getDate(String timestamp) {
    return timestamp == null ? null : Date.from(getInstant(timestamp));
  }

  /**
   * Get an Instant object parsed from a string representation. Also perform some munging on the
   * input string to handle standard-yet-not-supported formats.
   *
   * @param timestamp string representation
   * @return Instant object
   */
  public static Instant getInstant(String timestamp) {
    String replaced = timestamp.replaceFirst("\\+0000$", "Z");
    return timestamp == null ? null : Instant.parse(replaced);
  }

  /**
   * Get a current timestamp string.
   *
   * @return current ISO timestamp
   */
  public static String isoConvert() {
    return isoConvert(CleanDateFormat.cleanDate());
  }

  /**
   * Load a file to given type.
   *
   * @param clazz class of result
   * @param file file to load
   * @param <T> type of result
   * @return loaded object
   */
  public static <T> T loadFile(Class<T> clazz, File file) {
    try {
      return file.exists() ? OBJECT_MAPPER.readValue(file, clazz) : null;
    } catch (Exception e) {
      throw new RuntimeException("While loading " + file.getAbsolutePath(), e);
    }
  }

  /**
   * Load a file to given type, requiring that it exists.
   *
   * @param clazz class of result
   * @param file path of file to load
   * @param <T> type of result
   * @return loaded object
   */
  public static <T> T loadFileRequired(Class<T> clazz, String file) {
    return loadFileRequired(clazz, new File(file));
  }

  /**
   * Load a file to given type, requiring that it exists.
   *
   * @param clazz class of result
   * @param file file to load
   * @param <T> type of result
   * @return loaded object
   */
  public static <T> T loadFileRequired(Class<T> clazz, File file) {
    if (!file.exists()) {
      throw new RuntimeException("Required file not found: " + file.getAbsolutePath());
    }
    try {
      return OBJECT_MAPPER.readValue(file, clazz);
    } catch (Exception e) {
      throw new RuntimeException("While loading " + file.getAbsolutePath(), e);
    }
  }

  /**
   * Load file with strict(er) error checking, and throw an exception if necessary.
   *
   * @param clazz class of result
   * @param file file to load
   * @param <T> type of result
   * @return converted object
   */
  public static <T> T loadFileStrict(Class<T> clazz, File file) {
    try {
      return file.exists() ? STRICT_MAPPER.readValue(file, clazz) : null;
    } catch (Exception e) {
      throw new RuntimeException("While loading " + file.getAbsolutePath(), e);
    }
  }

  public static <T> T loadFileStrictRequired(Class<T> clazz, String file) {
    return loadFileStrictRequired(clazz, new File(file));
  }

  /**
   * Load file with strict(er) error checking and required-to-exist file.
   *
   * @param clazz class of result
   * @param file file to load
   * @param <T> type of result
   * @return converted object
   */
  public static <T> T loadFileStrictRequired(Class<T> clazz, File file) {
    if (!file.exists()) {
      throw new RuntimeException("Required file not found: " + file.getAbsolutePath());
    }
    try {
      return STRICT_MAPPER.readValue(file, clazz);
    } catch (Exception e) {
      throw new RuntimeException("While loading " + file.getAbsolutePath(), e);
    }
  }

  public static String loadFileString(File file) {
    try {
      return new String(Files.readAllBytes(file.toPath()));
    } catch (Exception e) {
      throw new RuntimeException("While loading file " + file.getAbsolutePath(), e);
    }
  }

  /**
   * Convert the given input file to a mapped representation.
   *
   * @param inputFile input file to convert to a map
   * @return object-as-map
   */
  public static Map<String, Object> loadMap(File inputFile) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = convertTo(TreeMap.class, loadFile(TreeMap.class, inputFile));
    return map;
  }

  /**
   * Sleep and catch-and-rethrow any exceptions.
   *
   * @param sleepTimeMs duration to sleep
   */
  public static void safeSleep(long sleepTimeMs) {
    try {
      Thread.sleep(sleepTimeMs);
    } catch (Exception e) {
      throw new RuntimeException("Interrupted sleep", e);
    }
  }

  /**
   * Convert an object to a json string.
   *
   * @param target object to convert
   * @return json string representation
   */
  public static String stringify(Object target) {
    try {
      return OBJECT_MAPPER.writeValueAsString(target);
    } catch (Exception e) {
      throw new RuntimeException("While stringifying object", e);
    }
  }

  /**
   * Convert an object to a terse (no indent) json string.
   *
   * @param target object to convert
   * @return json string representation
   */
  public static String stringifyTerse(Object target) {
    try {
      return TERSE_MAPPER.writeValueAsString(target);
    } catch (Exception e) {
      throw new RuntimeException("While stringifying object", e);
    }
  }

  /**
   * Parse and get as any Java object from json.
   */
  public static Object parseJson(String message) {
    try {
      return OBJECT_MAPPER.readTree(message);
    } catch (Exception e) {
      throw new RuntimeException("While parsing json object", e);
    }
  }

  /**
   * Convert the pojo to a mapped representation of strings only.
   *
   * @param message input object to convert
   * @return object-as-map
   */
  public static Map<String, String> toStringMap(Object message) {
    @SuppressWarnings("unchecked")
    Map<String, String> map = convertTo(TreeMap.class, message);
    return map;
  }

  /**
   * Convert the pojo to a mapped representation of strings only.
   *
   * @param message input object to convert
   * @return object-as-map
   */
  public static Map<String, String> toStringMap(String message) {
    @SuppressWarnings("unchecked")
    Map<String, String> map = fromString(TreeMap.class, message);
    return map;
  }

  /**
   * Convert the pojo to a mapped representation.
   *
   * @param message input object to convert
   * @return object-as-map
   */
  public static Map<String, Object> toMap(Object message) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = convertTo(TreeMap.class, message);
    return map;
  }

  /**
   * Convert the string to a mapped representation.
   *
   * @param message input string to convert
   * @return object-as-map
   */
  public static Map<String, Object> toMap(String message) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map = fromString(TreeMap.class, message);
    return map;
  }

  /**
   * Write json representation to a file.
   *
   * @param target object to write
   * @param file output file
   */
  public static void writeFile(Object target, File file) {
    try {
      OBJECT_MAPPER.writeValue(file, target);
    } catch (Exception e) {
      throw new RuntimeException("While writing " + file.getAbsolutePath(), e);
    }
  }

  private static Date isoConvert(String timestamp) {
    try {
      String wrappedString = "\"" + timestamp + "\"";
      return fromJsonString(wrappedString, Date.class);
    } catch (Exception e) {
      throw new RuntimeException("Creating date", e);
    }
  }

  public static String isoConvert(Instant timestamp) {
    return isoConvert(Date.from(timestamp));
  }

  public static String isoConvert(Date timestamp) {
    try {
      if (timestamp == null) {
        return "null";
      }
      String dateString = toJsonString(timestamp);
      // Strip off the leading and trailing quotes from the JSON string-as-string representation.
      return dateString.substring(1, dateString.length() - 1);
    } catch (Exception e) {
      throw new RuntimeException("Creating timestamp", e);
    }
  }
}
