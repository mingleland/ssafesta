package com.example.ssafesta.internal.ai;

import com.example.ssafesta.common.ApiException;
import java.util.List;

/**
 * Renders an embedding as a pgvector literal, refusing anything the column cannot hold.
 *
 * <p>Used on both sides of the vector boundary: the query embedding a search carries
 * ({@link AiChunkSearchService}) and the stored embeddings a worker sends back
 * ({@link AiDocumentResultService}). The stored side matters more — a bad query vector fails one
 * request, a bad stored vector poisons every later search of that document.
 */
final class EmbeddingVector {

    /** 헌법 18조·FR-009. Not a tunable — the column is {@code vector(1536)}. */
    static final int DIMENSIONS = 1536;

    private EmbeddingVector() {
    }

    /**
     * Four rejections, each of which would otherwise become a 500 or a wrong answer instead of a
     * 400:
     *
     * <ul>
     *   <li><b>Not finite.</b> {@code 1e400} is valid JSON and parses to {@code Infinity}, which
     *       reaches Postgres as the text {@code Infinity} and fails there.
     *   <li><b>Outside float32.</b> {@code vector} stores {@code float4}, so {@code 1e300} — a
     *       perfectly finite {@code double} — overflows on input. The value is narrowed here and
     *       checked, which is also why the literal is written from the {@code float}: the text and
     *       the stored value are then the same number.
     *   <li><b>A norm float32 cannot hold.</b> Per-element finiteness is not enough: pgvector
     *       accumulates the norm in a {@code float}, so 1536 individually legal elements can
     *       overflow on the way in — {@code 1e19} each is finite as {@code float4}, and the sum of
     *       squares is {@code 1.5e41}. The accumulator becomes {@code Infinity} and the division
     *       gives {@code NaN} or a distance that means nothing. The sum of squares is therefore
     *       bounded by {@link Float#MAX_VALUE}; the terms are non-negative, so the total bounds
     *       every partial sum, and Cauchy-Schwarz bounds the dot product by the same value.
     *   <li><b>A norm float32 rounds to zero.</b> Cosine distance divides by the norm, so {@code <=>}
     *       against a zero-norm vector is {@code NaN} for every row. That does not fail — it sorts
     *       arbitrarily, and Jackson writes {@code NaN} as the <i>string</i> {@code "NaN"}, so a
     *       consumer that holds the contract's {@code number} type rejects the whole response.
     *       <b>The check runs on a {@code float} accumulator, mirroring pgvector's.</b> Testing the
     *       {@code double} sum instead catches only the elements that round to zero themselves
     *       ({@code 1e-50}); it misses the band where the element is a normal {@code float4} but its
     *       <i>square</i> underflows — every element {@code 1e-23} sums to {@code 1.5e-43} as a
     *       {@code double} and to exactly {@code 0} as a {@code float}, which is the number Postgres
     *       divides by. The two accumulators are kept separate because the overflow bound below
     *       needs the {@code double} one, which cannot saturate.
     * </ul>
     *
     * @param field the request field to name in the rejection — the caller's key, not ours
     */
    static String literal(List<Double> values, String field) {
        if (values == null || values.size() != DIMENSIONS) {
            throw ApiException.fieldInvalid(field, DIMENSIONS + "개의 값이어야 합니다.");
        }
        StringBuilder vector = new StringBuilder(DIMENSIONS * 8).append('[');
        double sumOfSquares = 0;
        // pgvector 가 노름을 누적하는 방식 그대로 — float 누산기다. double 로 센 제곱합은 0 이
        // 아닌데 float 로는 0 인 구간이 있고, 판정이 갈리는 쪽은 Postgres 다.
        float pgSumOfSquares = 0;
        for (int index = 0; index < values.size(); index++) {
            Double value = values.get(index);
            if (value == null || !Double.isFinite(value)) {
                throw ApiException.fieldInvalid(field, "유한한 수만 넣을 수 있습니다.");
            }
            float element = value.floatValue();
            if (!Float.isFinite(element)) {
                throw ApiException.fieldInvalid(field, "float32 로 담을 수 없는 값이 있습니다.");
            }
            sumOfSquares += (double) element * element;
            pgSumOfSquares += element * element;
            if (index > 0) {
                vector.append(',');
            }
            vector.append(element);
        }
        if (pgSumOfSquares == 0) {
            throw ApiException.fieldInvalid(field,
                    "노름이 float32 에서 0 이 됩니다. 코사인 거리를 정의할 수 없습니다.");
        }
        if (sumOfSquares > Float.MAX_VALUE) {
            throw ApiException.fieldInvalid(field,
                    "노름이 float32 범위를 넘습니다. 임베딩 값을 확인해 주세요.");
        }
        return vector.append(']').toString();
    }
}
