package com.codingreview.controlplane.review;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 跨语言稳定 JSON、原始字节摘要与严格 UTF-8 解码。 */
public final class ReviewJson {
    public static final Comparator<String> CODE_POINTS =
            (a, b) -> Arrays.compare(a.codePoints().toArray(), b.codePoints().toArray());
    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(
                            com.fasterxml.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS);
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private ReviewJson() {}

    public static String timestamp(Instant value) {
        return UTC.format(value);
    }

    public static String sha256(byte[] bytes) {
        return "sha256:" + HexFormat.of().formatHex(digest().digest(bytes));
    }

    public static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8");
        }
    }

    public static JsonNode parse(byte[] bytes) {
        try {
            String text = utf8(bytes);
            if (text.startsWith("\ufeff")) throw new IllegalArgumentException("BOM is forbidden");
            JsonNode value = MAPPER.readTree(text);
            if (value == null) throw new IllegalArgumentException("Empty JSON");
            validateUnicode(value);
            return value;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid JSON");
        }
    }

    public static byte[] canonical(Object value) {
        try {
            return MAPPER.writeValueAsBytes(sorted(MAPPER.valueToTree(value)));
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("JSON encoding failed");
        }
    }

    private static JsonNode sorted(JsonNode value) {
        validateUnicode(value);
        if (value.isObject()) {
            var result = MAPPER.createObjectNode();
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(CODE_POINTS);
            names.forEach(name -> result.set(name, sorted(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            var result = MAPPER.createArrayNode();
            value.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return value;
    }

    public static void unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(c))
                throw new IllegalArgumentException("Invalid Unicode");
        }
    }

    private static void validateUnicode(JsonNode value) {
        if (value.isTextual()) unicode(value.textValue());
        if (value.isObject()) value.fieldNames().forEachRemaining(ReviewJson::unicode);
        if (value.isContainerNode()) value.forEach(ReviewJson::validateUnicode);
    }
}
