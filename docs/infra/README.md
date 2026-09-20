# SSAFY FESTA Infra 문서 안내

> 이 디렉터리는 **팀원이 인프라를 쉽게 이해하고 사용하는 안내 계층**입니다.  
> 정책·수치·불변조건이 충돌하면 [`canonical/`](./canonical/) 정본이 우선합니다.

## 무엇을 보면 되나요?

| 내가 하려는 일 | 먼저 볼 문서 |
|---|---|
| 전체 구조와 왜 바뀌었는지 빠르게 이해 | [`guides/00_TEAM_INFRA_SUMMARY_GUIDE.md`](./guides/00_TEAM_INFRA_SUMMARY_GUIDE.md) |
| 인프라 개편 배경과 개선 효과를 조금 더 자세히 이해 | [`guides/01_TEAM_INFRA_OVERVIEW.md`](./guides/01_TEAM_INFRA_OVERVIEW.md) |
| FE / BE / AI 작업을 Demo까지 반영 | [`guides/02_DEVELOPMENT_TO_DEMO_GUIDE.md`](./guides/02_DEVELOPMENT_TO_DEMO_GUIDE.md) |
| Unity 변경 검증 / Release Bundle / Demo 배포 | [`guides/03_UNITY_RELEASE_DEPLOY_GUIDE.md`](./guides/03_UNITY_RELEASE_DEPLOY_GUIDE.md) |
| Demo 검증본을 Production으로 승격 | [`guides/04_PRODUCTION_PROMOTION_GUIDE.md`](./guides/04_PRODUCTION_PROMOTION_GUIDE.md) |
| CI·배포가 이상할 때 빠르게 확인 | [`guides/05_INFRA_TROUBLESHOOTING_QUICK_GUIDE.md`](./guides/05_INFRA_TROUBLESHOOTING_QUICK_GUIDE.md) |
| 정확한 계약·수치·이력·운영 불변조건 확인 | [`canonical/`](./canonical/) |

## 한 줄 구조

```text
일반 개발: MR → GitLab CI → develop → Jenkins → Demo
Unity Release: 담당자 PC Build → Release Bundle → Package Registry → Jenkins Consumer → Demo
Production: Demo 승인 → develop→main NON-SQUASH → 수동 Production Promotion → Human Gate
```

## 현재 핵심 운영값

```text
GitLab Runner:
  concurrent = 4
  limit = 4
  request_concurrency = 4

Unity:
  Release build = 담당자 licensed 환경
  MR validation = Jenkins Unity Agent EditMode

Production:
  exact-artifact promotion
  rebuild / repack = 0
```

정본 진입점: [`canonical/00_SSAFESTA_INFRA_MASTER_INDEX.md`](./canonical/00_SSAFESTA_INFRA_MASTER_INDEX.md)
