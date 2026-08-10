# SSAFY FESTA — 착수 전 기술 결정 (ADR)

> **문서 목적**: spec-kit 기반 SDD 착수 전에 **반드시 확정해야 할 6개 항목**의 선택지·트레이드오프·권장안을 정리한다.
> **왜 이 6개만인가**: 이 결정들은 여러 기능의 계약(API/DB/네트워크)에 동시에 박히므로, 기능별 `spec.md`를 쓰기 시작한 뒤에 바꾸면 이미 쓴 spec을 전부 다시 써야 한다.
> 나머지 미정 항목(회원 탈퇴 보존 기간, Booth 임대 연장, 수수료율, Coin 환불, 재접속 상세, 이벤트 주기)은 기능 단위에 갇히므로 `/speckit.clarify` 단계에서 처리한다.
> **상태**: 팀 회의 결정 대기
> **근거 문서**: 01, 03, 07, 12, 13, 14, 15, 16

---

## 결정 요약표

| # | 항목 | 권장안 | 되돌리기 비용 | 결정 시한 |
|---:|---|---|---|---|
| 1 | Unity Web ↔ Server Transport | **WebSocket(wss) + ALB TLS termination** | 매우 높음 | Week 1 착수 전 |
| 2 | Embedding Model | **차원 고정 후 확정 (예: 1536d)** | 매우 높음 | Week 1 |
| 3 | LLM Provider | **Adapter 뒤로 감추고 저가 모델로 시작** | 낮음 | Week 2까지 유예 가능 |
| 4 | AI Streaming (SSE vs WS) | **SSE 유지 + AI UI를 React 오버레이로** | 높음 | Week 1 |
| 5 | Refresh Token 방식 | **httpOnly Cookie + Unity엔 1회용 connection token** | 중간 | Week 2 |
| 6 | EC2 vs ECS / 자동 Channeling | **EC2 + Docker Compose로 시작, 계약만 확장형** | 낮음 (Docker화가 진짜 결정) | Week 1 |

> **핵심 통찰**: 6개 중 진짜로 되돌릴 수 없는 건 **1번(Transport)과 2번(Embedding 차원)** 둘뿐이다. 나머지는 추상화 계층을 제대로 두면 나중에 바꿔도 싸다. 회의 시간의 절반을 1·2번에 쓰는 것이 맞다.

---

## 결정 1. Unity Web ↔ Dedicated Server Transport

### 지금 상태
- 12번 문서: "Transport 세부 방식은 Web Client POC 결과에 따라 확정한다"
- 15번 문서 25절: "ALB를 Unity Server에도 사용할지 여부" 미정
- 12번 25절 Week 1 POC 조건에 **ws / wss 구분이 없다** ← 이게 가장 위험하다

### 선택지가 사실상 없다는 점부터
브라우저(Unity Web 빌드)는 **UDP 소켓을 열 수 없다.** Unity Transport는 Web 빌드에서 자동으로 WebSocket으로 전환된다. 즉 "UDP냐 WebSocket이냐"는 선택지가 아니라 **WebSocket 확정**이다. 실제 결정은 그다음 두 가지다.

**(A) TLS를 어디서 끊을 것인가**

| 방식 | 내용 | 평가 |
|---|---|---|
| **ALB에서 TLS termination** | 브라우저 →`wss`→ ALB →`ws`→ Unity Server | **권장.** ACM 인증서 무료·자동갱신, ALB가 WebSocket Upgrade 네이티브 지원, Unity 컨테이너에 인증서 파일 불필요 |
| Unity Server가 직접 wss | `WithSecureServerParameters(cert, key)` 사용 | 정식 CA 인증서 필수(**자체서명은 브라우저가 거부**). 컨테이너에 pem 주입 + 갱신 관리 부담 |
| NLB (TCP passthrough) | L4 통과 | 결국 Unity Server가 인증서를 들어야 함. ECS 동적 포트와 궁합도 나쁨 |
| Unity Relay | Unity 관리형 중계 | 인증서 문제 회피 가능하나 Dedicated Server 자체 운영이라는 프로젝트 기술 포인트(01번 14절)와 어긋남 |

**(B) 그래서 반드시 같이 확정할 것**
- **ALB idle timeout 상향**: 기본값 60초. NGO 연결이 60초 무트래픽 시 끊긴다. → 180초 이상 + NGO keepalive 확인
- 도메인 분리: `world.festa.example.com` → Unity Server Target Group (채널 확장 시 `w01/w02` host 기반)
- Connection Approval에 넣을 token 형식 → **결정 5와 함께 확정**

### 권장 결론
> **Unity Transport WebSocket 사용. 브라우저는 `wss://world.festa.example.com`으로 접속하고, ALB(ACM 인증서)에서 TLS를 끊어 백엔드 Unity Server에는 평문 ws로 전달한다. ALB idle timeout은 180초 이상으로 설정한다.**

### 이 결정이 만드는 후속 작업 (Week 1 POC 조건에 추가할 것)
12번 25절 POC 체크리스트에 다음을 **반드시 추가**한다.

- [ ] **HTTPS로 서빙된 페이지**에서 `wss://`로 연결 성공 (← 로컬 `http` + `ws`로만 검증하면 배포 시 mixed content로 전부 막힌다)
- [ ] ALB Target Group health check 통과
- [ ] 60초 이상 무입력 상태에서 연결 유지 확인

**이 함정이 이 프로젝트 최대 리스크다.** 로컬에서 잘 되던 게 배포하는 순간 브라우저가 `ws://`를 차단해서 전부 멈추는 시나리오는 매우 흔하다. Week 1에 로컬이 아니라 **AWS + HTTPS 환경에서** POC를 끝내는 것을 강력히 권한다.

**영향 문서**: 12, 15, 16, 07

---

## 결정 2. Embedding Model — *LLM보다 이게 먼저다*

### 지금 상태
13번 문서 28절에 "LLM Provider"와 "Embedding Model"이 나란히 미정으로 있다. 그런데 **이 둘의 되돌리기 비용은 자릿수가 다르다.**

| | 나중에 바꾸면 | 비용 |
|---|---|---|
| LLM Provider | `LLMClient` 어댑터 구현체 교체 (13번 17절에 이미 설계됨) | **낮음.** 코드 몇 백 줄 |
| Embedding Model | 벡터 차원이 바뀜 → `vector(N)` 컬럼 마이그레이션 → **등록된 모든 문서 전량 재임베딩** | **매우 높음.** 시연 직전이면 치명적 |

즉 회의에서 "무슨 LLM 쓸까"로 시간을 쓰는 건 우선순위가 틀렸다. **차원을 못 박는 것이 핵심이다.**

### 선택지

| 방식 | 차원 | 장점 | 단점 |
|---|---:|---|---|
| **관리형 Embedding API** (OpenAI text-embedding-3-small 급) | 1536 | 인프라 0, 한국어 무난, 매우 저렴, 즉시 시작 | 외부 의존, 키 발급·결제 필요 |
| 자체 호스팅 다국어 모델 (bge-m3, multilingual-e5 등) | 1024 | 비용 예측 가능, 외부 의존 없음 | **GPU 인스턴스 필요** — 15번 인프라에 GPU가 없다. CPU 추론은 문서 처리 시간 폭증 |
| 국내 Provider (Solar 등) | 모델별 상이 | 한국어 특화, 국내 결제 | 생태계·문서 상대적으로 얕음 |

### 권장 결론
> **관리형 Embedding API로 확정하고, `vector(1536)`을 스키마에 못 박는다.** 15번 인프라 설계에 GPU가 없고 8주 일정에서 GPU 인스턴스를 추가하는 건 비용·시간 모두 손해다.
> 09번 DB 설계의 chunk 테이블에 **`embedding_model_id` 컬럼을 반드시 포함**한다 (13번 18절이 이미 권고). 나중에 모델을 바꾸더라도 어떤 청크가 어떤 모델로 만들어졌는지 추적할 수 있어야 부분 재임베딩이 가능하다.

**결정할 것 (회의에서 채워넣기)**
- [ ] Embedding Provider / 모델명: __________
- [ ] Vector 차원: __________ (스키마 확정값)
- [ ] API Key 발급 주체와 결제 수단: __________ ← 의외로 이게 병목이 된다. Week 1에 발급 완료할 것
- [ ] pgvector 위치: **같은 RDS 인스턴스 + 별도 스키마 권장** (07번 14절·15번 25절 미정 항목 동시 해소. 비용 때문에 별도 인스턴스는 비추)

**영향 문서**: 09, 13, 14, 15

---

## 결정 3. LLM Provider

### 권장 결론
> **지금 확정하지 않아도 되는 유일한 "확정 필요" 항목이다.** 13번 17절의 `LLMClient` 어댑터(`generate` / `stream` / `healthCheck`)를 **Week 2에 반드시 먼저 구현**하고, 그 뒤로 저가·저지연 모델(gpt-4o-mini 급)로 시작한다.

### 다만 지금 못 박아야 할 것
어댑터가 있어도 **Provider를 바꿀 때 깨지는 지점**이 두 군데 있다. 이건 미리 정한다.

1. **Streaming 이벤트 스키마** — 16번 6절에 이미 `start / token / source / done / error`로 정의돼 있다. **Provider가 뭐든 이 스키마로 정규화**한다. Provider raw 응답을 프론트로 그대로 흘리지 않는다.
2. **Timeout / 재시도 정책** — 21절 "LLM Timeout" 처리가 있으나 수치가 없다. TTFT 기준 타임아웃을 정해야 SSE 클라이언트 UX가 결정된다. **권장: TTFT 15초, 전체 60초, 자동 재시도 없음(중복 답변 위험, 16번 7절과 일관)**

**결정할 것**
- [ ] 1차 LLM 모델: __________
- [ ] TTFT timeout: ______초 / 전체 timeout: ______초
- [ ] AI 실패 시 Coin 환불 → **`/speckit.clarify`로 유예 가능** (단 Ledger에 `REFUND` 타입 자리는 09번 스키마에 미리 만들어 둘 것)

**영향 문서**: 13, 14, 16

---

## 결정 4. AI Streaming — SSE vs WebSocket

### 지금 상태
- 16번 6절: `POST /ai/v1/conversations/{id}/stream` + `Accept: text/event-stream`
- 16번 7절: "브라우저/Unity Web 환경에서 POST SSE가 불편하면 WebSocket 대안을 검토"

### 문제의 진짜 정체
이 항목은 겉보기엔 "프로토콜 선택"이지만, 실제로는 **"AI 상담 UI를 Unity 안에 그릴 것인가, React 오버레이로 띄울 것인가"** 라는 UI 아키텍처 결정이다. 그게 정해지면 프로토콜은 자동으로 따라온다.

| | AI 채팅 UI를 Unity 내부에 구현 | AI 채팅 UI를 React 오버레이로 |
|---|---|---|
| SSE 수신 | Unity Web에서 스트리밍 수신이 까다로움 → jslib 플러그인으로 브라우저 `fetch` 스트림을 받아 `SendMessage`로 넘기는 우회 필요 | `fetch` + `ReadableStream`으로 표준적으로 처리 |
| **한글 입력(IME)** | **Unity Web 빌드의 한글 IME 입력은 오래된 난제.** 조합 중 글자 깨짐·중복 입력 등 | 브라우저 네이티브 input. 문제 없음 |
| 긴 텍스트 렌더링/스크롤 | TMP로 직접 구현 | DOM 기본 제공 |
| 사람 상담 WebSocket 재사용 | Unity에서 WS를 또 구현 | 같은 React 레이어에서 재사용 |
| 몰입감 | 높음 | 오버레이라 다소 떨어짐 |

> **한국어 서비스에서 Unity Web 내부 텍스트 입력창은 실패 확률이 높다.** AI 상담과 사람 상담 모두 한글 타이핑이 핵심 동작인데, 이걸 Unity WebGL InputField로 받으면 Week 5쯤 "글자가 깨져요"로 일정이 무너진다.

### 권장 결론
> **SSE 유지.** 단 조건을 붙인다 — **AI 상담·사람 상담·Survey 입력 UI는 Unity가 아니라 React 오버레이로 구현하고, Unity는 "AI NPC 상호작용 → React에 이벤트 전달"까지만 담당한다.** (07번 3.2절의 "AI NPC / Survey / Consultation UI 진입"을 "진입 트리거"로 해석)
> 10번 문서에 이미 **Unity Bridge**가 있으므로 아키텍처 변경이 아니라 **역할 경계의 명확화**다.

### WebSocket 단일화를 택하지 않는 이유
프로토콜을 WS 하나로 통일하면 깔끔해 보이지만, AI(FastAPI)와 상담(Spring)이 각각 WS 엔드포인트를 갖거나 Spring이 AI를 중계해야 한다. 후자는 **"AI 장애가 서비스 전체로 전파되지 않는다"(07번 11절 / 13번 1절 6항)는 핵심 원칙을 정면으로 깬다.** SSE는 Conversation 단위 단발 요청이라 이 격리가 자연스럽다.

### 같이 확정할 것
- [ ] **ALB idle timeout / buffering** — 15번 4절에 "실제 검증"으로 남아있다. SSE가 ALB에서 버퍼링되면 스트리밍이 아니라 한 번에 도착한다. **Week 1 인프라 POC 항목에 추가**
- [ ] SSE 연결 중 keepalive 주석(`: ping`) 전송 주기
- [ ] Conversation 저장 위치 (07번 5절 미정) → **권장: 서비스 PostgreSQL에 저장, 보존 기간은 프로젝트 종료 시 일괄 삭제**로 단순화

**영향 문서**: 07, 10, 11, 13, 14, 15, 16

---

## 결정 5. Refresh Token 방식

### 지금 상태
- 15번 5절: 환경변수에 `JWT secret/reference`
- 13번 28절: "FastAPI JWT 검증 방식" 미정
- 12번 7절: NGO Connection Approval용 "short-lived connection token"
- 16번 14절: WebSocket 인증 방식 미정

즉 **토큰이 4곳(React REST / Unity NGO / FastAPI / WebSocket)에서 필요**한데 각각 따로 미정으로 남아있다. 하나로 묶어서 결정해야 한다.

### 선택지

| 방식 | 장점 | 단점 |
|---|---|---|
| **httpOnly + Secure Cookie에 Refresh Token** | XSS로 탈취 불가. 표준적 | CloudFront 도메인 ↔ API 도메인이 달라 `SameSite=None; Secure` + 상위 도메인(`.festa.example.com`) 설정 필요. CORS `credentials` 설정 필수 |
| localStorage에 Refresh Token | 구현 가장 쉬움 | XSS 한 방에 전부 털림. 발표 때 지적당하기 좋음 |
| Access Token만 (Refresh 없음) | 가장 단순 | 만료마다 재로그인 → 시연 중 끊기면 최악 |

### 권장 결론
> **4계층 분리안**
>
> 1. **Access Token (JWT, 30분)** — React는 메모리 보관, `Authorization: Bearer`로 전송
> 2. **Refresh Token (7일)** — `httpOnly; Secure; SameSite=None`, Domain `.festa.example.com` 쿠키. 회전(rotation) 없음 — 8주 프로젝트에 과설계
> 3. **Unity NGO Connection Token** — Spring이 `POST /api/v1/world-sessions` 응답으로 주는 **TTL 60~120초 1회용 토큰**. **Refresh Token은 Unity에 절대 전달하지 않는다.** Unity 서버는 이 토큰만 Connection Approval에서 검증 (12번 7절 완성)
> 4. **WebSocket 인증** — Handshake 시 Access Token. **쿼리스트링 금지** (16번 14절이 이미 경고)
>
> **FastAPI 검증**: 자체 발급하지 않고 **Spring이 서명한 Access Token을 공유 시크릿(HS256)으로 검증만** 한다. 시크릿은 Secrets Manager. RS256 공개키 방식이 더 안전하지만 8주 규모에선 HS256이 충분하고 단순하다.

### 왜 이게 지금 결정되어야 하나
Access Token TTL과 Connection Token TTL이 **12번 20절의 오류 코드(`INVALID_TOKEN`, `SESSION_EXPIRED`)와 클라이언트 재시도 UX를 결정**한다. 인증은 거의 모든 기능 spec의 전제라, 나중에 바꾸면 spec 대부분에 손이 간다.

**영향 문서**: 03, 07, 08, 12, 14, 15, 16

---

## 결정 6. EC2 vs ECS + 자동 Channeling 시점

### 이 항목의 진짜 결정은 따로 있다
"EC2냐 ECS냐"는 사실 **되돌릴 수 있는 결정**이다. 되돌릴 수 없는 건 그 앞단이다.

> **되돌릴 수 없는 결정: 모든 서버 컴포넌트를 Docker 이미지로 빌드하는가.**
> Docker 이미지가 있으면 EC2 → ECS 이관은 Task Definition 작성일 뿐 **애플리케이션 코드 변경이 0**이다. 반대로 EC2에 직접 jar/python을 올려놓고 시작하면 이관이 재작업이 된다.

### 권장 결론
> **(a) Day 1부터 Spring / FastAPI / Unity Server 전부 Docker 이미지로 빌드하고 ECR에 푸시한다. 이건 타협 없음.**
> **(b) 오케스트레이션은 EC2 + Docker Compose 단일 인스턴스로 시작한다.** (15번 21절이 이미 허용)
> **(c) Unity Dedicated Server만 Week 6 전에 ECS Task로 이관한다.** — 01번 11절이 "Week 6 이후 아키텍처 변경 금지"라 했으므로 이게 마지노선이다.
> **(d) ALB + ACM + Route53만은 Week 1에 세팅한다.** 결정 1(wss)이 이것 없이는 검증 자체가 불가능하다.

**근거**: 01번 13절에서 최상위 리스크가 "Unity Web + Dedicated Server 연결 불확실성"이다. Week 1~2를 ECS 학습에 태우면 최상위 리스크 검증이 밀린다. 반대로 ECS를 아예 안 하면 01번 14절의 기술 포인트 4번("Horizontally Scalable World")을 발표에서 주장할 수 없다. 그래서 **Unity Server만 ECS**가 절충점이다.

### 자동 Channeling / Booth Instance — 범위 결정

> **구현하지 않는다(P2 유지). 단 계약은 지금 확정한다.**

- MVP에서도 `POST /api/v1/world-sessions`를 **반드시 존재시키고**, 항상 `11F-01` 고정 응답을 준다
- **Unity 클라이언트가 서버 endpoint를 하드코딩하지 않는다.** 항상 이 API 응답을 쓴다
- 이렇게 하면 나중에 채널을 늘리는 게 **서버 측 로직 변경만**으로 끝난다 (12번 5절 의도와 동일)
- **Booth Instance는 spec을 쓰지 않는다.** 12번 14절대로 P0/P1 안정 후

발표 메시지: "확장 가능한 Session 계약을 설계하고 단일 채널로 검증했다"는 정직하면서도 강한 문장이다. 15번 21절의 "구현하지 않은 AWS 서비스를 사용했다고 발표하지 않는다" 원칙과도 일관된다.

**영향 문서**: 07, 12, 15, 16

---

## 회의 진행 제안 (90분)

| 시간 | 항목 | 필요 참석자 |
|---|---|---|
| 0–25분 | **결정 1 (Transport / wss)** | Unity, Infra 필수 |
| 25–45분 | **결정 2 (Embedding 차원) + 결정 3 (LLM)** | AI, Backend |
| 45–65분 | **결정 4 (SSE + AI UI 위치)** | Frontend, Unity, AI |
| 65–80분 | **결정 5 (Token 4계층)** | Backend, Frontend, Unity |
| 80–90분 | **결정 6 (Docker/EC2/Channel 범위)** | Infra, 팀장 |

**회의 전 준비**: Embedding/LLM API Key 발급 가능 여부와 결제 수단을 미리 확인할 것. 여기서 막히면 결정 2·3이 공중에 뜬다.

---

## 회의 후 즉시 할 일

1. 이 문서의 빈칸을 채워 확정본으로 전환하고, 각 원본 문서(07·12·13·15·16)의 "확정 필요 사항" 절에 **"→ ADR 21번에서 확정"** 링크를 단다
2. `.specify/memory/constitution.md`에 다음을 원칙으로 박는다
   - Unity Web 연결은 wss만 사용한다 (ws 금지)
   - 벡터 차원은 `____`로 고정하며 변경 시 전량 재임베딩을 수반한다
   - 텍스트 입력 UI는 Unity가 아닌 React 레이어에서 처리한다
   - Refresh Token은 Unity/게임 서버에 전달하지 않는다
   - 모든 서버 컴포넌트는 Docker 이미지로 빌드한다
   - AI 서버 장애가 World 접속을 차단하지 않는다
   - Coin·Lease·Reward는 Realtime 이벤트가 아닌 REST/DB 트랜잭션으로만 변경한다
3. 12번 25절 Week 1 POC 체크리스트에 **"HTTPS 페이지에서 wss 연결 성공"**, **"ALB SSE 버퍼링 없음 확인"** 두 줄을 추가한다
4. 02번 기능 명세서를 기준으로 `specs/001-…` 분할안을 작성하고 `/speckit.specify`를 시작한다
