// My Info — /app/profile (S15P21A604-406). ESC Game Menu 의 Profile Summary 에서도 진입한다
// (ESC 쪽은 MyInfoOverlay, S15P21A604-798). 본문은 features/profile/ui/MyInfoBody 를 공유한다.
import { MyInfoBody } from '../../features/profile/ui/MyInfoBody';
import { PageShell } from '../../features/shell/ui/PageShell';
import './profilePage.css';

export function ProfilePage() {
  return (
    <PageShell title="내 정보" subtitle="축제에서 쓰는 내 프로필과 보유 자산" backTo="/app/world">
      <MyInfoBody />
    </PageShell>
  );
}
