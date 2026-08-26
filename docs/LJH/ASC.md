# ASC

ASC(Agent Session Control)는 프로젝트 독립적인 별도 Repository로 분리되었다 (2026-08-22).

Canonical:
- 운영모델 (v5.1 동결): `<ASC Repository>/docs/design/operating-model.md`
- C-01 Approval Port 구현 계약: `<ASC Repository>/docs/contracts/C-01_approval-port.md`

로컬 경로: `projects/asc/` (SSAFESTA와 sibling repository).

SSAFESTA는 ASC의 첫 Project Profile / attach 대상이다.
SSAFESTA Profile 정본은 `<ASC Repository>/profiles/ssafesta/`에 둔다.
ASC Core 및 구현 계약의 정본은 SSAFESTA Repository에 두지 않는다.
attach 시 SSAFESTA에 생기는 것은 Runtime State `.asc/`(Git 추적 제외)뿐이다.
