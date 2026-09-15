// 내 부스 관리의 AI 직원 탭 — 부스 편집자가 직원의 대화 설정을 생성·수정하는 화면이다.
import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createAiAgent,
  deleteAiDocument,
  getAiAgent,
  listAiDocuments,
  uploadAiDocument,
  updateAiAgent,
  type AiAgent,
  type AiAgentCommand,
  type AiAgentResponseLength,
  type AiAgentRole,
  type AiAgentTone,
  type AiDocumentStatus,
} from '../../../entities/aiAgent/api';
import { isApiError } from '../../../shared/api/client';
import { OverlayError, OverlayLoading } from '../../overlay/ui/OverlayFrame';

type FormState = {
  name: string;
  role: AiAgentRole;
  tone: AiAgentTone;
  responseLength: AiAgentResponseLength;
  systemPrompt: string;
  forbiddenTopics: string;
  handoffEnabled: boolean;
};

const emptyForm: FormState = {
  name: '',
  role: 'PROJECT_DOCENT',
  tone: 'FRIENDLY',
  responseLength: 'MEDIUM',
  systemPrompt: '',
  forbiddenTopics: '',
  handoffEnabled: false,
};

// 이름·프롬프트를 비워둔 채 등록해도 막지 않는다(S15P21A604-724) — 백엔드는 두 필드를
// 여전히 필수로 요구하므로(AiAgentService.validatedName/validatedPrompt), 빈 채로 보내면
// 저장 직전에 이 기본값으로 채운다. 계약을 바꾸는 게 아니라 FE가 대신 채워 넣는 것이다.
const DEFAULT_AGENT_NAME = 'FESTA 안내 직원';
const DEFAULT_SYSTEM_PROMPT =
  '방문객의 질문에 친절하고 정확하게 답합니다. 모르는 내용은 모른다고 답하고, 확인되지 않은 정보를 지어내지 않습니다.';

function toForm(agent: AiAgent | null): FormState {
  if (!agent) return emptyForm;
  return {
    name: agent.name,
    role: agent.role,
    tone: agent.tone,
    responseLength: agent.responseLength,
    systemPrompt: agent.systemPrompt,
    forbiddenTopics: agent.forbiddenTopics.join(', '),
    handoffEnabled: agent.handoffEnabled,
  };
}

function toCommand(form: FormState): AiAgentCommand {
  return {
    name: form.name.trim(),
    role: form.role,
    tone: form.tone,
    responseLength: form.responseLength,
    systemPrompt: form.systemPrompt.trim(),
    servicePrice: 0,
    handoffEnabled: form.handoffEnabled,
    forbiddenTopics: form.forbiddenTopics.split(',').map((topic) => topic.trim()).filter(Boolean),
  };
}

const STATUS_LABEL: Record<AiDocumentStatus, string> = {
  QUEUED: '대기',
  PROCESSING: '처리중',
  READY: '준비완료',
  FAILED: '실패',
  EXPIRED: '만료',
  DISABLED: '비활성화',
};

const STATUS_CLASS: Record<AiDocumentStatus, string> = {
  QUEUED: 'bm-document-status-queued',
  PROCESSING: 'bm-document-status-processing',
  READY: 'bm-document-status-ready',
  FAILED: 'bm-document-status-failed',
  EXPIRED: 'bm-document-status-expired',
  DISABLED: 'bm-document-status-disabled',
};

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

export function AiAgentManagementTab({ boothId }: { boothId: number }) {
  const queryClient = useQueryClient();
  const agentQuery = useQuery({ queryKey: ['ai-agent', boothId], queryFn: () => getAiAgent(boothId) });
  const agentId = agentQuery.data?.agentId ?? null;
  const documentsQuery = useQuery({
    queryKey: ['ai-agent-documents', agentId],
    queryFn: () => listAiDocuments(agentId as number),
    enabled: agentId !== null,
  });
  const [form, setForm] = useState<FormState>(emptyForm);
  const [error, setError] = useState<string | null>(null);
  const [uploadMessage, setUploadMessage] = useState<string | null>(null);
  const [pendingFiles, setPendingFiles] = useState<File[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (agentQuery.data !== undefined) setForm(toForm(agentQuery.data));
  }, [agentQuery.data]);

  const saveMutation = useMutation({
    mutationFn: (nextForm: FormState) => {
      const command = toCommand(nextForm);
      return agentQuery.data
        ? updateAiAgent(agentQuery.data.agentId, command)
        : createAiAgent(boothId, command);
    },
    // 등록 폼에서 미리 골라둔 문서를 저장 직후 같은 agentId 로 순차 업로드한다 —
    // "저장 먼저, 업로드는 그다음 화면에서" 두 단계를 사용자가 거치지 않게 한다.
    onSuccess: async (agent) => {
      await queryClient.invalidateQueries({ queryKey: ['ai-agent', boothId] });
      setError(null);
      if (pendingFiles.length > 0) {
        const files = pendingFiles;
        setPendingFiles([]);
        setUploadMessage(`문서 ${files.length}건 업로드 중...`);
        let uploaded = 0;
        for (const file of files) {
          try {
            await uploadAiDocument(agent.agentId, file);
            uploaded += 1;
          } catch {
            // 개별 문서 업로드 실패는 등록 자체를 무효화하지 않는다 — 건너뛰고 계속한다
          }
        }
        setUploadMessage(
          uploaded === files.length ? `문서 ${uploaded}건 업로드 완료` : `문서 ${uploaded}/${files.length}건 업로드 완료 (일부 실패)`,
        );
        await queryClient.invalidateQueries({ queryKey: ['ai-agent-documents', agent.agentId] });
      }
    },
    onError: (cause) => setError(isApiError(cause) ? cause.message : 'AI 직원 설정을 저장하지 못했습니다.'),
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      if (!agentQuery.data) throw new Error('AI 직원 설정을 먼저 저장해 주세요.');
      return uploadAiDocument(agentQuery.data.agentId, file);
    },
    onSuccess: async (result) => {
      setUploadMessage(result.duplicate ? '이미 등록된 문서입니다.' : `업로드 완료: 처리 대기(${result.processingStatus})`);
      setError(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
      await queryClient.invalidateQueries({ queryKey: ['ai-agent-documents', agentId] });
    },
    onError: (cause) => setError(isApiError(cause) ? cause.message : cause instanceof Error ? cause.message : '문서를 업로드하지 못했습니다.'),
  });

  const deleteMutation = useMutation({
    mutationFn: (documentId: number) => deleteAiDocument(documentId),
    onSuccess: async () => {
      setUploadMessage('문서를 삭제했습니다.');
      setError(null);
      await queryClient.invalidateQueries({ queryKey: ['ai-agent-documents', agentId] });
    },
    onError: (cause) => setError(isApiError(cause) ? cause.message : cause instanceof Error ? cause.message : '문서를 삭제하지 못했습니다.'),
  });

  function removeDocument(documentId: number, fileName: string) {
    if (!window.confirm(`'${fileName}' 문서를 삭제할까요? 되돌릴 수 없습니다.`)) return;
    setUploadMessage(null);
    deleteMutation.mutate(documentId);
  }

  // 이름·프롬프트를 비워도 막지 않는다 — 비워둔 채 제출하면 기본값을 채워 넣고 그 값으로
  // 저장한다. 화면에도 실제 저장되는 값을 그대로 반영해 나중에 "왜 이렇게 저장됐지"가 없게 한다.
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const next: FormState = {
      ...form,
      name: form.name.trim() || DEFAULT_AGENT_NAME,
      systemPrompt: form.systemPrompt.trim() || DEFAULT_SYSTEM_PROMPT,
    };
    setForm(next);
    setError(null);
    saveMutation.mutate(next);
  }

  function selectFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploadMessage(null);
    uploadMutation.mutate(file);
  }

  // 등록 전(agentId 없음) 단계 — 선택만 해두고 실제 업로드는 저장 성공 뒤 saveMutation.onSuccess 가 한다
  function stagePendingFiles(event: ChangeEvent<HTMLInputElement>) {
    const files = event.target.files;
    if (!files || files.length === 0) return;
    setPendingFiles((prev) => {
      const next = [...prev];
      for (const file of Array.from(files)) {
        if (!next.some((existing) => existing.name === file.name && existing.size === file.size)) next.push(file);
      }
      return next;
    });
    event.target.value = '';
  }

  function removePendingFile(index: number) {
    setPendingFiles((prev) => prev.filter((_, i) => i !== index));
  }

  if (agentQuery.isLoading) return <OverlayLoading label="AI 직원 설정을 불러오는 중..." />;
  if (agentQuery.isError) {
    return <OverlayError title="AI 직원 설정을 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void agentQuery.refetch()} />;
  }

  const editing = agentQuery.data !== null;
  return (
    <form className="bm-agent" onSubmit={submit}>
      <div className="bm-agent-head">
        <div>
          <h3>AI 직원 {editing ? '설정' : '등록'}</h3>
          <p className="ov-note">부스당 한 명만 운영할 수 있으며, 저장 후 문서를 등록해 답변 근거를 채울 수 있습니다. 이름·프롬프트를 비워두면 기본값으로 등록됩니다.</p>
        </div>
        <span className={'bm-agent-state' + (editing ? ' bm-agent-state-on' : '')}>{editing ? '운영 설정됨' : '미등록'}</span>
      </div>

      {/* 등록→문서 업로드 순서를 1·2 단계로 보여준다 — 저장 전에는 "AI 직원을 먼저 등록해야
          문서를 올릴 수 있다"는 게 화면만 봐서는 드러나지 않았다(S15P21A604-758). 순서·제출
          동작은 그대로고 레이아웃·라벨링만 바뀐다. */}
      <div className="bm-step">
        <div className="bm-step-marker" aria-hidden="true">
          <span className="bm-step-badge">1</span>
          <span className="bm-step-line" />
        </div>
        <div className="bm-step-body">
          <p className="bm-step-title">기본 정보</p>
          <label className="bm-field">
            <span>직원 이름</span>
            <input value={form.name} maxLength={100} onChange={(event) => setForm({ ...form, name: event.target.value })} placeholder="예: FESTA 안내 직원" />
          </label>
          <div className="bm-field-grid">
            <label className="bm-field"><span>역할</span><select value={form.role} onChange={(event) => setForm({ ...form, role: event.target.value as AiAgentRole })}><option value="PROJECT_DOCENT">프로젝트 도슨트</option><option value="GUIDE">행사 안내</option></select></label>
            <label className="bm-field"><span>말투</span><select value={form.tone} onChange={(event) => setForm({ ...form, tone: event.target.value as AiAgentTone })}><option value="FRIENDLY">친근하게</option><option value="PROFESSIONAL">전문적으로</option><option value="ENTHUSIASTIC">활기차게</option></select></label>
            <label className="bm-field"><span>답변 길이</span><select value={form.responseLength} onChange={(event) => setForm({ ...form, responseLength: event.target.value as AiAgentResponseLength })}><option value="SHORT">짧게</option><option value="MEDIUM">보통</option><option value="LONG">자세히</option></select></label>
          </div>
          <label className="bm-field"><span>시스템 프롬프트</span><textarea value={form.systemPrompt} onChange={(event) => setForm({ ...form, systemPrompt: event.target.value })} placeholder="AI 직원이 지켜야 할 답변 원칙을 작성하세요." rows={5} /></label>
          <label className="bm-field"><span>금지 주제 <em>쉼표로 구분</em></span><input value={form.forbiddenTopics} onChange={(event) => setForm({ ...form, forbiddenTopics: event.target.value })} placeholder="예: 개인정보, 경쟁사 비방" /></label>
          <label className="bm-check"><input type="checkbox" checked={form.handoffEnabled} onChange={(event) => setForm({ ...form, handoffEnabled: event.target.checked })} /> 사람 상담으로 연결 허용</label>
          {error && <p className="bm-agent-error" role="alert">{error}</p>}
          <button type="submit" className="ov-btn ov-btn-primary" disabled={saveMutation.isPending}>{saveMutation.isPending ? '저장 중...' : editing ? '설정 저장' : 'AI 직원 등록'}</button>
        </div>
      </div>

      <div className="bm-step">
        <div className="bm-step-marker" aria-hidden="true">
          <span className="bm-step-badge">2</span>
        </div>
        <div className="bm-step-body">
          <section className="bm-document" aria-labelledby="ai-document-title">
            <div>
              <h4 id="ai-document-title">답변 근거 문서</h4>
              <p className="ov-note">PDF, Markdown, 텍스트 파일을 최대 20MB까지 올릴 수 있습니다. 업로드 후 처리 완료 전까지는 답변 근거에 쓰이지 않습니다.</p>
            </div>
            {!editing && (
              <>
                <label className="ov-btn bm-upload">
                  파일 선택
                  <input type="file" multiple accept=".pdf,.md,.txt,application/pdf,text/markdown,text/plain" onChange={stagePendingFiles} />
                </label>
                <p className="ov-note">선택한 문서는 &apos;AI 직원 등록&apos; 저장과 함께 업로드됩니다.</p>
                {pendingFiles.length > 0 && (
                  <ul className="bm-document-list">
                    {pendingFiles.map((file, index) => (
                      <li key={`${file.name}-${file.size}-${index}`} className="bm-document-row">
                        <span className="bm-document-name">{file.name}</span>
                        <span className="bm-document-meta">{formatBytes(file.size)}</span>
                        <button type="button" className="bm-document-remove" onClick={() => removePendingFile(index)}>
                          제거
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}

            {editing && (
              <label className={'ov-btn bm-upload' + (uploadMutation.isPending ? ' bm-upload-disabled' : '')}>
                {uploadMutation.isPending ? '업로드 중...' : '문서 업로드'}
                <input ref={fileInputRef} type="file" accept=".pdf,.md,.txt,application/pdf,text/markdown,text/plain" disabled={uploadMutation.isPending} onChange={selectFile} />
              </label>
            )}
            {uploadMessage && <p className="bm-upload-result" role="status">{uploadMessage}</p>}

            {editing && (
              <div className="bm-document-list-wrap">
                {documentsQuery.data && (
                  <span className="ov-note bm-document-quota">
                    {documentsQuery.data.documents.filter((d) => d.status === 'QUEUED' || d.status === 'PROCESSING' || d.status === 'READY').length}
                    /{documentsQuery.data.quota.countLimit}개 ·{' '}
                    {formatBytes(documentsQuery.data.documents.reduce((sum, d) => sum + d.sizeBytes, 0))}
                    /{formatBytes(documentsQuery.data.quota.bytesLimit)}
                  </span>
                )}
                {documentsQuery.isLoading && <p className="ov-note">문서 목록을 불러오는 중...</p>}
                {documentsQuery.isError && <p className="ov-note">문서 목록을 불러오지 못했습니다.</p>}
                {documentsQuery.data?.documents.length === 0 && <p className="ov-note">아직 올린 문서가 없습니다.</p>}
                {documentsQuery.data && documentsQuery.data.documents.length > 0 && (
                  <ul className="bm-document-list">
                    {documentsQuery.data.documents.map((doc) => (
                      <li key={doc.documentId} className="bm-document-row">
                        <span className="bm-document-name">{doc.fileName}</span>
                        <span className="bm-document-meta">{formatBytes(doc.sizeBytes)}</span>
                        <span className={'bm-document-status ' + STATUS_CLASS[doc.status]}>{STATUS_LABEL[doc.status]}</span>
                        <button
                          type="button"
                          className="bm-document-remove"
                          disabled={deleteMutation.isPending && deleteMutation.variables === doc.documentId}
                          onClick={() => removeDocument(doc.documentId, doc.fileName)}
                        >
                          {deleteMutation.isPending && deleteMutation.variables === doc.documentId ? '삭제 중...' : '삭제'}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </section>
        </div>
      </div>
    </form>
  );
}
