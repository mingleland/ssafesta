// AI 직원 관리 — 부스 소유자의 AI 직원 대화 설정·답변 근거 문서 관리 화면.
// 진입: Booth Management → AI 직원 [관리]. PROJECT/SURVEY/CONSULTATION 과 같은
// drill-down 패턴으로 별도 화면을 연다.
import { useParams } from 'react-router-dom';
import { AiAgentManagementTab } from '../../features/booth/ui/AiAgentManagementTab';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../features/world/model/gameClientUi';
import { PageShell } from '../../features/shell/ui/PageShell';
import '../../features/booth/ui/boothManagement.css';

export function AiAgentManagementPage() {
  const { boothId } = useParams<{ boothId: string }>();

  return (
    <PageShell title="AI 직원 관리" subtitle="대화 설정·답변 근거 문서 관리" backTo={WORLD_RETURN_TO_MANAGEMENT}>
      <AiAgentManagementTab boothId={Number(boothId)} />
    </PageShell>
  );
}
