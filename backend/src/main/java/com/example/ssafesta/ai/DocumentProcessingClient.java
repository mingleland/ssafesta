package com.example.ssafesta.ai;

/**
 * The calls Spring makes into FastAPI: start processing a Job that already exists here, and cancel
 * an attempt that is still running (contract {@code document-processing-api.yaml} v0.7.0,
 * S15P21A604-175 · S15P21A604-496).
 *
 * <p>An interface with one implementation, which is normally a smell. It earns its place as a test
 * seam for the same reason {@code ObjectStorage} does: this is the only network call on the upload
 * path and there is no FastAPI in the test stack.
 *
 * <p><b>Nothing comes back.</b> The contract's 202 has no body — FastAPI reports through the result
 * endpoints ({@code /internal/ai/document-jobs/**}), keyed on the {@code jobId} and
 * {@code attemptNo} sent here. A caller that wants to know whether processing succeeded reads the
 * Job row, not this method's return value.
 */
public interface DocumentProcessingClient {

    /**
     * Hands one attempt of one Job to FastAPI.
     *
     * <p>Safe to call again with the same snapshot: the contract makes a resend of the same
     * {@code jobId + attemptNo} idempotent, accepted without starting a second worker. That is what
     * lets the dispatch sweeper retry a call it cannot prove was delivered.
     *
     * @throws DocumentProcessingUnavailableException when FastAPI could not be reached or answered
     *                                               anything other than 202. The Job stays where it
     *                                               is and the retry is the caller's decision
     */
    void startProcessing(ProcessingRequest request);

    /**
     * Asks FastAPI to stop one attempt of one Job.
     *
     * <p>Idempotent by contract in the widest sense: an unknown Job, a finished one and an
     * {@code attemptNo} that is not the running one all answer {@code 204}. The last of those is the
     * point of sending {@code attemptNo} at all — a cancel that arrives after lease recovery issued
     * the next attempt must not kill the attempt that replaced it (GitLab #162).
     *
     * @throws DocumentProcessingUnavailableException when FastAPI could not be reached or answered
     *                                               anything other than 204. Callers do not retry:
     *                                               a worker that never hears is refused at its next
     *                                               callback, because a cancelled Job is terminal
     */
    void cancelProcessing(CancelRequest request);

    /**
     * Exactly the contract's {@code ProcessDocumentRequest}, in its field order.
     *
     * <p>The contract sets {@code additionalProperties: false}, so an extra field is a 422 rather
     * than something FastAPI ignores. Anything this record does not carry is deliberately absent:
     * the embedding model, vector dimension and chunker options are the AI side's own settings, and
     * the original bytes are fetched from the storage coordinates below rather than sent or signed.
     */
    record ProcessingRequest(long jobId, int attemptNo, long documentId, long boothId, long agentId,
                             String originalFilename, String contentType, long fileSizeBytes,
                             String storageProvider, String storageBucket, String objectKey,
                             String sourceHash) {
    }

    /**
     * Exactly the contract's {@code CancelDocumentProcessingRequest}, in its field order.
     *
     * <p>The two identifiers and nothing else — {@code additionalProperties: false} applies here too,
     * and the reason for the cancellation is Spring's business.
     */
    record CancelRequest(long jobId, int attemptNo) {
    }
}
