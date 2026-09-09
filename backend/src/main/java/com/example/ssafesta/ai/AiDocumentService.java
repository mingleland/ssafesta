package com.example.ssafesta.ai;

import com.example.ssafesta.storage.ObjectStorage;
import com.example.ssafesta.storage.ObjectStorageProperties;
import com.example.ssafesta.storage.StorageQuotaExceededException;
import com.example.ssafesta.storage.StorageUnavailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.ssafesta.booth.BoothAccessGuard;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * Upload grants and upload completion for an agent's documents (spec 007 US2).
 *
 * <p>Two endpoints, one story: the browser asks for a presigned URL, PUTs the file straight to
 * storage, then tells us it is done. Nothing streams through Spring — the file never touches this
 * process, which is what makes a 20MB limit an assertion about a number rather than about memory.
 *
 * <p>Handing the document to FastAPI is S15P21A604-175. Abandoned grants no longer hold their slot:
 * {@link AiDocumentExpirySweeper} moves an unused one to {@code EXPIRED} an hour after it was
 * issued (FR-026), and {@link #complete} takes it back for the next 24 hours (FR-027).
 *
 * <p><b>Deleting the original once that window closes (FR-028) is still nobody's.</b> An expired
 * row keeps its object key, and no pass removes the bytes — the row is out of the way, the storage
 * is not.
 */
@Service
public class AiDocumentService {

    /** FR-011. Fixed, not configurable: the contract states one number and FastAPI validates it. */
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final int MAX_FILENAME = 255;

    /** FR-011 · document-processing-api.yaml. Extension included so the two cannot disagree. */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf", ".pdf",
            "text/markdown", ".md",
            "text/plain", ".txt");

    /** FR-019a. Lowercase hex, exactly 64 — what {@code sha256sum} prints. */
    private static final String SHA256 = "^[a-f0-9]{64}$";

    /** FR-027. A late completion is accepted for this long after the row expired. */
    private static final Duration RECOVERY_WINDOW = Duration.ofHours(24);

    /**
     * Statuses that mean the upload gate is already behind us. Completing again is not an error —
     * a client that retries after a timeout must not be told something went wrong.
     */
    private static final Set<String> PAST_UPLOAD = Set.of(AiDocument.PROCESSING, AiDocument.READY);

    private final AiDocumentRepository documents;
    private final AiAgentRepository agents;
    private final BoothAccessGuard accessGuard;
    private final AiAgentProperties agentProperties;
    private final ObjectStorageProperties storageProperties;
    private final ObjectStorage storage;
    private final DocumentJobDispatchService dispatcher;
    private final TransactionTemplate transactions;

    public AiDocumentService(AiDocumentRepository documents, AiAgentRepository agents,
                             BoothAccessGuard accessGuard,
                             AiAgentProperties agentProperties,
                             ObjectStorageProperties storageProperties, ObjectStorage storage,
                             DocumentJobDispatchService dispatcher,
                             TransactionTemplate transactions) {
        this.documents = documents;
        this.agents = agents;
        this.accessGuard = accessGuard;
        this.agentProperties = agentProperties;
        this.storageProperties = storageProperties;
        this.storage = storage;
        this.dispatcher = dispatcher;
        this.transactions = transactions;
    }

    // ── 업로드 URL 발급 ─────────────────────────────────────────────────────

    /**
     * Issues a presigned upload URL, or reports that the file is already registered.
     *
     * <p><b>There is no translation of {@code ux_ai_documents_agent_active_sha} here.</b> The
     * pattern next door in {@code AiAgentService.create} needs one because it has no lock and two
     * requests really can both insert; this path takes the agent row first, so every insert for one
     * agent is serialised and the loser sees the committed row in its own pre-check. A violation
     * would mean something wrote {@code ai_documents} without that lock — a defect, and turning it
     * into a cheerful 200 would hide it. The index stays as the schema's own backstop.
     *
     * <p>The write itself runs through a {@link TransactionTemplate} rather than
     * {@code @Transactional}: the URL is signed after the transaction commits, and a boundary that
     * matters is better read in the code than inferred from an annotation a self-invocation would
     * bypass.
     */
    public UploadGrantView issueUploadUrl(Long agentId, Long userId, UploadCommand command) {
        AiAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new AiAgentNotFoundException(agentId));
        // Permission first, availability second: a member with no claim on this booth should be
        // told they cannot edit it, not that our storage is down.
        accessGuard.requireActiveEditor(agent.getBoothId(), userId);

        UploadRequest request = validated(command);

        // Before the insert, deliberately. A row created while uploads are blocked would hold one
        // of the ten slots with no object behind it and nothing to clean it up (#100).
        //
        // One setting, two refusals. The operator has already read both state machines and written
        // the verdict into upload-gate (#100, 2026-09-01) — the reason still travels with the
        // refusal, because a spent quota is not something the owner can wait out and "try later"
        // sends them nowhere.
        switch (storageProperties.uploadGate()) {
            case QUOTA_BLOCKED -> throw new StorageQuotaExceededException();
            case UNAVAILABLE -> throw new StorageUnavailableException(
                    "현재 문서 업로드를 받을 수 없습니다. 잠시 후 다시 시도해 주세요.");
            case OPEN -> { }
        }

        return grant(agent, userId, request, storage.activeWriteTarget());
    }

    private UploadGrantView grant(AiAgent agent, Long userId, UploadRequest request,
                                  ObjectStorage.WriteTarget target) {
        Prepared prepared = transactions.execute(status -> {
            // Serialises quota checks for this agent. Without it two different files each read
            // "nine documents" and both insert; the unique index only catches the same file twice.
            agents.findWithLockById(agent.getId())
                    .orElseThrow(() -> new AiAgentNotFoundException(agent.getId()));

            Optional<AiDocument> active =
                    documents.findActiveByAgentAndSha(agent.getId(), request.contentSha256());
            if (active.isPresent()) {
                AiDocument existing = active.get();
                if (isResumable(existing, target)) {
                    return Prepared.reissue(existing, request);
                }
                if (existing.isAwaitingUpload()) {
                    // The write target moved under an unfinished grant: the old object would land
                    // in a storage we no longer write to, so it is abandoned and a new row starts
                    // in the current one (FR-032).
                    existing.expire(Instant.now());
                } else {
                    return Prepared.duplicate(existing);
                }
            }

            requireRoom(agent.getId(), request.size());

            Instant now = Instant.now();
            AiDocument document = new AiDocument(agent.getBoothId(), agent.getId(), userId,
                    request.fileName(), request.contentType(), request.size(),
                    request.contentSha256(), target, now);
            // saveAndFlush, not save: the id has to exist before the object key can be built, and
            // flushing here also means an index violation surfaces inside this transaction instead
            // of at commit time, where the stack trace no longer says which insert caused it.
            document = documents.saveAndFlush(document);
            document.assignObjectKey(objectKeyOf(document));
            return Prepared.issued(document);
        });
        return present(Objects.requireNonNull(prepared));
    }

    /**
     * Whether a fresh URL for this same row is the right answer (#84 멱등 재발급).
     *
     * <p>Only while the grant is still outstanding and still points at where we write today. A
     * document that has been uploaded is a duplicate, not a resume.
     *
     * <p>Bucket counts, not just the provider name: one provider can be repointed at a new bucket,
     * and reissuing on the old row would keep signing URLs for a bucket nothing reads any more.
     */
    private boolean isResumable(AiDocument existing, ObjectStorage.WriteTarget target) {
        return existing.isAwaitingUpload()
                && existing.getStorageProvider().equals(target.provider())
                && existing.getStorageBucket().equals(target.bucket());
    }

    private UploadGrantView present(Prepared prepared) {
        AiDocument document = prepared.document();
        if (prepared.duplicate()) {
            return UploadGrantView.duplicate(document.getId());
        }
        if (prepared.reissue()) {
            // The row is the record of what was approved. Re-signing with the request's values
            // instead would let a second call widen the size the first one had checked against the
            // quota, and the presigned PUT pins content-length.
            requireSameFile(document, prepared.request());
        }
        // Outside the transaction: signing is offline, and a lock held across it buys nothing.
        String url = storage.presignPut(document.getStorageProvider(), document.getStorageBucket(),
                document.getObjectKey(), document.getContentType(), document.getSizeBytes(),
                storageProperties.presignTtl());
        return UploadGrantView.issued(document.getId(), url, document.getObjectKey());
    }

    /**
     * A resumed grant must describe the same file the row was created for.
     *
     * <p>The hash matched, but the hash is the client's claim about bytes we have never seen
     * (FR-019a) — the name, type and size are ours, and they are what the object key, the quota and
     * the signature were built from. Refusing the mismatch is the conservative reading, confirmed
     * 2026-08-31 and announced to the team for comment.
     */
    private void requireSameFile(AiDocument document, UploadRequest request) {
        if (!document.getOriginalFilename().equals(request.fileName())
                || !document.getContentType().equals(request.contentType())
                || document.getSizeBytes() != request.size()) {
            throw ApiException.fieldInvalid("contentSha256",
                    "같은 파일 해시로 다른 파일 정보가 왔습니다. 진행 중인 업로드를 마치거나 만료된 뒤 다시 시도해 주세요.");
        }
    }

    private void requireRoom(Long agentId, long size) {
        int countLimit = agentProperties.documentCountLimit();
        if (documents.countActive(agentId) >= countLimit) {
            throw AiDocumentLimitException.byCount(countLimit);
        }
        DataSize totalLimit = agentProperties.documentTotalBytes();
        if (documents.sumActiveBytes(agentId) + size > totalLimit.toBytes()) {
            throw AiDocumentLimitException.byTotalSize(totalLimit);
        }
    }

    /** {@code booths/{boothId}/agents/{agentId}/documents/{documentId}/{fileName}} (docs/08 §7). */
    private static String objectKeyOf(AiDocument document) {
        return "booths/" + document.getBoothId() + "/agents/" + document.getAgentId()
                + "/documents/" + document.getId() + "/" + document.getOriginalFilename();
    }

    // ── 목록·상태 조회 ──────────────────────────────────────────────────────

    /**
     * The agent's documents and how much of the quota they use (US2 시나리오 2·7, US3 시나리오 1).
     *
     * <p><b>{@code requireEditor}, not {@code requireActiveEditor}.</b> An expired lease turns the
     * documents {@code DISABLED} and keeps the originals (FR-015) — being unable to look at what
     * you own because the lease ran out would make that preservation pointless. Writing still needs
     * an active lease; reading does not.
     *
     * <p><b>Nothing here touches FastAPI.</b> US2 시나리오 7 requires the list to answer while the
     * AI service is down, and it does because every value on it is a column of {@code ai_documents}.
     *
     * <p>No paging. FR-018 caps an agent at ten active documents, and the inactive ones this list
     * also returns are bounded by the same grants — a page parameter would be two sides of protocol
     * for a list that fits on one screen. ponytail: add it when a real agent's list stops fitting.
     */
    public DocumentListView list(Long agentId, Long userId) {
        AiAgent agent = agents.findById(agentId)
                .orElseThrow(() -> new AiAgentNotFoundException(agentId));
        accessGuard.requireEditor(agent.getBoothId(), userId);

        List<DocumentView> rows = documents.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(DocumentView::of)
                .toList();
        return new DocumentListView(rows, new QuotaView(agentProperties.documentCountLimit(),
                agentProperties.documentTotalBytes().toBytes()));
    }

    // ── 업로드 완료 ─────────────────────────────────────────────────────────

    /**
     * Confirms the object arrived and moves the document on (FR-027, data-model 업로드 만료 전이).
     *
     * <p>Three steps on purpose — read, ask storage, write — with <b>no database lock held across
     * the network call</b>. The expiry sweeper and reconcile both write these rows, so the state
     * seen in step one may be stale by step three; the write transaction re-reads under a lock and
     * decides again. When storage identifiers changed in between, the HEAD answered about a
     * different bucket and cannot be trusted — the client asks again.
     *
     * <p>Retrying in-process was written first and then removed. Moving a document between storages
     * is reconcile, and reconcile is operator-approved settings plus a redeploy while uploads are
     * blocked — a race that needs a redeploy to land inside one request. One extra round trip is
     * cheaper than a loop nobody can trigger.
     */
    public CompleteView complete(Long documentId, Long userId) {
        Snapshot snapshot = Objects.requireNonNull(
                transactions.execute(status -> readSnapshot(documentId, userId)));
        if (snapshot.decided() != null) {
            return snapshot.decided();
        }
        Optional<Long> storedSize = storage.headSize(snapshot.provider(), snapshot.bucket(),
                snapshot.objectKey());
        Settled settled = Objects.requireNonNull(
                transactions.execute(status -> settle(documentId, snapshot, storedSize)));
        // Outside the transaction, on purpose — the same rule the HEAD above follows. The Job is
        // already committed, so a delegation that fails leaves work the sweeper can pick up rather
        // than a lock held across somebody else's network (S15P21A604-175).
        settled.dispatch().ifPresent(dispatcher::dispatchQuietly);
        return settled.view();
    }

    private Snapshot readSnapshot(Long documentId, Long userId) {
        AiDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AiDocumentNotFoundException(documentId));
        accessGuard.requireActiveEditor(document.getBoothId(), userId);
        return Snapshot.of(document, decideWithoutStorage(document));
    }

    /**
     * The answers that do not need storage — checked first so a document that is already past the
     * upload gate never triggers a HEAD.
     */
    private static CompleteView decideWithoutStorage(AiDocument document) {
        if (PAST_UPLOAD.contains(document.getProcessingStatus())) {
            return CompleteView.of(document);
        }
        if (AiDocument.QUEUED.equals(document.getProcessingStatus())
                && document.getUploadedAt() != null) {
            return CompleteView.of(document);
        }
        if (!AiDocument.QUEUED.equals(document.getProcessingStatus()) && !document.isExpired()) {
            // FAILED or DISABLED — completing has no meaning, and silently doing nothing would look
            // like success to a client that is about to wait for processing.
            throw new ApiException(ErrorCode.DOCUMENT_UPLOAD_INCOMPLETE,
                    "이 문서는 업로드를 완료할 수 있는 상태가 아닙니다.");
        }
        return null;
    }

    private Settled settle(Long documentId, Snapshot snapshot, Optional<Long> storedSize) {
        // Agent first, then the document — the order issueUploadUrl takes. Reversing it here would
        // put two paths that hold both rows in opposite orders, which is a deadlock rather than a
        // style question. The lock is what makes the supersession check below decide something: a
        // competing grant is a different row, so this document's own lock cannot serialise it.
        agents.findWithLockById(snapshot.agentId())
                .orElseThrow(() -> new AiAgentNotFoundException(snapshot.agentId()));

        AiDocument document = documents.findWithLockById(documentId)
                .orElseThrow(() -> new AiDocumentNotFoundException(documentId));

        CompleteView settled = decideWithoutStorage(document);
        if (settled != null) {
            // Already past the upload gate, so its Job was created by the call that put it there.
            // Creating another would violate the one-active-Job index and re-process a document
            // nobody changed.
            return Settled.decided(settled);
        }
        if (!snapshot.sameStorage(document)) {
            // Reconcile moved the object while we were asking the old provider about it. The HEAD
            // answered about a bucket this row no longer points at, so it decides nothing.
            throw new StorageUnavailableException(
                    "문서 저장 위치가 변경되는 중입니다. 잠시 후 다시 시도해 주세요.");
        }

        Instant now = Instant.now();
        boolean present = storedSize.filter(size -> size == document.getSizeBytes()).isPresent();
        if (document.isExpired()) {
            if (!withinRecoveryWindow(document, now)) {
                // The object may still be sitting there — the sweeper deletes on its own schedule —
                // but the contract grants 24 hours, and honouring a later completion would make the
                // deadline mean nothing.
                throw gone();
            }
            if (!present) {
                throw gone();
            }
            // A newer grant for the same file already holds the active slot. Recovering would put
            // two active rows on one (agent_id, content_sha256) and break
            // ux_ai_documents_agent_active_sha — a 500 nobody could act on. The user has already
            // started over with this file, so the sentence 410 carries ("업로드가 만료되었습니다.
            // 새로 업로드해 주세요.") is exactly what happened to this grant.
            //
            // FR-027 is not weakened: it promises the original is kept and recoverable, not that a
            // late completion outranks the upload the same user started afterwards.
            boolean superseded = documents
                    .findActiveByAgentAndSha(document.getAgentId(), document.getContentSha256())
                    .filter(other -> !other.getId().equals(documentId))
                    .isPresent();
            if (superseded) {
                throw gone();
            }
            document.recover(now);
            return Settled.uploaded(document, dispatcher.createQueuedJob(document));
        }

        if (!present) {
            throw new ApiException(ErrorCode.DOCUMENT_UPLOAD_INCOMPLETE,
                    storedSize.isEmpty()
                            ? "업로드된 파일을 찾을 수 없습니다. 다시 올려 주세요."
                            : "업로드된 파일 크기가 요청과 다릅니다. 다시 올려 주세요.");
        }
        document.markUploaded(now);
        return Settled.uploaded(document, dispatcher.createQueuedJob(document));
    }

    /**
     * What {@link #settle} decided, and the delegation it left for after the commit.
     *
     * <p>Two fields rather than dispatching inside {@code settle}: the Job insert needs the document
     * row lock and the delegation call must not have it, and that boundary is only visible if the
     * transaction hands the work back out.
     */
    private record Settled(CompleteView view,
                           Optional<DocumentProcessingClient.ProcessingRequest> dispatch) {

        /** No write happened, so there is nothing to hand to FastAPI. */
        static Settled decided(CompleteView view) {
            return new Settled(view, Optional.empty());
        }

        static Settled uploaded(AiDocument document,
                                DocumentProcessingClient.ProcessingRequest request) {
            return new Settled(CompleteView.of(document), Optional.of(request));
        }
    }

    private static boolean withinRecoveryWindow(AiDocument document, Instant now) {
        Instant expiredAt = document.getExpiredAt();
        // A row without expired_at predates this column or was written by hand; treat the window as
        // closed rather than open — an unbounded recovery is the worse failure.
        return expiredAt != null && now.isBefore(expiredAt.plus(RECOVERY_WINDOW));
    }

    private static ApiException gone() {
        return new ApiException(ErrorCode.DOCUMENT_UPLOAD_GONE);
    }

    // ── 검증 ────────────────────────────────────────────────────────────────

    private UploadRequest validated(UploadCommand command) {
        if (command == null) {
            throw ApiException.fieldInvalid("fileName", "업로드할 파일 정보를 입력해 주세요.");
        }
        String fileName = validatedFileName(command.fileName());
        String contentType = validatedContentType(command.contentType(), fileName);
        long size = validatedSize(command.size());
        String sha = validatedSha(command.contentSha256());
        return new UploadRequest(fileName, contentType, size, sha);
    }

    /**
     * The file name is appended to the object key, so it is a trust boundary: a slash would let a
     * caller place the object anywhere in the bucket, and the length has to fit the column that
     * stores it.
     */
    private static String validatedFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw ApiException.fieldInvalid("fileName", "파일 이름을 입력해 주세요.");
        }
        if (fileName.length() > MAX_FILENAME) {
            throw ApiException.fieldInvalid("fileName",
                    "파일 이름이 너무 깁니다. (최대 " + MAX_FILENAME + "자)");
        }
        boolean unsafe = fileName.chars()
                .anyMatch(ch -> ch == '/' || ch == '\\' || Character.isISOControl(ch));
        if (unsafe || fileName.contains("..")) {
            throw ApiException.fieldInvalid("fileName", "파일 이름에 사용할 수 없는 문자가 있습니다.");
        }
        return fileName;
    }

    private static String validatedContentType(String contentType, String fileName) {
        String extension = ALLOWED_TYPES.get(contentType);
        if (extension == null) {
            throw ApiException.fieldInvalid("contentType",
                    "지원하지 않는 파일 형식입니다. 허용값: " + String.join(", ",
                            ALLOWED_TYPES.keySet().stream().sorted().toList()));
        }
        // A .pdf declared as text/plain would be signed as text and rejected by the parser much
        // later, with nothing pointing at the real cause.
        if (!fileName.toLowerCase().endsWith(extension)) {
            throw ApiException.fieldInvalid("fileName",
                    "파일 확장자가 형식과 맞지 않습니다. " + contentType + " 은(는) " + extension + " 이어야 합니다.");
        }
        return contentType;
    }

    private static long validatedSize(Long size) {
        if (size == null || size <= 0) {
            throw ApiException.fieldInvalid("size", "파일 크기를 입력해 주세요.");
        }
        if (size > MAX_FILE_BYTES) {
            throw ApiException.fieldInvalid("size",
                    "파일은 " + (MAX_FILE_BYTES / 1024 / 1024) + "MB 까지 올릴 수 있습니다.");
        }
        return size;
    }

    private static String validatedSha(String sha) {
        if (sha == null || !sha.matches(SHA256)) {
            throw ApiException.fieldInvalid("contentSha256",
                    "contentSha256 은 소문자 16진수 64자여야 합니다.");
        }
        return sha;
    }

    // ── 요청·응답 ───────────────────────────────────────────────────────────

    /** All four keys are required, so a record is enough — no {@code PresenceField} needed here. */
    public record UploadCommand(String fileName, String contentType, Long size,
                                String contentSha256) {
    }

    private record UploadRequest(String fileName, String contentType, long size,
                                 String contentSha256) {
    }

    /**
     * {@code duplicate:true} omits {@code uploadUrl} and {@code objectKey} entirely (docs/08 §7) —
     * hence the nulls and {@code @JsonInclude} on the fields rather than a second response type.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UploadGrantView(boolean duplicate, Long documentId, String uploadUrl,
                                  String objectKey) {

        static UploadGrantView issued(Long documentId, String uploadUrl, String objectKey) {
            return new UploadGrantView(false, documentId, uploadUrl, objectKey);
        }

        static UploadGrantView duplicate(Long documentId) {
            return new UploadGrantView(true, documentId, null, null);
        }
    }

    public record CompleteView(Long documentId, String processingStatus) {

        static CompleteView of(AiDocument document) {
            return new CompleteView(document.getId(), document.getProcessingStatus());
        }
    }

    /**
     * One row of the list.
     *
     * <p><b>{@code uploadedAt} is {@code null} while the bytes are still in flight.</b> That is the
     * one thing {@code status} cannot say: a grant that was just issued and a document waiting to
     * be processed are both {@code QUEUED}. A client can tell them apart with this field — and the
     * expiry sweep reads the same column for the same reason.
     *
     * <p><b>No failure reason yet.</b> FR-007 requires a cleaned-up one and forbids leaking
     * {@code ai_document_jobs.last_error}. The worker's {@code failureCode} is a free-form string
     * of up to 50 characters with no agreed set, so there is nothing to translate from — and
     * nothing writes {@code FAILED} to a document today in any case (GitLab #119). Whoever adds
     * that writer (S15P21A604-400) adds the reason with it.
     */
    public record DocumentView(Long documentId, String fileName, long sizeBytes, String status,
                               Instant createdAt, Instant uploadedAt) {

        static DocumentView of(AiDocument document) {
            return new DocumentView(document.getId(), document.getOriginalFilename(),
                    document.getSizeBytes(), document.getProcessingStatus(),
                    document.getCreatedAt(), document.getUploadedAt());
        }
    }

    /**
     * FR-018's two limits, which the client cannot know on its own — they are server configuration.
     *
     * <p><b>What is spent is not here, because it is already in {@code documents}.</b> The slots
     * taken are the rows whose status is {@code QUEUED}, {@code PROCESSING} or {@code READY}
     * (FR-019b — a failed or expired upload holds nothing), and the bytes are their
     * {@code sizeBytes}. Counting them here would be two more queries per poll for a number the
     * caller can read off the array it just received.
     *
     * <p>The limits are on the response because otherwise they surface only as a refusal:
     * {@code 409 DOCUMENT_LIMIT_EXCEEDED}, on a grant the user has already chosen a file for.
     */
    public record QuotaView(int countLimit, long bytesLimit) { }

    public record DocumentListView(List<DocumentView> documents, QuotaView quota) { }

    /** What the grant transaction decided, carried out so the URL can be signed outside it. */
    private record Prepared(AiDocument document, boolean duplicate, boolean reissue,
                            UploadRequest request) {

        static Prepared issued(AiDocument document) {
            return new Prepared(document, false, false, null);
        }

        static Prepared duplicate(AiDocument document) {
            return new Prepared(document, true, false, null);
        }

        static Prepared reissue(AiDocument document, UploadRequest request) {
            return new Prepared(document, false, true, request);
        }
    }

    /**
     * The row as it looked before the HEAD, plus any answer that needed no storage at all.
     *
     * <p>{@code agentId} is here so {@link #settle} can take the agent lock <b>before</b> it loads
     * the document. The status is deliberately not carried: the sweeper may have moved it during
     * the HEAD, which is the whole reason the write transaction decides again.
     */
    private record Snapshot(Long agentId, String provider, String bucket, String objectKey,
                            long sizeBytes, CompleteView decided) {

        static Snapshot of(AiDocument document, CompleteView decided) {
            return new Snapshot(document.getAgentId(), document.getStorageProvider(),
                    document.getStorageBucket(), document.getObjectKey(), document.getSizeBytes(),
                    decided);
        }

        boolean sameStorage(AiDocument document) {
            return provider.equals(document.getStorageProvider())
                    && bucket.equals(document.getStorageBucket())
                    && Objects.equals(objectKey, document.getObjectKey())
                    && sizeBytes == document.getSizeBytes();
        }
    }
}
