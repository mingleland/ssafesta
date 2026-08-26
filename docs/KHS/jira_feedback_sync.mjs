// 컨설턴트 피드백(2026-08-24) → Jira 반영 실행기
// 사용: node jira_feedback_sync.mjs [--dry]
//
// 하는 일
//  1) 신규 3건 생성 — GitLab #93(FE 템플릿)·#94(BE 계측)·#95(AI 초안 생성)
//     게임 파트 대응분(#89~#92)은 08-24 랩업에서 이미 S15P21A604-235~238 로 등록돼 있어 만들지 않는다.
//  2) 기존 6건 설명에 "컨설턴트 피드백 연계" 절 append — GitLab 이슈·근거 문서 상호참조
//  3) 선후행 링크 — 계측(235)이 후속 최적화(236·237)를 Blocks
//
// 멱등: 이미 연계 절이 있으면 건너뛴다. 같은 제목의 이슈가 있으면 생성하지 않는다.
import { execSync } from "node:child_process";

const SITE = "https://ssafy.atlassian.net";
const EMAIL = "gudtnslwkd@naver.com";
const PROJECT = "S15P21A604";
const SP_FIELD = "customfield_10031";
const EPIC_FIELD = "customfield_10014";
const DRY = process.argv.includes("--dry");

const token = execSync(
  `powershell.exe -NoProfile -Command "[Environment]::GetEnvironmentVariable('ATLASSIAN_API_TOKEN','User')"`,
  { encoding: "utf8" }
).trim();
const AUTH = "Basic " + Buffer.from(`${EMAIL}:${token}`).toString("base64");

async function api(method, path, body, base = "/rest/api/2") {
  if (DRY && method !== "GET") { console.log(`[dry] ${method} ${path}`); return {}; }
  const res = await fetch(SITE + base + path, {
    method,
    headers: { Authorization: AUTH, "Content-Type": "application/json", Accept: "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

const MARK = "h3. 컨설턴트 피드백 연계 (2026-08-25)";

// ── 1. 기존 이슈 상호참조 append ──
const links = [
  { key: "S15P21A604-164", gl: "#89 [게임] WebGL 30~40인 부하 측정 인프라", doc: "09_동시접속_규모와_성능_예산.md" },
  { key: "S15P21A604-206", gl: "#89 [게임] WebGL 30~40인 부하 측정 인프라", doc: "09_동시접속_규모와_성능_예산.md" },
  { key: "S15P21A604-235", gl: "#89 [게임] WebGL 30~40인 부하 측정 인프라", doc: "09_동시접속_규모와_성능_예산.md" },
  { key: "S15P21A604-236", gl: "#90 [게임] 원격 아바타 렌더·애니메이션 비용 절감", doc: "09_동시접속_규모와_성능_예산.md" },
  { key: "S15P21A604-237", gl: "#91 [게임] NetworkTransform 동기화 트래픽 완화", doc: "09_동시접속_규모와_성능_예산.md" },
  { key: "S15P21A604-238", gl: "#92 [공통] Three.js가 아니라 Unity WebGL인 이유", doc: "08_WebGL_렌더링_아키텍처_선택.md" },
];

for (const l of links) {
  const cur = await api("GET", `/issue/${l.key}?fields=description,summary`);
  const desc = cur.fields.description || "";
  if (desc.includes(MARK)) { console.log("건너뜀(이미 연계됨):", l.key); continue; }
  const add =
    `\n\n${MARK}\n` +
    `* GitLab 이슈: ${l.gl}\n` +
    `* 근거 문서: docs/KHS/conference/${l.doc}\n` +
    `* 배경: 컨설턴트 피드백 "WebGL 30~40인 최적화 최우선". 30~40인은 아직 미검증이며, ` +
    `계측(S15P21A604-235)이 후속 최적화보다 선행한다.`;
  await api("PUT", `/issue/${l.key}`, { fields: { description: desc + add } });
  console.log("연계 추가:", l.key, "|", cur.fields.summary.slice(0, 40));
}

// ── 2. 신규 3건 ──
const prios = await api("GET", "/priority");
const prio = Object.fromEntries(prios.map(p => [p.name, p.id]));
const es = await api("GET",
  `/search/jql?jql=${encodeURIComponent(`project=${PROJECT} AND issuetype=Epic`)}&maxResults=100&fields=summary`,
  null, "/rest/api/3");
const epicKeys = {};
for (const it of es.issues || []) epicKeys[it.fields.summary] = it.key;

const creates = [
  {
    title: "[FE] Booth 용도별 Layout 템플릿·프리셋 세트",
    epic: "Booth Studio", components: ["Frontend (React)"], labels: ["front", "consultant-feedback"],
    sp: 3, priority: "Medium",
    desc: `h3. 작업 목적
외부 고객·타 지역 운영자가 빈 캔버스에서 시작하지 않도록, 용도별 Booth Layout 템플릿을 제공한다.
Published Layout 계약이 이미 "정해진 서식에 데이터를 넣어 Booth 생성"에 해당하므로 새 스키마 없이 프리셋만 얹는다.

h3. 작업 내용
* 용도별 템플릿 3~5종 (기업 홍보형 / 프로젝트 전시형 / 이벤트형 등) 을 Published Layout 샘플 세트로 제작
* Studio 진입점에 "템플릿에서 시작" 추가 → 선택 후 사용자가 수정
* 이미지 업로드 → 지정 슬롯(포스터·배너) 자동 배치 플로우
* 편집 역할 분리는 현행 유지 — 텍스트·이미지·정보 입력은 웹, 3D 반영·미리보기는 Unity
  (Unity WebGL 한글 IME 제약으로 인게임 에디터 통합은 채택하지 않는다)

h3. 완료 조건
* [ ] 템플릿 선택 → 수정 → Publish 가 신규 사용자 기준 5분 내 가능
* [ ] 템플릿 산출물이 기존 Published Layout 계약을 그대로 따른다 (새 필드 없음)
* [ ] 이미지 업로드분이 슬롯 규격(로컬 미터)에 맞게 배치된다

h3. 선행 작업 / Dependency
* Published Layout 계약 (GitLab #62) — 좌표·식별자 규격 불변

h3. 참고
* GitLab 이슈 #93
* docs/KHS/conference/10_부스_자동_생성과_외부_확장.md

h3. 테스트 방법
# 신규 계정으로 템플릿 선택 → 이미지 1장 업로드 → Publish → Unity 방문까지 관통`,
  },
  {
    title: "[BE] Booth 방문·체류·Coin 계측 이벤트 로깅",
    epic: "Economy (Coin)", components: ["Backend (Spring)"], labels: ["back", "consultant-feedback"],
    sp: 3, priority: "High",
    desc: `h3. 작업 목적
"Coin 시스템 유효성은 배포 후 사용자 반응으로 검증하라"는 피드백을 실행 가능하게 만든다.
검증하려면 배포 전에 계측이 심어져 있어야 한다 — 배포 후에는 늦다.
Coin·Lease 등 영구 상태의 Source of Truth 가 Spring 이므로 서버에서 기록하는 것이 정확하다.

h3. 작업 내용
* Booth 방문 이벤트 (boothId, userId, timestamp)
* Booth 체류시간 (입장/퇴장 쌍)
* Coin 획득·사용 흐름 (사유 코드 포함)
* 관리자용 집계 조회 1건 (Booth 별 방문수·평균 체류·Coin 순환량)
* Unity 클라이언트가 호출할 이벤트 API 계약 문서화

h3. 완료 조건
* [ ] 이벤트 API 계약 확정·문서화 (Unity 발신 지점과 합의)
* [ ] 방문·체류·Coin 3종 기록 동작
* [ ] 집계 조회 1건 동작

h3. 선행 작업 / Dependency
* 게임 클라이언트 발신 훅은 Booth 포털 텔레포트 지점 (BoothPortal/PortalInteractor)

h3. 참고
* GitLab 이슈 #94
* 검증 지표: Coin 획득 동기 / 사용처 / 방문·체류 증가 여부 / 보상 구조 실효성 / 운영 복잡도 대비 효과

h3. 테스트 방법
# Booth 입장·퇴장 후 집계 조회로 방문수·체류시간 반영 확인`,
  },
  {
    title: "[AI] Rule-based + LLM Booth 초안 생성 구조 확정",
    epic: "AI Agent", components: ["AI (FastAPI/RAG)"], labels: ["ai", "consultant-feedback"],
    sp: 3, priority: "Medium",
    desc: `h3. 작업 목적
컨설턴트가 긍정 평가한 Rule-based + LLM 조합의 역할 경계를 확정하고, 산출물이 기존 발행
파이프라인을 그대로 타도록 만든다.

h3. 작업 내용
* Rule-based 담당: Booth 규격(Published Layout 스키마 — 크기·배치 규칙·필수 슬롯),
  브랜드 색상 팔레트(12색 계약), 템플릿 선택 — 결정적으로 처리
* LLM 담당: 소개 문구, 콘텐츠 요약, 테마·장식 제안 — 창의 영역만
* 산출물 형식: Published Layout JSON + 문구 → 기존 발행 경로 재사용 (새 경로 만들지 않는다)
* Vector DB 는 이번 범위에서 제외 — 부스 12개·축적 사례 0건 규모에서는 검색할 코퍼스가 없어
  템플릿 전체가 프롬프트 하나에 들어간다. 정식 운영으로 사례가 쌓인 뒤 도입 (로드맵 기록)

h3. 완료 조건
* [ ] 입력(회사 정보·이미지) → Booth 초안 JSON 생성 → Studio 에서 수정 플로우 1회 관통
* [ ] 산출 JSON 이 Published Layout 계약을 위반하지 않는다 (계약 밖 필드 0)
* [ ] Rule / LLM 담당 경계가 문서화된다

h3. 선행 작업 / Dependency
* Published Layout 계약 (GitLab #62)

h3. 참고
* GitLab 이슈 #95
* docs/KHS/conference/10_부스_자동_생성과_외부_확장.md §5

h3. 테스트 방법
# 샘플 회사 정보로 초안 생성 → 계약 스키마 검증 → Unity 렌더 확인`,
  },
];

// 중복 방지 — 같은 제목이 이미 있으면 건너뛴다
const existing = await api("GET",
  `/search/jql?jql=${encodeURIComponent(`project=${PROJECT} AND labels="consultant-feedback"`)}&maxResults=50&fields=summary`,
  null, "/rest/api/3");
const have = new Set((existing.issues || []).map(i => i.fields.summary));

const created = [];
for (const c of creates) {
  if (have.has(c.title)) { console.log("건너뜀(이미 존재):", c.title); continue; }
  const f = {
    project: { key: PROJECT },
    summary: c.title,
    description: c.desc,
    issuetype: { name: "작업" },
    labels: c.labels,
    components: c.components.map(n => ({ name: n })),
    priority: { id: prio[c.priority] },
  };
  if (c.sp) f[SP_FIELD] = c.sp;
  if (c.epic && epicKeys[c.epic]) f[EPIC_FIELD] = epicKeys[c.epic];
  else console.log("⚠ 에픽 못 찾음:", c.epic);
  const r = await api("POST", "/issue", { fields: f });
  created.push(r.key);
  console.log("생성:", r.key, "|", c.title);
}

// ── 3. 선후행 링크 — 계측이 후속 최적화를 Blocks ──
const blockPairs = [["S15P21A604-235", "S15P21A604-236"], ["S15P21A604-235", "S15P21A604-237"]];
for (const [blocker, blocked] of blockPairs) {
  const cur = await api("GET", `/issue/${blocker}?fields=issuelinks`);
  const dup = (cur.fields.issuelinks || []).some(
    l => l.type?.name === "Blocks" && l.outwardIssue?.key === blocked);
  if (dup) { console.log("건너뜀(링크 존재):", blocker, "→", blocked); continue; }
  await api("POST", "/issueLink", {
    type: { name: "Blocks" },
    inwardIssue: { key: blocker },
    outwardIssue: { key: blocked },
  });
  console.log("링크:", blocker, "blocks", blocked);
}

console.log(`\n완료 — 생성 ${created.length}건 ${created.join(", ")}`);
console.log("신규 이슈는 스프린트 미편성(백로그) — 편성은 팀 결정.");
