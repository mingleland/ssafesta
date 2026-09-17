// 이벤트 상점 화면 모델 (S15P21A604-842, S15P21A604-836 후속).
//
// 즉시교환은 entities/eventShop(entities/eventShop/api.select) 을 직접 쓴다 — 서버가 진짜 목록을 준다.
// 응모권은 대응 BE 가 아직 없다(-836 스펙에 추첨 개념 자체가 없음, 담당자 확인 결과 추후 추가 예정).
// 그래서 실제로 없는 API를 있는 척 흉내내지 않는다 — 정적 안내용 항목만 두고 버튼을 비활성으로 둔다.
import mygummyUrl from '../../../assets/festa/eventShop/mygummy.png';
import pringlesUrl from '../../../assets/festa/eventShop/pringles.png';
import coffeeUrl from '../../../assets/festa/eventShop/coffee.png';
import mallangiUrl from '../../../assets/festa/eventShop/mallangi.png';
import kyoboUrl from '../../../assets/festa/eventShop/kyobo.png';
import chickenUrl from '../../../assets/festa/eventShop/chicken.png';

// 서버 응답엔 이미지가 없다(EventPrize·RafflePrize 둘 다 imageUrl 필드가 없음) — 상품명으로 매핑한다.
// 매핑에 없는 이름은 undefined를 돌려주고, 화면은 기본 아이콘으로 대신한다.
const PRIZE_IMAGES: Record<string, string> = {
  마이구미: mygummyUrl,
  프링글스: pringlesUrl,
  커피: coffeeUrl,
};

const RAFFLE_IMAGES: Record<string, string> = {
  말랑이: mallangiUrl,
  '교보 기프트카드': kyoboUrl,
  치킨: chickenUrl,
};

export function imageForPrize(name: string): string | undefined {
  return PRIZE_IMAGES[name];
}

export function imageForRaffle(name: string): string | undefined {
  return RAFFLE_IMAGES[name];
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
export function drawTimeLabel(drawAt: string | null): string {
  if (drawAt === null) return '추첨 일정은 추후 공지됩니다';
  const date = new Date(drawAt);
  const formatted = new Intl.DateTimeFormat('ko-KR', {
    month: 'long',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(date);
  return `추첨 ${formatted}`;
}
