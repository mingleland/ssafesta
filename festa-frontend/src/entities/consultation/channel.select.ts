// Consultation Port 선택 지점 — real 어댑터는 아직 없다(BE -137 미착수, WS·STOMP shape 미확정).
// BE 계약 확정 시 channel.real.ts(STOMP 어댑터)를 추가하고 여기서 VITE_USE_MOCK 3항으로 가른다.
import type { ConsultationChannelPort, ConsultationStaffPort } from './channel.port';
import { consultationStaffMock, consultationVisitorMock } from './channel.mock';

export const consultationChannel: ConsultationChannelPort = consultationVisitorMock;
export const consultationStaff: ConsultationStaffPort = consultationStaffMock;
