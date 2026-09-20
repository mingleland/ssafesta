// Consultation Port 선택 지점 — 2026-09-18 부터 real 어댑터가 기본이다.
//
// BE ConsultationController(REST 5종)·ConsultationEventPublisher(알림)·WsTokenController 가
// 모두 develop 에 있어서 낡은 "real 어댑터는 아직 없다(BE -137 미착수)" 주석과 mock 고정을
// 걷었다. 갈림은 세 가지다:
//
//   테스트        — mock. 모델 테스트가 시뮬레이터 함수(__simulateVisitorEvent)로 상태 전이를
//                   잠그고 있어 실 어댑터로 돌리면 소켓·서버 없이 못 돈다
//   VITE_USE_MOCK — 목업 백엔드와 짝이 맞는다. 실 BE 에 403·404 가 쌓이는 일을 막는다
//   나머지        — real. REST + shared/realtime 공용 소켓
import type { ConsultationChannelPort, ConsultationStaffPort } from './channel.port';
import { consultationStaffMock, consultationVisitorMock } from './channel.mock';
import { consultationStaffReal, consultationVisitorReal } from './channel.real';

const useMock = import.meta.env.MODE === 'test' || import.meta.env.VITE_USE_MOCK === 'true';

export const consultationChannel: ConsultationChannelPort = useMock
  ? consultationVisitorMock
  : consultationVisitorReal;
export const consultationStaff: ConsultationStaffPort = useMock ? consultationStaffMock : consultationStaffReal;
