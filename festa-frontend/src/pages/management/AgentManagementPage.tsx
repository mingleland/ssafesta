// AI 직원 관리 — 부스 소유자가 AI 직원(이름·역할·말투·프롬프트)을 만들고 참고 문서를 올린다.
// 진입: Booth Management → AI 직원 [관리]. ProjectManagementPage와 같은 골격(PageShell, dirty
// 편집 모델)을 그대로 따른다 — 새 패턴을 만들지 않는다.
//
// 상태 5종(대기/처리 중/준비완료/실패/비활성화) 중 서버가 실제로 아는 것은 앞 3개뿐이다(BE
// AiDocument.processingStatus: QUEUED/PROCESSING/READY/EXPIRED). 실패(job이 DEAD로 확정돼도
// 문서 상태로 반영되는 코드가 없다, S15P21A604-174 예정)와 비활성화(AiAgent.java: "There is no
// lifecycle here")는 서버에 그 개념 자체가 없어 여기서도 만들어 붙이지 않는다 — 라벨만 두고
// 비활성 처리한다. 문서 상태 조회 API도 없어(같은 티켓) 방금 올린 문서의 QUEUED 이후 전환은
// 이 화면에서 확인할 수 없다 — 숨기지 않고 안내 문구로 알린다.
import { useEffect, useRef } from 'react';
import { useParams } from 'react-router-dom';
import {
  loadAgentEdit,
  saveAgent,
  updateAgentField,
  useAgentEdit,
} from '../../features/agent/model/edit';
import { uploadDocument, useDocumentRows } from '../../features/agent/model/documents';
import type { DocumentProcessingStatus } from '../../entities/agent/types';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../features/world/model/gameClientUi';
import { PageShell, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import './management.css';

const ROLE_OPTIONS: { value: 'PROJECT_DOCENT' | 'GUIDE'; label: string }[] = [
  { value: 'PROJECT_DOCENT', label: '프로젝트 도슨트 — 전시 설명 위주' },
  { value: 'GUIDE', label: '가이드 — 부스 전반 안내' },
];

const TONE_OPTIONS: { value: 'FRIENDLY' | 'PROFESSIONAL' | 'ENTHUSIASTIC'; label: string }[] = [
  { value: 'FRIENDLY', label: '친근하게' },
  { value: 'PROFESSIONAL', label: '전문적으로' },
  { value: 'ENTHUSIASTIC', label: '열정적으로' },
];

const DOC_STATUS_LABEL: Record<DocumentProcessingStatus, string> = {
  QUEUED: '대기',
  PROCESSING: '처리 중',
  READY: '준비완료',
  EXPIRED: '만료',
};

/** 상태 5종 범례 — 뒤 2개는 서버에 없는 상태라 항상 비활성으로 그린다 */
const STATUS_LEGEND: { key: string; label: string; wired: boolean }[] = [
  { key: 'QUEUED', label: '대기', wired: true },
  { key: 'PROCESSING', label: '처리 중', wired: true },
  { key: 'READY', label: '준비완료', wired: true },
  { key: 'FAILED', label: '실패', wired: false },
  { key: 'INACTIVE', label: '비활성화', wired: false },
];

export function AgentManagementPage() {
  const { boothId } = useParams<{ boothId: string }>();
  const boothIdNum = Number(boothId);
  const state = useAgentEdit();
  const documents = useDocumentRows();
  const fileInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (Number.isFinite(boothIdNum)) void loadAgentEdit(boothIdNum);
  }, [boothIdNum]);

  const saving = state.save.phase === 'submitting';
  const canSave = state.agentId === null
    ? state.draft.name.trim() !== '' && state.draft.role !== '' && state.draft.systemPrompt.trim() !== ''
    : state.dirty.size > 0;

  if (state.status === 'idle' || state.status === 'loading') {
    return (
      <PageShell title="AI 직원 관리" backTo={WORLD_RETURN_TO_MANAGEMENT}>
        <ScreenLoading label="AI 직원 정보를 불러오는 중..." />
      </PageShell>
    );
  }
  if (state.status === 'error') {
    return (
      <PageShell title="AI 직원 관리" backTo={WORLD_RETURN_TO_MANAGEMENT}>
        <ScreenError
          title="AI 직원 정보를 불러오지 못했습니다"
          message="잠시 후 다시 시도해 주세요."
          onRetry={() => void loadAgentEdit(boothIdNum)}
        />
      </PageShell>
    );
  }

  const hasAgent = state.agentId !== null;

  return (
    <PageShell
      title="AI 직원 관리"
      subtitle={hasAgent ? '방문자에게 응답하는 AI 직원 설정' : '아직 AI 직원이 없습니다 — 저장하면 새로 만들어집니다'}
      backTo={WORLD_RETURN_TO_MANAGEMENT}
      actions={
        <button type="button" className="sc-btn sc-btn-primary" disabled={saving || !canSave} onClick={() => void saveAgent()}>
          {saving ? '저장 중...' : hasAgent ? '부스 수정' : '만들기'}
        </button>
      }
    >
      <form className="sc-card mg-form" onSubmit={(e) => e.preventDefault()}>
        <label className={'mg-field' + (state.dirty.has('name') ? ' mg-field-dirty' : '')}>
          <span className="mg-label">
            이름
            {state.dirty.has('name') && <span className="mg-dirty-dot" aria-label="변경됨" />}
          </span>
          <input
            className="mg-input"
            type="text"
            value={state.draft.name}
            disabled={saving}
            placeholder="예: 도담이"
            onChange={(e) => updateAgentField('name', e.target.value)}
          />
        </label>

        <label className={'mg-field' + (state.dirty.has('role') ? ' mg-field-dirty' : '')}>
          <span className="mg-label">
            역할
            {state.dirty.has('role') && <span className="mg-dirty-dot" aria-label="변경됨" />}
          </span>
          <select
            className="mg-input"
            value={state.draft.role}
            disabled={saving}
            onChange={(e) => updateAgentField('role', e.target.value as 'PROJECT_DOCENT' | 'GUIDE')}
          >
            <option value="" disabled>
              역할을 선택하세요
            </option>
            {ROLE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>

        <label className={'mg-field' + (state.dirty.has('tone') ? ' mg-field-dirty' : '')}>
          <span className="mg-label">
            말투
            {state.dirty.has('tone') && <span className="mg-dirty-dot" aria-label="변경됨" />}
          </span>
          <select
            className="mg-input"
            value={state.draft.tone}
            disabled={saving}
            onChange={(e) => updateAgentField('tone', e.target.value as 'FRIENDLY' | 'PROFESSIONAL' | 'ENTHUSIASTIC')}
          >
            {TONE_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </label>

        <label className={'mg-field' + (state.dirty.has('systemPrompt') ? ' mg-field-dirty' : '')}>
          <span className="mg-label">
            프롬프트
            {state.dirty.has('systemPrompt') && <span className="mg-dirty-dot" aria-label="변경됨" />}
          </span>
          <textarea
            className="mg-input mg-textarea"
            rows={5}
            value={state.draft.systemPrompt}
            disabled={saving}
            placeholder="AI 직원이 답변할 때 지킬 지침을 적어 주세요."
            onChange={(e) => updateAgentField('systemPrompt', e.target.value)}
          />
        </label>

        <div className="mg-form-foot">
          {state.save.phase === 'success' && <span className="mg-ok">저장했습니다</span>}
          {state.save.phase === 'error' && (
            <span className="sc-alert" role="alert">
              {state.save.message ?? '저장하지 못했습니다.'}
            </span>
          )}
        </div>
      </form>

      <section className="sc-card ag-docs">
        <span className="ag-section-title">문서 업로드</span>
        <p className="sc-note">부스 자료를 올리면 AI 직원이 이 문서를 근거로 답합니다.</p>
        {!hasAgent && <p className="sc-note ag-doc-disabled">먼저 AI 직원을 만들어야 문서를 올릴 수 있어요.</p>}
        <input
          ref={fileInputRef}
          type="file"
          className="ag-file-input"
          disabled={!hasAgent}
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file !== undefined && state.agentId !== null) void uploadDocument(state.agentId, file);
            if (fileInputRef.current) fileInputRef.current.value = '';
          }}
        />
        {documents.length > 0 && (
          <ul className="ag-doc-list">
            {documents.map((d) => (
              <li key={d.tempId} className="ag-doc-row">
                <span className="ag-doc-name">{d.fileName}</span>
                <span className={'ag-doc-status ag-doc-status-' + d.phase}>
                  {d.phase === 'uploading' && '업로드 중...'}
                  {d.phase === 'completing' && '확인 중...'}
                  {d.phase === 'done' && (d.processingStatus !== undefined ? DOC_STATUS_LABEL[d.processingStatus] : '완료')}
                  {d.phase === 'error' && (d.errorMessage ?? '실패')}
                  {d.duplicate === true && ' (이미 등록됨)'}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p className="sc-note ag-doc-note">
          업로드 직후 상태는 항상 "대기"로 시작합니다. 이후 처리 진행 상황을 조회하는 API가 아직
          없어(S15P21A604-174), 처리 중·준비완료로 바뀌어도 이 화면에서는 확인할 수 없습니다 — 새로고침하면
          이 목록도 사라집니다.
        </p>
      </section>

      <section className="sc-card ag-status">
        <span className="ag-section-title">상태</span>
        <div className="ag-status-legend">
          {STATUS_LEGEND.map((s) => (
            <span key={s.key} className={'ag-status-badge' + (s.wired ? '' : ' ag-status-badge-off')}>
              {s.label}
              {!s.wired && <span className="ag-status-off-note">API 없음</span>}
            </span>
          ))}
        </div>
      </section>
    </PageShell>
  );
}
