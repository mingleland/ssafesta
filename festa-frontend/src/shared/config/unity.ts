// Unity WebGL Host의 조정 가능한 상수 — 값 변경이 여기 한 파일로 끝나게 한다
// 출처: Issue #31, specs/002-world-session/spec.md FR-013·FR-014, GitLab #128 §3 (S15P21A604-426)

// Unity boot watchdog(ms) — createUnityInstance 시작부터 인스턴스가 설 때까지, 진행률 콜백이 이 시간 동안
// 한 번도 오지 않으면 boot 실패로 본다. 진행률이 올 때마다 다시 잰다(느린 회선에서 239MB 다운로드가 60초를
// 넘겨도 진행 중이면 실패가 아니다). 인스턴스가 서면 해제한다 — 로비 체류·입장 게이트 대기에는 타임아웃이
// 없다. 게이트 쪽 상한은 Unity WorldEntryGate 의 30초 강제 개방(FR-014)이 갖고 있고 개방 시 onWorldGateReady
// 를 보내므로 호스트가 따로 재지 않는다. p95 실측치 없음 — demo 환경 후 조정, 잠정값 그대로 사용.
export const UNITY_BOOT_STALL_TIMEOUT_MS = 60_000;
