# CI/CD 과거와 현재 아키텍처 비교 — JSW

> 갱신: 2026-09-21 · Jira: `S15P21A604-939`
>
> 이 문서는 팀이 흐름을 빠르게 이해하기 위한 비교 안내서다. 현재 운영 정본과 완료 근거는
> `docs/infra/canonical/00`, `02`, `03`, `04`, `05`, `06`, `07`, `08`을 우선한다.

---

## 1. 한눈에 보는 전환

| 구분 | 과거 | 현재 (Frozen) |
|---|---|---|
| MR 검증 | 브랜치·누적 diff에 따라 필요 이상으로 전 파트 실행·대기 | GitLab MR pre-merge validation. 변경 유형을 validation/build/deploy 축으로 나누고 docs·CI-only는 필요한 검증만 실행 |
| develop 이후 | CI 성공과 Demo 배포 책임이 섞임 | Jenkins가 develop 실행을 받아 Demo batch를 빌드·배포. GitLab은 merge 전 gate만 담당 |
| Unity 릴리스 | Unity Editor CI 빌드 또는 수동 WebGL 파일 복사에 의존 | Unity 담당자 PC가 4-file Release Bundle 생성 → GitLab Generic Package Registry → Jenkins Consumer 검증·배포 |
| Unity MR 검증 | GitLab Runner가 Jenkins 결과를 오래 폴링 | GitLab은 비동기 dispatch 후 즉시 반환, Jenkins Unity agent가 EditMode 검사 뒤 commit status 콜백 |
| Production | Demo 설정을 바꾼 수동 컨테이너·도메인 전환 시도 | Demo known-good exact artifact를 Jenkins Production Promotion으로만 승격. `develop → main`은 non-squash |
| 배포 판단 | 컨테이너가 떠 있거나 가장 최근 이미지면 성공으로 판단 | receipt·source commit·content ID·CURRENT·KNOWN-GOOD·사람 승인을 함께 확인 |

## 2. 현재 전체 흐름

```text
feature / fix / part branch
→ GitLab MR pipeline (pre-merge validation)
→ develop
→ Jenkins develop execution
→ Demo batch deploy / runtime verification
→ Demo CURRENT → human verification → KNOWN-GOOD
→ develop → main (NON-SQUASH)
→ Jenkins manual Production Promotion
→ Production CURRENT → human verification → KNOWN-GOOD
```

`CURRENT`는 공개 중인 조합이고, `KNOWN-GOOD`는 사람이 전체 여정을 확인해 승인한 조합이다.
둘을 동시에 갱신하거나 같은 뜻으로 취급하지 않는다.

## 3. Unity는 두 경로로 분리됐다

| 목적 | 주체와 입력 | 결과 |
|---|---|---|
| 릴리스 배포 | Unity 담당자 licensed workstation이 `festa-webgl-release-<8sha>.zip`, `festa-game-<8sha>.tar`, `webgl-manifest.json`, `image-metadata.json`을 Registry `unity-release-bundle/<8sha>`에 업로드 | Jenkins Consumer가 checksum·metadata·lineage를 검증하고 Demo candidate에 배포 |
| MR 검증 | GitLab `unity-mr-validation-dispatch` → Jenkins `festa-unity-mr-validation` → Unity agent `/opt/unity` | EditMode 결과를 GitLab commit status로 비동기 반환 |

따라서 다음은 하면 안 된다.

- Jenkins에서 Unity/WebGL/Game 릴리스 빌드를 다시 수행
- Registry bundle 없이 EC2 WebGL 파일이나 `current` symlink를 직접 교체
- `558d6624` fixture를 Production provenance로 사용
- WebGL과 World의 commit 문자열만 보고 호환성을 확정

Unity bundle의 Registry 업로드는 **배포 입력을 준비한 것**이다. 호스트 공개 파일을 직접 바꾼 것이 아니며,
Jenkins Consumer의 검증·candidate·promote 절차를 지나야 Demo 릴리스가 된다.

## 4. 검증과 배포의 경계

| 단계 | 책임 | 통과 의미 |
|---|---|---|
| GitLab MR | 코드·설정의 pre-merge validation | develop에 합칠 최소 조건을 만족 |
| Jenkins develop | 선택 빌드, Demo 배포, runtime health/WSS 확인 | Demo `CURRENT`가 됨. 자동으로 known-good이 되지는 않음 |
| Demo human verification | 브라우저 로그인·API·AI·WebGL·WSS 실제 사용자 여정 | 동일 환경 조합을 `KNOWN-GOOD`으로 승인 가능 |
| Jenkins Production Promotion | approved Demo artifact의 receipt·ancestry·identity 검증, candidate와 cutover | Production `CURRENT`가 됨 |
| Production human verification | 공개 경로와 실제 사용자 여정 확인 | Production `KNOWN-GOOD` 승인 |

Demo는 개발 통합·검증 환경이고, Production은 `ssafesta.world`의 별도 런타임이다.
Demo compose/env를 Production에 재사용하거나, `festa-prod-*` legacy 컨테이너를 되살리는 것은 공식 경로가 아니다.

## 5. 운영상 확정된 기준

| 항목 | 현재 기준 |
|---|---|
| Runner | 단일 호스트 Docker Runner, `concurrent=4`, `limit=4`, `request_concurrency=4` |
| 용량 한계 | c=4 최적, c=5는 CPU·메모리·I/O stop condition 충족으로 초과 |
| Demo World / Production World | 각각 `17777` / `27777` |
| 관측 | PSI·Docker stats·Actuator·FastAPI health·WSS 101·GitLab/Jenkins 실행 지표. Grafana 계열은 on-demand |
| 이미지 정리 | 최근 5개만 남기는 방식 금지. CURRENT·KNOWN-GOOD·previous·receipt·state reference 이미지는 보호 |
| 담당 분리 | GitLab = pre-merge validation, Jenkins = execution/deployment, 사람 = human verification/approval |

## 6. 현재 완료 상태와 남은 운영 이벤트

다음은 완료됐으므로 새 증거 없이 다시 설계하거나 TODO로 되살리지 않는다.

- Batch 1 CI boundary/environment isolation
- Batch 2 Unity Consumer E2E
- Batch 3 secret scan·운영 데이터·hygiene
- GitLab 이슈 #220 CI/CD restructuring

공식 carry-forward는 네 건뿐이다.

1. 실제 GitLab provenance가 있는 Unity bundle 입고 시 canonical gate 1회 확인
2. 다음 Production 승격 때 AI/Back stale·dead env 키 제거
3. Production Gate G에서 human gate 대기 중 deploy executor 해제 실측
4. raffle 당첨 인원·시각 결정 뒤 운영 manifest와 provisioning 반영

새 운영 문제는 이 네 건에 억지로 포함하지 않는다. 최신 develop과 읽기 전용 운영 evidence로
재현한 뒤, 별도 Jira 이슈로 분리한다.
