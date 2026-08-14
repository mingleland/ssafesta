# SSAFY FESTA Demo / 발표 시나리오

> **목적**: 서비스 가치를 짧은 시간 안에 보여주면서 핵심 기술 흐름이 실제로 연결되어 있음을 증명한다.  
> 확장 기능을 많이 보여주기보다 **Booth 제작 → Unity 반영 → 방문 → AI → 사람 상담 → Survey → 운영 결과**의 한 흐름을 완주하는 것이 우선이다.

---

## 1. 발표 핵심 메시지

> **사용자가 웹에서 자신의 AI·프로젝트·설문·상담 부스를 직접 만들면 Unity 멀티플레이 SSAFY 공간에 데이터 기반으로 반영되고, 다른 사용자가 이를 실시간으로 방문·체험할 수 있다.**

---

## 2. 시연에서 반드시 증명할 것

1. 실제 다중 사용자 월드
2. Booth Studio가 단순 Mock UI가 아님
3. Publish 데이터가 Unity Prefab으로 생성됨
4. AI가 운영자 문서를 기반으로 답변함
5. AI 상담에서 실제 직원 상담으로 이어짐
6. Survey/수익 등 운영 데이터가 다시 Owner에게 돌아옴

---

## 3. Demo 역할

### 사용자 A — Booth Owner / Staff

준비:

- 로그인 가능
- Booth 임대 또는 시연 직전 임대 가능한 상태
- Coin 충분
- Staff 상담을 받을 수 있음

### 사용자 B — Visitor

준비:

- 로그인 가능
- 별도 브라우저 또는 별도 PC
- World 접속 가능

### Backup

- 시연용 Booth 사전 Published 버전
- AI READY 문서
- 전체 시연 녹화 영상

---

## 4. 사전 준비 Booth

프로젝트 홍보형 Booth가 가장 많은 핵심 기능을 자연스럽게 보여준다.

구성:

```text
Video Screen
+ Project Panel
+ AI NPC
+ Survey Kiosk
+ Consultation Desk
```

AI 문서:

- 프로젝트 기획서 또는 소개 PDF

AI 역할:

- 프로젝트 도슨트

---

## 5. 화면 1 — 서비스 입장

### 화면

Home → Unity World

### 진행

1. A가 Web에 로그인한다.
2. Unity SSAFY FESTA에 입장한다.
3. B도 이미 접속해 있거나 바로 접속한다.
4. 두 아바타가 같은 공간에서 이동하는 모습을 보여준다.

### 전달 메시지

- 설치 없이 Web으로 접속
- 단순 3D 공간이 아니라 실제 Multiplayer World

### 기술 포인트

**Unity Multiplayer World — NGO + Dedicated Server**

---

## 6. 화면 2 — Booth 임대

시간이 짧으면 임대는 사전 완료하고 화면만 설명할 수 있다. 여유가 있으면 실제 수행한다.

### 진행

1. A가 Booth Slot 목록 확인
2. 빈 슬롯 선택
3. `100 Coin / 1일` 확인
4. 임대
5. 잔액과 Booth 상태 갱신

### 전달 메시지

- 한정된 공간을 실제 서비스 자원으로 운영
- Coin Ledger로 거래 추적

---

## 7. 화면 3 — Booth Studio

### 진행

1. A가 Booth Studio 진입
2. `외부 설정`에서 간판·대표색 확인
3. `내부 꾸미기`로 전환
4. AI NPC, Video Screen, Survey Kiosk 추가
5. Object를 이동/회전
6. Properties에서 Agent/Project/Survey 연결

### 반드시 보여줄 것

- Object List
- Editor Canvas
- Properties
- 현재 Draft 상태

### 전달 메시지

> Unity를 직접 개발하지 않아도 사용자가 준비된 기능 오브젝트를 조합해 새로운 부스를 만든다.

### 기술 포인트

**React Booth Studio — 제한형 UGC Editor**

---

## 8. 화면 4 — AI 직원 설정

### 진행

1. AI 이름: `FESTA 프로젝트 도슨트`
2. 역할/말투 확인
3. System Prompt 일부 확인
4. 프로젝트 PDF 등록
5. 문서 상태 `READY` 확인

문서 Processing 자체를 발표 중 기다리지 않는다. 이미 READY 문서를 준비하고, 업로드→처리 구조를 짧게 설명한다.

### 전달 메시지

- 사용자가 AI 서비스를 코드 없이 설정
- Prompt + RAG
- Booth/Agent 단위 데이터 분리

---

## 9. 화면 5 — Publish

### 진행

1. Studio에서 Preview
2. Publish
3. `Published Version N` 확인
4. Unity로 이동

### 실제 기술 흐름

```text
React Booth Studio
→ Layout JSON
→ Spring Boot
→ Published Version
→ Unity LayoutLoader
→ BoothObjectRegistry
→ BoothObjectFactory
→ Prefab Runtime Spawn
```

### 전달 메시지

> 새로운 부스가 생겨도 Unity Scene을 다시 제작하거나 Client를 다시 배포하지 않는다.

### 기술 포인트

**Data-driven Unity Booth Runtime**

---

## 10. 화면 6 — Visitor Booth 방문

### 진행

1. B가 A의 외부 Booth 슬롯으로 이동
2. 문에서 `부스 내부로 이동하시겠습니까?` 확인
3. Server의 활성 임대 검증 후 같은 Scene의 내부 슬롯으로 이동
4. Published Layout으로 생성된 Video Screen과 Project Panel 확인
5. AI NPC 선택

A와 B 화면을 번갈아 보여주며 같은 Booth를 보고 있음을 강조한다.

### 기술 포인트

- 사용자별 서버/Scene을 만들지 않고 물리 임대 슬롯 수만큼 내부 앵커를 재사용한다.
- 정적 부스 오브젝트는 각 Client가 같은 Published Layout으로 로컬 생성한다.
- 일반 방문은 공유형이며, 사람 상담 같은 독점 기능만 별도 예약/세션 대상으로 둔다.
- 임대 만료 시 슬롯 연결과 화면 표시만 초기화하고 기존 소유자의 콘텐츠는 Draft로 보존한다.

---

## 11. 화면 7 — AI RAG 상담

### 질문 예

```text
이 프로젝트의 핵심 기술은 무엇인가요?
```

준비 문서에 답이 명확히 있는 질문을 사용한다.

### 보여줄 것

- Streaming 답변
- 등록 문서를 기반으로 하는 내용
- 가능하면 Source 표시

### 피할 것

- 문서에 없는 애매한 자유 질문
- LLM이 길게 답해 시간이 밀리는 질문
- 네트워크 상태에 따라 수십 초 걸리는 복잡한 질문

---

## 12. 화면 8 — AI → 사람 상담

### 진행

1. B가 `사람 직원에게 상담 요청`
2. A에게 실시간 알림
3. A가 요청 상세 확인
4. AI 대화 Summary 확인
5. A가 Accept
6. B 화면이 상담 연결 상태로 전환
7. 짧은 텍스트 1~2회 교환

### 전달 메시지

> AI가 해결하지 못한 질문을 단절시키지 않고 실제 부스 운영자에게 넘긴다.

### 기술 포인트

- FastAPI Summary
- Spring Staff/Permission
- Redis Presence
- Realtime Text Consultation

---

## 13. 화면 9 — Survey

### 진행

1. B가 Survey Kiosk 선택
2. 짧은 문항 응답
3. Submit
4. 보상형이면 Coin 결과 확인

시간 절약을 위해 1~2문항짜리 시연용 Survey를 사용한다.

---

## 14. 화면 10 — Dashboard

### 진행

A 화면으로 전환.

확인:

- 방문자
- AI 이용
- Survey 응답
- 상담 수
- 서비스 수익

### 마무리 메시지

> 사용자는 공간을 만드는 데서 끝나는 것이 아니라 실제 방문자 반응과 운영 결과를 다시 확인할 수 있다.

---

## 15. 확장 기능 시연

핵심 시연이 안정된 경우 **하나만** 추가한다.

우선순위 후보:

1. World Channel
2. 관리자 Minigame
3. Portfolio Competition
4. Event / QR

완성도가 낮은 확장 기능 여러 개를 짧게 보여주는 것보다 하나를 안정적으로 보여주는 편이 낫다.

---

## 16. 발표 기술 설명 5축

서비스 기능을 모두 나열하지 않고 다음 다섯 개로 묶는다.

### 1. React Booth Studio

사용자가 직접 부스를 제작하는 Web UGC Editor.

### 2. Data-driven Unity Booth Runtime

Layout JSON이 Unity Prefab으로 Runtime 생성.

### 3. Unity Multiplayer World

NGO + Dedicated Server.

### 4. Horizontally Scalable World

현재 MVP는 World Channel + 동일 Scene 내부 슬롯 풀을 사용한다. 규모가 커지면 Layout·Lease 계약을 유지한 채 Additive Scene 또는 Booth Instance로 확장한다.

구현된 범위와 설계 범위를 정확히 구분해 설명한다.

### 5. User-configurable AI Agent

Prompt + RAG + Human Handoff.

---

## 17. 시연 순서 압축 버전

시간이 부족하면:

```text
World에서 A/B Multiplayer 확인
→ 이미 임대한 Booth의 Studio 진입
→ Object 한 개 이동
→ Publish
→ B가 Booth 방문
→ AI 질문
→ Handoff
→ Dashboard
```

Survey는 생략하거나 Dashboard 데이터로만 설명할 수 있다.

---

## 18. 시연 실패 대비

### Unity 연결 실패

1. 한 번 재시도
2. 실패 지속 시 Backup 영상으로 전환
3. 구조 설명은 계속 진행

### AI Provider 지연

- 미리 준비한 짧은 질문 사용
- 한 번 재시도 후 Backup 캡처/영상

### Handoff Realtime 실패

- Dashboard나 사전 녹화로 전환
- 발표 흐름을 멈추고 디버깅하지 않는다.

### Publish 실패

- 사전 Published Booth로 즉시 시연 지속

---

## 19. 발표 직전 Runbook

### 30~60분 전

- Spring Health
- FastAPI Health
- Unity Server
- DB
- Redis
- S3 Document
- AI Document READY

### 10분 전

- A/B Login
- Browser Cache 확인
- Coin 잔액
- Staff AVAILABLE
- Booth Published
- Survey 상태

### 직전

- 발표용 Tab만 열기
- 불필요한 알림 끄기
- Backup Video 바로 접근 가능하게 준비

---

## 20. 성공 판정

Demo 성공은 기능 개수를 많이 보여주는 것이 아니라 다음이 끝까지 이어지는 것으로 판단한다.

```text
사용자가 제작
→ 서버에 저장
→ Unity에 반영
→ 다른 사용자가 방문
→ AI 이용
→ 사람 상담
→ Feedback/운영 데이터 수집
```

이 흐름이 안정적으로 완료되면 SSAFY FESTA의 핵심 가치와 대표 기술을 모두 증명할 수 있다.
