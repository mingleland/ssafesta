// ESC → 내 정보 — 페이지 이동 대신 오버레이로 띄운다 (S15P21A604-798). 본문은 ProfilePage와
// MyInfoBody를 공유한다.
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { MyInfoBody } from './MyInfoBody';

interface Props {
  onClose: () => void;
}

export function MyInfoOverlay({ onClose }: Props) {
  return (
    <OverlayFrame title="내 정보" subtitle="축제에서 쓰는 내 프로필과 보유 자산" onClose={onClose}>
      <MyInfoBody />
    </OverlayFrame>
  );
}
