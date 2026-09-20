# SSAFY FESTA Operations Runbook & Invariants

> 상태: CURRENT / FROZEN
> 목적: 운영자가 "왜"를 몰라도 실수로 정본을 깨지 않도록 하는 최소 운영 규칙.

---

## 1. 절대 금지

- `develop → main` squash
- Production에서 app/Unity/WebGL/World rebuild/repack
- CURRENT와 KNOWN-GOOD 동시 갱신
- Unity CI license를 infra 담당자 책임으로 되돌리기
- `558d6624`를 Production canonical provenance로 승격
- AI binding을 OFF로 되돌리기
- Demo readiness가 Production World를 검사하도록 되돌리기
- Jenkins develop status를 GitLab MR의 authoritative merge gate로 사용
- CI/CD async 완료를 Codex가 busy-polling하며 기다리기

---

## 2. Unity 경로 분리 운영 원칙

- **릴리스 배포**: Unity 담당자 워크스테이션에서 번들을 빌드하여 Registry에 업로드하고, Jenkins는 Consumer로만 작동한다. CI 내 Unity Editor 릴리스 빌드 절대 금지.
- **MR 검증**: `festa-unity-mr-validation` 및 Unity 에이전트(`/opt/unity` 마운트)는 Unity 변경 MR의 EditMode 테스트 전용으로만 제한적으로 사용된다.

---

## 3. Observability 운영 신호 및 대응 임계치

### 정상 운영 기준 (c=4)
- CPU PSI some avg10 ≤ 20, full = 0.00
- Memory PSI = 0.00 / 0.00
- Swap 변동 없음
- IO PSI some ≤ 10, full ≤ 5

### 경보 및 대응 신호
1. **Runner queue 증가 / FAST queue 지연 (>10초)**:
   - 러너 컨테이너 상태 점검 (`docker ps | grep runner`)
2. **CPU PSI 지속 상승 (>50) / Load average > 5**:
   - 불필요한 빌드 프로세스 점검 (`top`, `ps aux`)
3. **Memory PSI 발생 (>5.0) / Swap in-out 급증 (>1GB)**:
   - c=5 이상의 과도한 동시 빌드 유입 의심, `concurrent = 4` 확인
4. **IO PSI 급증 (>30)**:
   - 디스크 쓰기 병목 확인 (`iostat -xz 1 2`)
5. **WSS 101 실패 / Demo readiness 실패**:
   - World 컨테이너 포트 17777 리슨 확인 (`docker ps`, `curl`)
6. **Production readiness 실패**:
   - 27777 / 28080~28082 리슨 확인

---

## 4. Docker Image Retention 정본

- 단순 5/component가 아님.
- **보호 대상**: 최근 retention window(5) + CURRENT + KNOWN-GOOD + rollback/previous + release history + state/receipt/candidate references.
- 상태 머신이 참조하는 이미지는 5개를 초과하더라도 정상 보존 대상이므로 추가 강제 prune을 하지 않는다.

---

## 5. Security Threat Model & Inbound Agent

- **Jenkins inbound authentication**: ACTIVE (WebSocket `-webSocket`)
- **Secret transport**: 컨테이너 격리 프로세스 파라미터 (root 외 접근 불가, `no-new-privileges:true` 강제)
- **Status**: `CURRENT_GENERATION_ACCEPTED`

