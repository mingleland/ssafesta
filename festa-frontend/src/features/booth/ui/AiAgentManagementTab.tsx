// 내 부스 관리의 AI 직원 탭 — 부스 편집자가 직원의 대화 설정을 생성·수정하는 화면이다.
import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createAiAgent,
  getAiAgent,
  uploadAiDocument,
  updateAiAgent,
  type AiAgent,
  type AiAgentCommand,
  type AiAgentResponseLength,
  type AiAgentRole,
  type AiAgentTone,
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

export function AiAgentManagementTab({ boothId }: { boothId: number }) {
  const queryClient = useQueryClient();
  const agentQuery = useQuery({ queryKey: ['ai-agent', boothId], queryFn: () => getAiAgent(boothId) });
  const [form, setForm] = useState<FormState>(emptyForm);
  const [error, setError] = useState<string | null>(null);
  const [uploadMessage, setUploadMessage] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (agentQuery.data !== undefined) setForm(toForm(agentQuery.data));
  }, [agentQuery.data]);

  const saveMutation = useMutation({
    mutationFn: () => {
      const command = toCommand(form);
      return agentQuery.data
        ? updateAiAgent(agentQuery.data.agentId, command)
        : createAiAgent(boothId, command);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['ai-agent', boothId] });
      setError(null);
    },
    onError: (cause) => setError(isApiError(cause) ? cause.message : 'AI 직원 설정을 저장하지 못했습니다.'),
  });

  const uploadMutation = useMutation({
    mutationFn: (file: File) => {
      if (!agentQuery.data) throw new Error('AI 직원 설정을 먼저 저장해 주세요.');
      return uploadAiDocument(agentQuery.data.agentId, file);
    },
    onSuccess: (result) => {
      setUploadMessage(result.duplicate ? '이미 등록된 문서입니다.' : `업로드 완료: 처리 대기(${result.processingStatus})`);
      setError(null);
      if (fileInputRef.current) fileInputRef.current.value = '';
    },
    onError: (cause) => setError(isApiError(cause) ? cause.message : cause instanceof Error ? cause.message : '문서를 업로드하지 못했습니다.'),
  });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form.name.trim() || !form.systemPrompt.trim()) {
      setError('직원 이름과 시스템 프롬프트를 입력해 주세요.');
      return;
    }
    saveMutation.mutate();
  }

  function selectFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploadMessage(null);
    uploadMutation.mutate(file);
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
          <p className="ov-note">부스당 한 명만 운영할 수 있으며, 저장 후 문서를 등록해 답변 근거를 채울 수 있습니다.</p>
        </div>
        <span className={'bm-agent-state' + (editing ? ' bm-agent-state-on' : '')}>{editing ? '운영 설정됨' : '미등록'}</span>
      </div>

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

      <section className="bm-document" aria-labelledby="ai-document-title">
        <div>
          <h4 id="ai-document-title">답변 근거 문서</h4>
          <p className="ov-note">PDF, Markdown, 텍스트 파일을 최대 20MB까지 올릴 수 있습니다. 업로드 후 처리 완료 전까지는 답변 근거에 쓰이지 않습니다.</p>
        </div>
        <label className={'ov-btn bm-upload' + (!editing || uploadMutation.isPending ? ' bm-upload-disabled' : '')}>
          {uploadMutation.isPending ? '업로드 중...' : '문서 업로드'}
          <input ref={fileInputRef} type="file" accept=".pdf,.md,.txt,application/pdf,text/markdown,text/plain" disabled={!editing || uploadMutation.isPending} onChange={selectFile} />
        </label>
        {!editing && <p className="ov-note">AI 직원 설정을 먼저 저장하면 문서를 등록할 수 있습니다.</p>}
        {uploadMessage && <p className="bm-upload-result" role="status">{uploadMessage}</p>}
      </section>
    </form>
  );
}
