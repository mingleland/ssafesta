package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads an internal request body while refusing fields the contract does not define.
 *
 * <p>Spring Boot's shared {@code ObjectMapper} has {@code FAIL_ON_UNKNOWN_PROPERTIES} off, so
 * binding a body as a {@code @RequestBody} record would silently discard a field the caller
 * believed it sent — the caller then reads a normal 2xx as proof that the field took effect. That
 * is the shape of T-24. A private strict mapper closes it without changing the global one, which
 * would alter every other endpoint's behaviour.
 *
 * <p>Shared by every {@code /internal/ai} endpoint that takes a body: the vocabulary of "unknown
 * field" rejections has to be one thing, or two endpoints answer the same mistake differently.
 */
final class StrictJsonReader {

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private StrictJsonReader() {
    }

    static <T> T read(String rawBody, Class<T> type) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 본문이 비어 있습니다.");
        }
        try {
            return STRICT.readValue(rawBody, type);
        } catch (UnrecognizedPropertyException unknown) {
            // The field name is the whole value of this rejection: without it the caller cannot
            // tell which key to remove.
            throw ApiException.fieldInvalid(unknown.getPropertyName(), "계약에 없는 필드입니다.");
        } catch (JacksonException exception) {
            // Jackson's own text is English and names the mapped class, so it is not passed through.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "요청 JSON 형식이 올바르지 않습니다.");
        }
    }
}
