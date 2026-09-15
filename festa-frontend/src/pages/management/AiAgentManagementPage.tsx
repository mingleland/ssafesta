// AI 직원 관리 — 부스 소유자의 AI 직원 대화 설정·답변 근거 문서 관리 화면.
// 진입: Booth Management → AI 직원 [관리]. PROJECT/SURVEY/CONSULTATION 과 같은
// drill-down 패턴으로 별도 화면을 연다.
import { AiAgentManagementTab } from '../../features/booth/ui/AiAgentManagementTab';
import { ManagementScreen, useManagementBoothId } from '../../features/booth/ui/ManagementScreen';
import '../../features/booth/ui/boothManagement.css';

export function AiAgentManagementPage() {
  const boothId = useManagementBoothId();

  return (
    <ManagementScreen title="AI 직원 관리" subtitle="대화 설정·답변 근거 문서 관리">
      <AiAgentManagementTab boothId={boothId} />
    </ManagementScreen>
  );
}
