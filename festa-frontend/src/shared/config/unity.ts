// Unity WebGL Host의 조정 가능한 상수 — 값 변경이 여기 한 파일로 끝나게 한다
// 출처: Issue #31, specs/002-world-session/spec.md FR-013·FR-014

// React 오버레이 독립 타임아웃(ms) — Unity 강제 개방(30초)보다 길어야 한다(#31 합의).
// p95 실측치 없음(배포 환경 미구성) — demo 환경 후 실측해 조정하기로 함, 잠정값 그대로 사용.
export const WORLD_GATE_TIMEOUT_MS = 60_000;
