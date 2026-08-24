// 부스 임대 mock — V5 시드 정합 7슬롯 + ADMIN sentinel 1개. 임대 검증·409 전종·C-02(읽기 시
// 만료 판정)·FR-018(재요청 무차감)을 서버만큼 엄격하게 재현한다. 차감은 wallet mock의
// debitForLease()로 일원화 — 배지 잔액과 balanceAfter가 항상 일치한다.
// 출처: specs/004-booth-slot-lease/contracts/lease-api.md, V5__booth_slot_seed.sql
import type { ApiError } from '../../shared/api/client';
import { debitForLease, getWallet } from '../wallet/api.mock';
import type { LeaseResponse, MyBooth, SlotView } from './types';

const STORAGE_KEY = 'festa-mock-booth-leases';
const LEASE_COIN = 100; // 1일 임대료(C 확정 수치)
const DAY_MS = 24 * 60 * 60 * 1000;

// sentinel — facade mock의 999 관용구 계승. 실 BE에 없는 시나리오 재현용.
const INSUFFICIENT_SLOT_ID = 6; // R06 임대 시도 → 항상 잔액 부족
const SHORT_LEASE_SLOT_ID = 7; // R07 임대 성공하되 90초 만료 — 시간 진행·만료 전환 관찰용
const ADMIN_SLOT_ID = 905; // USER_RENTAL 아님 → BOOTH_SLOT_NOT_RENTABLE
const TAKEN_SLOT_ID = 908; // 타인이 항상 점유 중 → BOOTH_SLOT_ALREADY_LEASED (경합 패배 재현)

interface MockLease {
  leaseId: number;
  slotId: number;
  boothId: number;
  startsAt: string;
  endsAt: string;
  chargedCoin: number;
}

// 시드 — V5 실물(F11-R01~R07 전부 USER_RENTAL) + ADMIN sentinel. 개수는 length로만 소비할 것.
interface MockSlot {
  slotId: number;
  slotCode: string;
  type: string;
}

const SLOT_SEED: MockSlot[] = [
  ...Array.from({ length: 7 }, (_, i) => ({
    slotId: i + 1,
    slotCode: `F11-R0${i + 1}`,
    type: 'USER_RENTAL',
  })),
  { slotId: ADMIN_SLOT_ID, slotCode: 'F11-A01', type: 'ADMIN_EXHIBITION' },
  { slotId: TAKEN_SLOT_ID, slotCode: 'F11-T01', type: 'USER_RENTAL' },
];

function loadLease(): MockLease | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as MockLease) : null;
  } catch {
    return null;
  }
}

function persistLease(): void {
  try {
    if (myLease) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(myLease));
    else sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // sessionStorage 미가용 — 이번 세션만 메모리로 동작
  }
}

// mock 단일 사용자 가정 — 임대 주체는 항상 나(mine 재현). 타인 점유는 sentinel로만 존재.
let myLease: MockLease | null = loadLease();
let nextLeaseId = 1;

// 첫 임대 시 Booth가 발급되면 만료돼도 삭제되지 않는다(FR-010 콘텐츠 보존) — 만료 후
// getMyBooth는 204가 아니라 INACTIVE + lease:null을 반환해야 실서버와 같다(codex 검증 적중분).
const HAD_BOOTH_KEY = 'festa-mock-booth-had';

function loadHadBooth(): boolean {
  try {
    return sessionStorage.getItem(HAD_BOOTH_KEY) === 'true';
  } catch {
    return false;
  }
}

let hadBooth = loadHadBooth() || myLease !== null;

function markHadBooth(): void {
  hadBooth = true;
  try {
    sessionStorage.setItem(HAD_BOOTH_KEY, 'true');
  } catch {
    // 무시
  }
}

function apiError(code: string, message: string): ApiError {
  return { code, message, requestId: `mock_${Date.now()}`, errors: [], warnings: [] };
}

function fieldError(field: string, message: string): ApiError {
  return {
    code: 'VALIDATION_FAILED',
    message: '요청 값이 올바르지 않습니다.',
    requestId: `mock_${Date.now()}`,
    errors: [{ rule: 'FIELD_INVALID', field, message }],
    warnings: [],
  };
}

// C-02 재현 — 만료 판정은 읽기 시점. 스케줄러 없이 조회마다 endsAt을 대조해 되돌린다.
function activeLease(): MockLease | null {
  if (myLease && Date.parse(myLease.endsAt) <= Date.now()) {
    myLease = null;
    persistLease();
  }
  return myLease;
}

export async function getSlots(): Promise<SlotView[]> {
  const lease = activeLease();
  return SLOT_SEED.map((s) => {
    // 타인 점유 sentinel — 항상 OCCUPIED·mine false. 경합 패배(ALREADY_LEASED)와 점유 표시 재현
    if (s.slotId === TAKEN_SLOT_ID) {
      return {
        slotId: s.slotId,
        slotCode: s.slotCode,
        floorNo: 11,
        type: s.type,
        status: 'OCCUPIED' as const,
        boothId: 777,
        boothName: '다른 회원 부스',
        leaseEndsAt: new Date(Date.now() + DAY_MS).toISOString(),
        remainingSeconds: 86_400,
        entryAvailable: true,
        mine: false,
      };
    }
    const occupied = lease !== null && lease.slotId === s.slotId;
    return {
      slotId: s.slotId,
      slotCode: s.slotCode,
      floorNo: 11,
      type: s.type,
      status: occupied ? 'OCCUPIED' : 'AVAILABLE',
      boothId: occupied ? lease.boothId : null,
      boothName: occupied ? '내 부스' : null,
      leaseEndsAt: occupied ? lease.endsAt : null,
      remainingSeconds: occupied
        ? Math.max(0, Math.floor((Date.parse(lease.endsAt) - Date.now()) / 1000))
        : null,
      entryAvailable: occupied,
      mine: occupied, // 단일 사용자 가정 — 점유 = 항상 나
    };
  });
}

export async function leaseSlot(slotId: number, durationDays = 1): Promise<LeaseResponse> {
  if (durationDays !== 1) {
    throw fieldError('durationDays', '임대 기간은 1일만 가능합니다.');
  }
  const slot = SLOT_SEED.find((s) => s.slotId === slotId);
  if (!slot) throw apiError('BOOTH_SLOT_NOT_FOUND', '슬롯을 찾을 수 없습니다.');
  if (slot.type !== 'USER_RENTAL') {
    throw apiError('BOOTH_SLOT_NOT_RENTABLE', '임대할 수 없는 슬롯입니다.');
  }
  if (slotId === TAKEN_SLOT_ID) {
    throw apiError('BOOTH_SLOT_ALREADY_LEASED', '다른 회원이 임대 중인 슬롯입니다.');
  }

  const lease = activeLease();
  if (lease) {
    if (lease.slotId === slotId) {
      // FR-018 — 이미 임차인이면 차감 없이 기존 임대 반환(200 경로). 잔액은 현재값 그대로
      return { ...toResponse(lease), balanceAfter: (await getWallet()).balance };
    }
    throw apiError('ACTIVE_LEASE_LIMIT', '이미 임대 중인 부스가 있습니다. 부스는 1개만 임대할 수 있습니다.');
  }

  if (slotId === INSUFFICIENT_SLOT_ID) {
    // sentinel — 부족액을 message에 일부러 노출: FE가 파싱하지 않음을 실측 검증
    throw apiError('INSUFFICIENT_COIN', `코인이 부족합니다. 필요: ${LEASE_COIN}, 잔액: 40`);
  }

  const balanceAfter = debitForLease(LEASE_COIN); // 부족 시 여기서 INSUFFICIENT_COIN throw
  const now = Date.now();
  const durationMs = slotId === SHORT_LEASE_SLOT_ID ? 90_000 : DAY_MS;
  myLease = {
    leaseId: nextLeaseId++,
    slotId,
    boothId: 1, // 사용자당 부스 1개(V7 UNIQUE) — 재임대에도 같은 boothId 재사용
    startsAt: new Date(now).toISOString(),
    endsAt: new Date(now + durationMs).toISOString(),
    chargedCoin: LEASE_COIN,
  };
  markHadBooth();
  persistLease();
  return { ...toResponse(myLease), balanceAfter };
}

function toResponse(lease: MockLease): LeaseResponse {
  return {
    leaseId: lease.leaseId,
    boothId: lease.boothId,
    slotId: lease.slotId,
    startsAt: lease.startsAt,
    endsAt: lease.endsAt,
    remainingSeconds: Math.max(0, Math.floor((Date.parse(lease.endsAt) - Date.now()) / 1000)),
    chargedCoin: lease.chargedCoin,
    balanceAfter: 0, // 호출부가 현재 잔액으로 덮는다 — 이 함수 단독으로 쓰지 않는다
  };
}

// real의 204 → null 정규화와 같은 시그니처. 부스를 한 번도 못 받았을 때만 null(204) —
// 만료 후에는 INACTIVE + lease:null (FR-010, 실서버 정합)
export async function getMyBooth(): Promise<MyBooth | null> {
  const lease = activeLease();
  if (!lease) {
    if (!hadBooth) return null;
    return { boothId: 1, name: '내 부스', status: 'INACTIVE', lease: null };
  }
  const slot = SLOT_SEED.find((s) => s.slotId === lease.slotId);
  return {
    boothId: lease.boothId,
    name: '내 부스',
    status: 'ACTIVE',
    lease: {
      leaseId: lease.leaseId,
      slotId: lease.slotId,
      slotCode: slot?.slotCode ?? null,
      startsAt: lease.startsAt,
      endsAt: lease.endsAt,
      remainingSeconds: Math.max(0, Math.floor((Date.parse(lease.endsAt) - Date.now()) / 1000)),
      chargedCoin: lease.chargedCoin,
    },
  };
}

export function __resetLeaseMockForTests(): void {
  myLease = null;
  nextLeaseId = 1;
  hadBooth = false;
  try {
    sessionStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(HAD_BOOTH_KEY);
  } catch {
    // 무시
  }
}
