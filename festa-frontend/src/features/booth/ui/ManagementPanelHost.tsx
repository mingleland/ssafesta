// 관리 상세 패널을 월드 위에 띄우는 자리 (S15P21A604-755).
//
// 예전에는 이 다섯이 별도 route 라 관리 화면에서 들어가면 월드가 화면에서 사라졌다. 이제 같은
// 본문을 오버레이로 얹는다 — route 는 그대로 남아 deep-link 가 계속 동작한다.
//
// **Studio 는 lazy 로 둔다.** 2.5D 렌더러(three)가 그 아래에 달려 있어 static import 로 두면
// Studio 를 열지 않는 사용자도 월드 진입 번들에서 3D 런타임을 받는다. route 쪽이 같은 이유로
// 이미 lazy 이고(S15P21A604-470), 여기서 정적으로 부르면 그 경계가 무너진다.
import { Suspense, lazy } from 'react';
import { ProjectManagementPage } from '../../../pages/management/ProjectManagementPage';
import { SurveyManagementPage } from '../../../pages/management/SurveyManagementPage';
import { ConsultationStaffPage } from '../../../pages/management/ConsultationStaffPage';
import { AiAgentManagementPage } from '../../../pages/management/AiAgentManagementPage';
import { ManagementSurfaceProvider } from './ManagementScreen';
import { OverlayLoading } from '../../overlay/ui/OverlayFrame';
import type { ManagementPanel } from '../../world/model/managementPanel';

const StudioPage = lazy(async () => {
  const m = await import('../../../pages/studio/StudioPage');
  return { default: m.StudioPage };
});

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
    case 'studio':
      return (
        <Suspense fallback={<OverlayLoading label="스튜디오를 불러오는 중..." />}>
          <StudioPage />
        </Suspense>
      );
  }
}

export function ManagementPanelHost({ panel, onClose }: { panel: ManagementPanel; onClose: () => void }) {
  return (
    <ManagementSurfaceProvider value={{ panel, onClose }}>
      {/* Studio 는 자기 Shell 이 화면 전체를 쓰므로 OverlayFrame 으로 감싸지 않는다 — 감싸면
          캔버스가 프레임 안에 갇혀 편집 면적이 사라진다. 나머지 넷은 ManagementScreen 이
          OverlayFrame 을 씌운다. */}
      {panel.kind === 'studio' ? (
        <div className="bm-panel-full">
          <PanelBody panel={panel} />
        </div>
      ) : (
        <PanelBody panel={panel} />
      )}
    </ManagementSurfaceProvider>
  );
}
