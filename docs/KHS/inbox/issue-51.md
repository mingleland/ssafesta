# #51 [docs][ai] spec 007 저장소가 S3 확정 상태 — 인프라 R2 전환(#30) 이 아직 반영 안 됨
작성: kanghyunsoon (나) · reason: author · 2026-08-21T09:43:14Z
링크: https://github.com/kanghyunsoon/ssafesta/issues/51

## 무슨 일
Alexjung0115: "1차 R2 / 장애 시 S3-compatible fallback" 으로 읽는 게 맞다고 확인.
ghkim1632(AI): 이견 없음 — spec 007 및 파생 산출물 전부 동기화하고 `s3Key`→`objectKey` 로 중립화하겠다고 회신.
내가 연 이슈이고 두 담당 답변이 모두 모여, 남은 것은 반영 확인 후 종료 여부 판단입니다.

## 내가 답해야 하는 것
- 두 답변으로 이 이슈를 닫아도 되는가, 아니면 문서 반영 커밋을 확인하고 닫는가
- `objectKey/object_key` 중립화 범위 — AI 소관 문서만인지, 공유 문서(`docs/08` 등)도 걸리는지

## 승인 후 확인할 것
- ghkim1632 가 말한 spec 007 FR-010·Document 엔티티·C-07 및 plan/data-model/OpenAPI/quickstart 반영 커밋이 실제로 올라왔는지
- `docs/15`·`INFRA.md` 의 R2 서술과 spec 007 문구가 지금 일치하는지
- `s3Key` 문자열이 남아 있는 문서·코드가 있는지 (전수 grep)
- #30 이 닫힌 시점(08:22) 과 이 답변들(08:55·09:11) 의 선후 — 이전에 시간선을 잘못 읽은 이슈다

## 초안 (뼈대만)
- 두 답변 확인, R2 1차 / S3-compatible fallback 으로 정합 확인.
- `objectKey` 중립화 방향 동의 — 공유 문서 영향 범위만 확인 요청.
- 반영 커밋 확인되면 닫겠다 (미확인 상태로 단정하지 않는다).
