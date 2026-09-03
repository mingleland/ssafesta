package com.example.ssafesta.ai;

import com.example.ssafesta.storage.ObjectStorage;
import com.example.ssafesta.storage.ObjectStorageProperties;
import com.example.ssafesta.storage.StorageQuotaExceededException;
import com.example.ssafesta.storage.StorageUnavailableException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.ssafesta.booth.BoothEditorGuard;
import com.example.ssafesta.booth.BoothExpiredException;
import com.example.ssafesta.booth.BoothLeaseRepository;
import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
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
 * <p>Handing the document to FastAPI is S15P21A604-175, and expiring abandoned grants is
 * S15P21A604-174. A completed document sits in {@code QUEUED} until the first of those lands.
 *
 * <p><b>Until S15P21A604-174 lands, an abandoned grant holds its slot forever.</b> {@code QUEUED}
 * counts toward the ten of FR-018 ({@code AiDocumentRepository#countActive}) and nothing here moves
 * it to {@code EXPIRED} except a provider switch — there is no sweeper, and no delete endpoint. Ten
 * grants that were issued and never uploaded therefore block that agent with no way out: completing
 * answers {@code DOCUMENT_UPLOAD_INCOMPLETE} because the object is not there, and re-requesting the
 * same file reissues on the same row rather than freeing one. -174 is itself waiting on the AI
 * document DB redesign (GitLab #119), so this is a live operational limit, not a short gap.
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
    private final BoothEditorGuard editorGuard;
    private final BoothLeaseRepository leases;
    private final AiAgentProperties agentProperties;
    private final ObjectStorageProperties storageProperties;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;

    public AiDocumentService(AiDocumentRepository documents, AiAgentRepository agents,
                             BoothEditorGuard editorGuard, BoothLeaseRepository leases,
                             AiAgentProperties agentProperties,
                             ObjectStorageProperties storageProperties, ObjectStorage storage,
                             TransactionTemplate transactions) {
        this.documents = documents;
        this.agents = agents;
        this.editorGuard = editorGuard;
        this.leases = leases;
        this.agentProperties = agentProperties;
        this.storageProperties = storageProperties;
        this.storage = storage;
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
        editorGuard.requireEditor(agent.getBoothId(), userId);
        requireValidLease(agent.getBoothId());

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
        return transactions.execute(status -> settle(documentId, snapshot, storedSize));
    }

    private Snapshot readSnapshot(Long documentId, Long userId) {
        AiDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AiDocumentNotFoundException(documentId));
        editorGuard.requireEditor(document.getBoothId(), userId);
        requireValidLease(document.getBoothId());
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

    private CompleteView settle(Long documentId, Snapshot snapshot, Optional<Long> storedSize) {
        AiDocument document = documents.findWithLockById(documentId)
                .orElseThrow(() -> new AiDocumentNotFoundException(documentId));

        CompleteView settled = decideWithoutStorage(document);
        if (settled != null) {
            return settled;
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
            document.recover(now);
            return CompleteView.of(document);
        }

        if (!present) {
            throw new ApiException(ErrorCode.DOCUMENT_UPLOAD_INCOMPLETE,
                    storedSize.isEmpty()
                            ? "업로드된 파일을 찾을 수 없습니다. 다시 올려 주세요."
                            : "업로드된 파일 크기가 요청과 다릅니다. 다시 올려 주세요.");
        }
        document.markUploaded(now);
        return CompleteView.of(document);
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

    private void requireValidLease(Long boothId) {
        leases.findValidByBoothId(boothId, Instant.now())
                .orElseThrow(() -> new BoothExpiredException(boothId));
    }

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

    /** The row as it looked before the HEAD, plus any answer that needed no storage at all. */
    private record Snapshot(String provider, String bucket, String objectKey, long sizeBytes,
                            CompleteView decided) {

        static Snapshot of(AiDocument document, CompleteView decided) {
            return new Snapshot(document.getStorageProvider(), document.getStorageBucket(),
                    document.getObjectKey(), document.getSizeBytes(), decided);
        }

        boolean sameStorage(AiDocument document) {
            return provider.equals(document.getStorageProvider())
                    && bucket.equals(document.getStorageBucket())
                    && Objects.equals(objectKey, document.getObjectKey())
                    && sizeBytes == document.getSizeBytes();
        }
    }
}
