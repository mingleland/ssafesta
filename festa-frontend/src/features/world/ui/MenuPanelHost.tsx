// ESC 메뉴가 여는 하위 화면을 월드 위에 띄우는 자리 (S15P21A604-798 후속).
//
// 셋 다 메뉴의 **자식**이다 — 닫으면 월드가 아니라 메뉴로 돌아간다. 그 복귀는 gameClientUi 가
// 메뉴를 켜 둔 채로 두는 것으로 성립하므로 여기서는 프레임만 씌운다.
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { MyInfoOverlay } from '../../profile/ui/MyInfoOverlay';
import { AdminOverlay } from '../../admin/ui/AdminOverlay';
import { DailyMissionOverlay } from '../../mission/ui/DailyMissionOverlay';
import { MusicSettings } from '../../audio/ui/MusicSettings';
import { ControlGuideList } from './ControlGuideList';
import type { MenuPanel } from '../model/gameClientUi';

export function MenuPanelHost({ panel, onClose }: { panel: MenuPanel; onClose: () => void }) {
  if (panel === 'myInfo') return <MyInfoOverlay onClose={onClose} />;
  if (panel === 'admin') return <AdminOverlay onClose={onClose} />;
  if (panel === 'missions') return <DailyMissionOverlay onClose={onClose} />;
  if (panel === 'guide') {
    return (
      <OverlayFrame title="조작 안내" subtitle="월드에서 사용하는 기본 조작" size="s" onClose={onClose}>
        <ControlGuideList />
      </OverlayFrame>
    );
  }
  return (
    <OverlayFrame title="설정" subtitle="소리" size="s" onClose={onClose}>
      <MusicSettings />
    </OverlayFrame>
  );
}
