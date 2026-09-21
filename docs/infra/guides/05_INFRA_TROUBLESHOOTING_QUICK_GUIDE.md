# 인프라 Troubleshooting Quick Guide

> 팀원이 먼저 볼 빠른 분기표입니다. 깊은 운영 작업은 canonical/runbook을 따릅니다.

## 1. MR pipeline이 안 생긴다

확인 순서:

```text
MR target branch
↓
.gitlab-ci.yml workflow rules
↓
develop / main / part branch 대상인지
```

현재 branch push 자체는 중복 CI를 피하기 위해 pipeline을 만들지 않는 구조입니다.

---

## 2. MR은 있는데 component CI가 안 돈다

파트 브랜치(`ai`, `back`, `front`, `frontend*`, `game`) 대상 MR에서는 현재 경량 `mr-status`만 동작합니다.

Full component CI는 develop 대상 MR에서 실행됩니다.

---

## 3. MR pipeline이 느리다

정상 Runner:

```text
concurrent = 4
limit = 4
request_concurrency = 4
```

정상 mixed soak 기준 queue는 0~2초 수준이었습니다.

FAST queue가 10초 이상 지속되면 Runner 상태/과도한 동시 build를 확인합니다.

---

## 4. develop에 merge됐는데 Demo가 안 바뀐다

확인:

```text
Jenkins `festa-gitlab-develop`
↓
component detector
↓
build/deploy stage
↓
Demo readiness
```

GitLab CI 성공만으로 Demo가 배포되는 것은 아니며, develop 이후 실행은 Jenkins 책임입니다.

---

## 5. Unity MR만 실패한다

```text
GitLab `unity-mr-validation-dispatch`
↓
Jenkins `festa-unity-mr-validation`
↓
Unity Agent / EditMode Test
↓
`unity-mr-validation` commit status
```

Release Bundle 배포 문제와 MR EditMode 실패는 서로 다른 경로입니다.

---

## 6. Unity Release가 Demo에 안 올라간다

순서:

```text
Release Bundle 4파일
↓
unity-release-bundle/<8sha>
↓
Consumer intake
↓
source / SHA / lineage gate
↓
canonical publish
↓
Demo candidate
↓
readiness / WSS
```

대표 오류:

- 65: identity/lineage/collision/partial registry
- 66: bundle 4파일 불완전
- 75: prefab set 불일치 skip

---

## 7. Demo World 접속이 안 된다

현재 Demo World:

```text
TCP 17777
Public WSS: demo.ssafesta.world root 경로
```

확인할 것:

```text
Game container healthy
17777 listen
WSS 101
```

Production World 27777과 혼동하지 않습니다.

---

## 8. Production에서 문제 발생

Production 컨테이너나 state를 직접 수정하지 않습니다.

먼저:

```text
CURRENT
KNOWN-GOOD
previous
RECEIPT_ID
Production promotion build
```

을 확인합니다.

Production pipeline에는 candidate/public 검증 및 failure rollback 경로가 있습니다.

---

## 9. Host가 갑자기 느려진다

현재 c=4 정상 운영 기준:

```text
Memory PSI = 0
Swap 급증 없음
IO PSI 낮음
```

c=5 실험에서 관측된 위험 신호:

```text
Load 10.82
Memory PSI 11.38 / 9.58
IO PSI 53.72 / 32.25
Swap 증가
```

운영 경보값은 일반 Linux 보편 기준이 아니라 **이 SSAFY FESTA EC2 실측 기준**입니다.

---

## 10. 문제를 넘길 때 최소 정보

```text
MR 번호
commit SHA
component
GitLab job 이름
Jenkins build 번호(해당 시)
오류 stage
대표 로그 5~20줄
사용자 증상
```

이 정보를 같이 전달하면 동일 조사를 반복하지 않아도 됩니다.
