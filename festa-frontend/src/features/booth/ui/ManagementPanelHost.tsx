// 관리 상세 패널을 월드 위에 띄우는 자리 (S15P21A604-755).
//
// 예전에는 이 넷이 별도 route 라 관리 화면에서 들어가면 월드가 화면에서 사라졌다. 이제 같은
// 본문을 오버레이로 얹는다 — route 는 그대로 남아 deep-link 가 계속 동작한다.
//
// **Studio 패널은 2026-09-17 에 걷었다.** 사용자 흐름에서 Booth Studio 를 폐기하면서 여는 길이
// 하나도 남지 않았고, 여기 갈래만 남겨 두면 three 를 끌어오는 lazy 경계를 이유 없이 유지하게 된다.
import { ProjectManagementPage } from '../../../pages/management/ProjectManagementPage';
import { SurveyManagementPage } from '../../../pages/management/SurveyManagementPage';
import { ConsultationStaffPage } from '../../../pages/management/ConsultationStaffPage';
import { AiAgentManagementPage } from '../../../pages/management/AiAgentManagementPage';
import { ManagementSurfaceProvider } from './ManagementScreen';
import type { ManagementPanel } from '../../world/model/managementPanel';

function PanelBody({ panel }: { panel: ManagementPanel }) {
  switch (panel.kind) {
    case 'project':
      return <ProjectManagementPage />;
    case 'survey':
      return <SurveyManagementPage />;
    case 'consultation':
      return <ConsultationStaffPage />;
    case 'ai-agent':
      return <AiAgentManagementPage />;
  }
}

export function ManagementPanelHost({ panel, onClose }: { panel: ManagementPanel; onClose: () => void }) {
  return (
    <ManagementSurfaceProvider value={{ panel, onClose }}>
      {/* 넷 다 ManagementScreen 이 OverlayFrame 을 씌운다 */}
      <PanelBody panel={panel} />
    </ManagementSurfaceProvider>
  );
}
