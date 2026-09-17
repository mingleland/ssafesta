// 이벤트 상점 화면 모델 (S15P21A604-842, S15P21A604-836 후속).
//
// 즉시교환은 entities/eventShop(entities/eventShop/api.select) 을 직접 쓴다 — 서버가 진짜 목록을 준다.
// 응모권은 대응 BE 가 아직 없다(-836 스펙에 추첨 개념 자체가 없음, 담당자 확인 결과 추후 추가 예정) —
// entities/raffle이 같은 모양의 mock 어댑터로 실제 응모 흐름을 흉내낸다. api.select.ts export
// 한 줄만 바꾸면 실 연동으로 전환된다.
import mygummyUrl from '../../../assets/festa/eventShop/mygummy.png';
import chocosongiUrl from '../../../assets/festa/eventShop/chocosongi.png';
import coffeeUrl from '../../../assets/festa/eventShop/coffee.png';
import mallangiUrl from '../../../assets/festa/eventShop/mallangi.png';
import kyoboUrl from '../../../assets/festa/eventShop/kyobo.png';
import chickenUrl from '../../../assets/festa/eventShop/chicken.png';

// 서버 응답엔 이미지가 없다(EventPrize·RafflePrize 둘 다 imageUrl 필드가 없음) — 상품명으로 매핑한다.
// 매핑에 없는 이름은 undefined를 돌려주고, 화면은 기본 아이콘으로 대신한다.
// 말랑이·교보 기프트카드는 응모권에서 즉시교환으로 옮겨졌다(2026-09-17, S15P21A604-842 후속).
const PRIZE_IMAGES: Record<string, string> = {
  마이구미: mygummyUrl,
  초코송이: chocosongiUrl,
  커피: coffeeUrl,
  말랑이: mallangiUrl,
  '교보 기프트카드 10000원권': kyoboUrl,
};

const RAFFLE_IMAGES: Record<string, string> = {
  치킨: chickenUrl,
};

export function imageForPrize(name: string): string | undefined {
  return PRIZE_IMAGES[name];
}

export function imageForRaffle(name: string): string | undefined {
  return RAFFLE_IMAGES[name];
}

// 원본 사진 크기·여백이 제각각이라 같은 96px 박스 안에서도 체감 크기가 다르다 — 상품별로
// 개별 보정한다(S15P21A604-842 후속). 1이면 보정 없음.
const IMAGE_SCALE: Record<string, number> = {
  초코송이: 1.2,
  // 기프트카드는 가로가 긴 사진이라 84% contain 박스에서 세로가 짧게 남아 다른 카드보다
  // 작아 보인다 — 다른 상품·응모권 카드와 체감 크기를 맞추려고 더 크게 키운다.
  '교보 기프트카드 10000원권': 1.7,
};

export function imageScaleFor(name: string): number {
  return IMAGE_SCALE[name] ?? 1;
}

export function stockLabel(stock: number | null): string {
  if (stock === null) return '재고 무제한';
  if (stock === 0) return '품절';
  return `재고 ${stock}개`;
}

// EventPrize·RafflePrize 둘 다 만족하는 구조적 타입 — 도메인 타입을 여기서 import하지 않는다
export function isSoldOut(item: { stock: number | null }): boolean {
  return item.stock !== null && item.stock <= 0;
}

// 추첨 시각을 모르는 채로(null) 응모만 받고 끝나면 "응모했는데 언제 결과가 나오는지" 가 안 남는다.
// 실제 일정은 팀이 아직 안 정했다(docs/26) — null이면 날짜를 지어내지 않고 그렇게 말한다.
// 응모 완료 팝업 — 문장 한 줄이라 공간이 있다.
export function drawTimeLabel(drawAt: string | null): string {
  if (drawAt === null) return '추첨 일정은 추후 공지됩니다';
  return `추첨 ${formatDrawAt(drawAt)}`;
}

// 카드 chip용 — chip은 폭이 좁아 문장이 두 줄로 접히면 보기 흉하다. null이면 "추후 공지"만 짧게.
export function drawTimeChipLabel(drawAt: string | null): string {
  if (drawAt === null) return '추후 공지';
  return formatDrawAt(drawAt);
}

function formatDrawAt(drawAt: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(drawAt));
}
