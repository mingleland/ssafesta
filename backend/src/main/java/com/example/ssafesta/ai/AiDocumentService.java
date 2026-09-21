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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>{@link #replace} is the same two steps aimed at an existing {@code READY} document (FR-019):
 * it grants a URL for a second row that points back at the first. Retiring the first is not this
 * class's — it happens in {@code AiDocumentResultService.finalizeJob}, once the new version is
 * actually published (FR-027a).
 *
 * <p>Deleting the original once that window closes (FR-028) is {@link AiDocumentOriginalDeleteSweeper}
 * — a separate pass on its own schedule, not this class. {@link #complete} still owns everything up
 * to that point: an expired row keeps its object key until that sweeper's 24-hour-later pass takes
 * it, not this one.
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
    private final JdbcTemplate jdbc;

    public AiDocumentService(AiDocumentRepository documents, AiAgentRepository agents,
                             BoothAccessGuard accessGuard,
                             AiAgentProperties agentProperties,
                             ObjectStorageProperties storageProperties, ObjectStorage storage,
                             DocumentJobDispatchService dispatcher,
                             TransactionTemplate transactions, JdbcTemplate jdbc) {
        this.documents = documents;
        this.agents = agents;
        this.accessGuard = accessGuard;
        this.agentProperties = agentProperties;
        this.storageProperties = storageProperties;
        this.storage = storage;
        this.dispatcher = dispatcher;
        this.transactions = transactions;
        this.jdbc = jdbc;
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
                    abandon(existing);
                } else {
                    return Prepared.duplicate(existing);
                }
            }

            return Prepared.issued(insert(agent.getBoothId(), agent.getId(), userId, request,
                    target, null));
        });
        return present(Objects.requireNonNull(prepared));
    }

    // ── 수정본 교체 ─────────────────────────────────────────────────────────

    /**
     * Issues an upload URL for a new version of a {@code READY} document (FR-019, US2 시나리오 5).
     *
     * <p>A replacement is a <b>second document</b>, not an edit of the first. The original stays
     * {@code READY} and searchable the whole way through and is only pushed out once the new one
     * actually publishes ({@code AiDocumentResultService.finalizeJob}) — so a file that fails to
     * parse costs nothing, which is the opposite of retiring the original at {@code /complete} and
     * leaving the agent with no document at all when processing then fails.
     *
     * <p>The client finishes with the ordinary {@code POST /documents/{newDocumentId}/complete}. It
     * needs no new response type either: what it gets back is a grant, and a grant is a
     * {@link UploadGrantView}.
     *
     * <p><b>Only {@code READY} can be replaced.</b> The other five states each have their own way
     * out — a {@code QUEUED} or {@code PROCESSING} document is already becoming the version the
     * owner wants, and {@code FAILED}, {@code EXPIRED} and {@code DISABLED} are outside the
     * duplicate rule (FR-019b) so the same file simply goes up again as a new document. Widening
     * this would mean two answers to "upload this file" with no way to tell which one the owner
     * meant.
     */
    public UploadGrantView replace(Long documentId, Long userId, UploadCommand command) {
        AiDocument document = documents.findById(documentId)
                .orElseThrow(() -> new AiDocumentNotFoundException(documentId));
        // Permission before availability, and before the lock — same order as issueUploadUrl.
        accessGuard.requireActiveEditor(document.getBoothId(), userId);

        UploadRequest request = validated(command);

        switch (storageProperties.uploadGate()) {
            case QUOTA_BLOCKED -> throw new StorageQuotaExceededException();
            case UNAVAILABLE -> throw new StorageUnavailableException(
                    "현재 문서 업로드를 받을 수 없습니다. 잠시 후 다시 시도해 주세요.");
            case OPEN -> { }
        }

        ObjectStorage.WriteTarget target = storage.activeWriteTarget();
        Prepared prepared = transactions.execute(status -> {
            // Agent first, then the document — the order settle() takes. Two paths that hold both
            // rows in opposite orders is a deadlock, not a style question. The agent lock is also
            // what serialises the quota and the successor lookup below, exactly as in grant().
            agents.findWithLockById(document.getAgentId())
                    .orElseThrow(() -> new AiAgentNotFoundException(document.getAgentId()));
            AiDocument original = documents.findWithLockById(documentId)
                    .orElseThrow(() -> new AiDocumentNotFoundException(documentId));

            // Re-read under the lock. The status seen above may be minutes old: a lease expiring
            // (FR-015) turns this document DISABLED in its own transaction, and replacing a
            // document that is no longer live would publish into a booth nobody can edit.
            if (!original.isReady()) {
                throw new ApiException(ErrorCode.DOCUMENT_NOT_REPLACEABLE,
                        "준비 완료(READY) 문서만 교체할 수 있습니다. 현재 상태: "
                                + original.getProcessingStatus());
            }
            return prepareReplacement(original, userId, request, target);
        });
        return present(Objects.requireNonNull(prepared));
    }

    /**
     * Decides what a replacement request means for a target that is already being replaced.
     *
     * <p><b>A pre-read under the agent lock, not a caught constraint violation.</b> Absorbing a
     * {@code ux_ai_documents_active_replacement} failure would be the wrong shape twice over: after
     * a violated {@code saveAndFlush} neither the transaction nor the persistence context can be
     * reused, and the index is the backstop for a writer that skipped this lock — turning it into a
     * cheerful 200 would hide exactly that defect (the same judgement {@link #issueUploadUrl}
     * records about the hash index).
     */
    private Prepared prepareReplacement(AiDocument original, Long userId, UploadRequest request,
                                        ObjectStorage.WriteTarget target) {
        Long documentId = original.getId();
        if (original.getContentSha256().equals(request.contentSha256())) {
            // The file the owner picked is the one already there. Nothing to replace, and creating
            // a row would spend a slot and a Job on an identical original — FR-019c's reading of a
            // duplicate, applied to the target itself.
            return Prepared.duplicate(original);
        }

        Optional<AiDocument> inFlight = documents.findActiveReplacementOf(documentId);
        // FR-019b uniqueness is per (agent, hash) across the active states, so a replacement
        // carrying a hash another live document already holds cannot be inserted at all. Saying so
        // here names the document that blocks it; letting the index fire would be a 500.
        //
        // The target and the replacement already being uploaded over it are not "elsewhere": the
        // first is the row this call is aimed at, and the second is this same request arriving
        // again — both are answered below rather than refused.
        Optional<AiDocument> sameFileElsewhere =
                documents.findActiveByAgentAndSha(original.getAgentId(), request.contentSha256())
                        .filter(other -> !other.getId().equals(documentId))
                        .filter(other -> inFlight.isEmpty()
                                || !other.getId().equals(inFlight.get().getId()));
        if (sameFileElsewhere.isPresent()) {
            throw new ApiException(ErrorCode.DOCUMENT_NOT_REPLACEABLE,
                    "같은 파일이 이미 다른 문서로 등록돼 있습니다. 그 문서를 교체하거나 삭제한 뒤 시도해 주세요.");
        }

        if (inFlight.isEmpty()) {
            return Prepared.issued(insert(original.getBoothId(), original.getAgentId(), userId,
                    request, target, documentId));
        }

        AiDocument successor = inFlight.get();
        if (!successor.isAwaitingUpload()) {
            if (AiDocument.QUEUED.equals(successor.getProcessingStatus())
                    && successor.getContentSha256().equals(request.contentSha256())) {
                // Uploaded and waiting to be processed. The request has already been carried out,
                // so this is the duplicate answer, pointed at the row that is doing the work.
                return Prepared.duplicate(successor);
            }
            // PROCESSING, READY, or a different file on an upload that already landed. Cancelling
            // work that is under way — or that a worker is holding the file for — is a different
            // operation from starting one, and nothing asked for it.
            throw new ApiException(ErrorCode.DOCUMENT_NOT_REPLACEABLE,
                    "이미 진행 중인 교체가 있습니다. 끝난 뒤 다시 시도해 주세요.");
        }

        if (successor.getContentSha256().equals(request.contentSha256())) {
            if (isResumable(successor, target)) {
                // Same file, grant still outstanding: re-sign it (#84 멱등 재발급). present()
                // checks the name, type and size against the row before signing.
                return Prepared.reissue(successor, request);
            }
            // The write target moved under the unfinished grant (FR-032).
            abandon(successor);
        } else {
            // The owner changed their mind about which file replaces this document. The old grant
            // has no object behind it and no Job — createQueuedJob runs in the same transaction
            // that fills uploaded_at, so a row awaiting upload provably has none — so expiring it
            // is the whole of the cleanup. expire() deliberately leaves replaced_at null: nothing
            // took this row over, it was simply abandoned (FR-027a).
            abandon(successor);
        }
        return Prepared.issued(insert(original.getBoothId(), original.getAgentId(), userId, request,
                target, documentId));
    }

    /**
     * Gives up an unfinished grant so a new row can take its place.
     *
     * <p><b>The flush is load-bearing, not a tidy-up.</b> Hibernate's action queue runs inserts
     * before updates, so without it the replacement row is inserted while this one is still active
     * and the partial unique indexes refuse it — {@code ux_ai_documents_agent_active_sha} on the
     * FR-032 path, {@code ux_ai_documents_active_replacement} when the owner swaps which file
     * replaces a document. Both are legitimate requests and both would come back a 500.
     *
     * <p>It used to work by accident: the quota pre-check sat between the two and its JPQL query
     * auto-flushed the update. Moving that check after the insert (see {@link #insert}) took the
     * accident away, which is reason enough to state the ordering here instead.
     */
    private void abandon(AiDocument grant) {
        grant.expire(Instant.now());
        documents.flush();
    }

    /**
     * Creates the row and proves it fits, in that order.
     *
     * <p><b>The quota is checked after the insert, not before.</b> A replacement's arithmetic only
     * balances once the row exists: {@code countActive} drops the original because a live successor
     * points at it, and nothing points at it until the successor is there. Asked beforehand, the
     * eleventh row of a full agent is refused and replacing a document becomes impossible exactly
     * when it matters most. Asked afterwards, the answer is the logical total and the refusal rolls
     * the insert back with it.
     *
     * <p>Nothing changes for an ordinary upload: the pre-check asked whether this row <i>would</i>
     * exceed the limit and the post-check asks whether it <i>does</i>, over the same rows and the
     * same numbers.
     */
    private AiDocument insert(Long boothId, Long agentId, Long userId, UploadRequest request,
                              ObjectStorage.WriteTarget target, Long replaces) {
        AiDocument document = new AiDocument(boothId, agentId, userId, request.fileName(),
                request.contentType(), request.size(), request.contentSha256(), target, replaces,
                Instant.now());
        // saveAndFlush, not save: the id has to exist before the object key can be built, and
        // flushing here also means an index violation surfaces inside this transaction instead
        // of at commit time, where the stack trace no longer says which insert caused it.
        document = documents.saveAndFlush(document);
        document.assignObjectKey(objectKeyOf(document));
        requireRoom(agentId);
        return document;
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
                Base64.getEncoder().encodeToString(HexFormat.of().parseHex(document.getContentSha256())),
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

    /** FR-018's two limits, read off the logical totals — see {@link #insert} for the ordering. */
    private void requireRoom(Long agentId) {
        int countLimit = agentProperties.documentCountLimit();
        if (documents.countActive(agentId) > countLimit) {
            throw AiDocumentLimitException.byCount(countLimit);
        }
        DataSize totalLimit = agentProperties.documentTotalBytes();
        if (documents.sumActiveBytes(agentId) > totalLimit.toBytes()) {
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
                agentProperties.documentTotalBytes().toBytes(),
                documents.countActive(agentId), documents.sumActiveBytes(agentId)));
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

    // ── 삭제 ────────────────────────────────────────────────────────────────

    /**
     * 부스 편집자가 누른 삭제 버튼 (FR-012, C-15, S15P21A604-831).
     *
     * <p><b>하드 삭제다.</b> {@code AccountDeletionService} 가 회원 탈퇴에서 이미 같은 일을 한다 —
     * {@code ai_document_jobs}·{@code ai_document_chunks}·{@code storage_reconciliation_log} 는
     * {@code ai_documents} 에 {@code ON DELETE CASCADE} 로 걸려 있어(V21·V25) 행 하나만 지우면
     * 나머지는 스키마가 정리한다. R2 원본은 이 트랜잭션 안에서 지우지 않는다 — 네트워크 호출을 행
     * 잠금 밑에 두지 않는 것은 이 클래스의 다른 모든 메서드와 같은 규칙이고, {@code AiDocumentService}
     * 밖에서 이미 걷는 경로({@code AccountDeletionService})를 그대로 따라 {@code game_asset_delete_queue}
     * 에 적어 넣는다 — R2 삭제는 그 큐의 기존 워커가 비동기로 처리한다.
     *
     * <p><b>업로드가 끝난 {@code QUEUED}·{@code PROCESSING}만 거부한다(409).</b> 이 둘은 살아 있는
     * {@code ai_document_jobs} 행을 가질 수 있어, 여기서 지우면 CASCADE가 그 행을 워커의 다음 callback
     * 전에 걷어 간다. 반면 {@code QUEUED}라도 {@link AiDocument#isAwaitingUpload()}이면 아직 Job이 없어
     * 삭제할 수 있다. {@code READY}·{@code FAILED}·{@code EXPIRED}·{@code DISABLED}도 Job이 없거나 이미
     * 끝난 상태라 안전하다.
     *
     * <p><b>이 문서를 대상으로 진행 중인 교체(FR-019)가 있으면 거부한다(409).</b>
     * {@code replaces_document_id} 는 {@code ON DELETE SET NULL}(V30)이라 여기서 원본을 지우면 교체본의
     * 그 컬럼이 조용히 비고, {@code AiDocumentJobRepository.retireReplacedOriginal} 은 나중에 finalize가
     * 끝나도 물릴 원본을 찾지 못해 아무 일도 하지 않는다 — 원본이 이미 사라졌으니 데이터가 깨지지는
     * 않지만(교체본은 {@code markDocumentReady} 로 독립적으로 READY 가 된다), FR-019 가 약속하는 "교체가
     * 끝날 때까지 원본은 살아 있다"를 사용자가 지워서 깨는 셈이라 미리 막는다. 교체본이 이미
     * {@code READY} 라면(=이미 끝나 원본을 물렸거나 물릴 수 없는 상태였던 경우) 막지 않는다 — 안 그러면
     * 물려난 원본이 영원히 삭제 불가능해진다({@code findActiveReplacementOf} 는 상태와 무관하게 계속
     * 이 문서를 가리킨다).
     *
     * <p><b>활성 임대는 요구하지 않는다.</b> 임대 만료·반납으로 {@code DISABLED}가 된 보존 문서를
     * 편집자가 정리할 수 있어야 하므로 권한만 검사한다. 변경 권한과 관리자 감사 기록은
     * {@code requireModifier}가 맡는다.
     */
    public void delete(Long documentId, Long userId) {
        transactions.executeWithoutResult(status -> {
            AiDocument document = documents.findWithLockById(documentId)
                    .orElseThrow(() -> new AiDocumentNotFoundException(documentId));
            accessGuard.requireModifier(document.getBoothId(), userId);

            if ((AiDocument.QUEUED.equals(document.getProcessingStatus())
                    && !document.isAwaitingUpload())
                    || AiDocument.PROCESSING.equals(document.getProcessingStatus())) {
                throw new ApiException(ErrorCode.DOCUMENT_NOT_DELETABLE,
                        "처리 중인 문서는 삭제할 수 없습니다. 완료된 뒤 다시 시도해 주세요. 현재 상태: "
                                + document.getProcessingStatus());
            }
            documents.findActiveReplacementOf(documentId)
                    .filter(replacement -> !AiDocument.READY.equals(replacement.getProcessingStatus()))
                    .ifPresent(replacement -> {
                        throw new ApiException(ErrorCode.DOCUMENT_NOT_DELETABLE,
                                "이 문서를 교체하는 작업이 진행 중입니다. 완료된 뒤 다시 시도해 주세요.");
                    });

            // EXPIRED 문서는 sweeper 가 원본을 지운 뒤 s3_key 를 NULL 로 비운다 — 저장소에 지울 것이
            // 없는 정상 상태다. 그 행을 큐에 넣으면 object_key NOT NULL 로 500 이 나 삭제 자체가 막혔다
            // (Demo 문서 7, S15P21A604-939). 좌표가 있을 때만 큐로 옮기고 행 삭제는 그대로 진행한다.
            if (document.getObjectKey() != null) {
                jdbc.update("""
                        INSERT INTO game_asset_delete_queue (provider, storage_bucket, object_key)
                        VALUES (?, ?, ?)
                        ON CONFLICT (provider, storage_bucket, object_key) DO NOTHING
                        """, document.getStorageProvider(), document.getStorageBucket(),
                        document.getObjectKey());
            }
            documents.delete(document);
        });
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
        if (document.getReplacedAt() != null) {
            // FR-027a. EXPIRED here means "a newer version took this document's place", not
            // "the bytes never arrived" — and the 24-hour recovery window (FR-027) belongs to the
            // second reading only. Recovering this row would put the old version back into the
            // active set alongside the one that replaced it.
            //
            // Checked before every other branch, on both reads, so the answer never depends on
            // what storage says: the object may well still be sitting there until the delete sweep
            // takes it (FR-028), and it is not what decides this.
            throw new ApiException(ErrorCode.DOCUMENT_UPLOAD_GONE,
                    "이 문서는 수정본으로 교체되었습니다. 교체본을 사용해 주세요.");
        }
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
            // The same argument for the replacement index (V30). Two ways this row's recovery would
            // break ux_ai_documents_active_replacement: something is already being uploaded over
            // this document, or — if this row is itself an abandoned 교체 attempt — over the same
            // target it was meant to replace. Either way the live attempt is the one the owner
            // started last, and this one is the grant that ran out.
            boolean replacementPending =
                    documents.findActiveReplacementOf(documentId).isPresent()
                            || (document.getReplacesDocumentId() != null && documents
                                    .findActiveReplacementOf(document.getReplacesDocumentId())
                                    .isPresent());
            if (replacementPending) {
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
     *
     * <p><b>{@code replacedAt} is the second thing {@code status} cannot say.</b> An
     * {@code EXPIRED} row with it set was pushed out by a newer version; without it the upload
     * simply never arrived and can still be completed for 24 hours (FR-027 vs FR-027a). The screen
     * has to tell those apart — offering "다시 올려 주세요" on a document that was replaced is the
     * shape of T-24, a control that reads as working and does nothing. Adding a
     * {@code DocumentStatus} value instead would drag the V24 check, the sweep predicates, OpenAPI
     * and every existing test along for one bit of information.
     */
    public record DocumentView(Long documentId, String fileName, long sizeBytes, String status,
                               Instant createdAt, Instant uploadedAt, Instant replacedAt) {

        static DocumentView of(AiDocument document) {
            return new DocumentView(document.getId(), document.getOriginalFilename(),
                    document.getSizeBytes(), document.getProcessingStatus(),
                    document.getCreatedAt(), document.getUploadedAt(), document.getReplacedAt());
        }
    }

    /**
     * FR-018's two limits and how much of them is spent.
     *
     * <p>The limits are on the response because otherwise they surface only as a refusal:
     * {@code 409 DOCUMENT_LIMIT_EXCEEDED}, on a grant the user has already chosen a file for.
     *
     * <p><b>{@code usedCount} and {@code usedBytes} used to be left out</b>, on the grounds that the
     * caller can count the array it just received. 수정본 교체 ended that: while a replacement is in
     * flight the array holds eleven rows on a ten-slot agent and every one of them is in an active
     * status, so counting them shows "11/10" beside an upload that will in fact succeed. The
     * successor stands for its original's slot (see {@code AiDocumentRepository.countActive}), and
     * that subtraction is a server-side fact — two queries per poll is what it costs to not make
     * every client re-derive it, and get it wrong.
     */
    public record QuotaView(int countLimit, long bytesLimit, long usedCount, long usedBytes) { }

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
