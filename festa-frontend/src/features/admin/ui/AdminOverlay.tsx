// 관리자 콘솔 오버레이 — ESC 메뉴의 자식 화면이다 (S15P21A604-828).
//
// 별도 페이지로 두지 않는다. 이 서비스는 월드가 상주 화면이고 나머지는 그 위에 잠깐 뜨는 것이라,
// 콘솔만 웹 페이지로 빠지면 운영자가 월드를 떠났다 돌아와야 한다 — 그때마다 Unity 로딩을 다시 겪는다.
//
// **권한은 여기서도 한 번 더 본다.** 메뉴 항목이 관리자에게만 보이지만, 그 판정과 이 화면 사이에
// 강등이 끼어들 수 있다. 최종 판정은 어차피 매 요청 서버가 한다 — 여기 확인은 빈 콘솔을 띄우지
// 않기 위한 것이다.
import { OverlayFrame, OverlayError, OverlayLoading } from '../../overlay/ui/OverlayFrame';
import { useAdminCapability } from '../model/capability';
import { resetAdminConsole } from '../model/consoleState';
import { AdminConsole } from './AdminConsole';

export function AdminOverlay({ onClose }: { onClose: () => void }) {
  const capability = useAdminCapability();
  // 닫으면 비운다 — 다시 열었을 때 옛 섹션·선택이 남아 있으면 그것이 방금 고른 것으로 읽힌다.
  // 여는 쪽이 아니라 닫는 쪽에서 비우는 이유는, 여는 경로가 둘(메뉴 항목·내 정보 카드)이기 때문이다.
  const close = () => {
    resetAdminConsole();
    onClose();
  };
  return (
    <OverlayFrame title="관리자 콘솔" subtitle="회원 · 권한 · 코인 · 경품 · 공식 설문 · 부스" size="xl" onClose={close}>
      {capability.isPending && <OverlayLoading label="관리자 권한을 확인하는 중..." />}
      {capability.isError && (
        <OverlayError title="관리자 권한을 확인하지 못했습니다" onRetry={() => void capability.refetch()} />
      )}
      {capability.isSuccess && !capability.data.admin && (
        <OverlayError title="관리자만 이용할 수 있습니다" message="권한이 회수되었을 수 있습니다." />
      )}
      {capability.isSuccess && capability.data.admin && <AdminConsole />}
    </OverlayFrame>
  );
}
