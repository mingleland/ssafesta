// SSAFESTA Jira 백로그 일괄 생성 — docs/KHS/27_JIRA_백로그_설계.md 의 실행기
// 사용: node jira_seed.mjs <backlog.json> [--dry]
// 인증: 사용자 환경변수 ATLASSIAN_API_TOKEN (Basic, 이메일 고정)
import { readFileSync } from "node:fs";
import { execSync } from "node:child_process";

const SITE = "https://ssafy.atlassian.net";
const EMAIL = "gudtnslwkd@naver.com";
const PROJECT = "S15P21A604";
const BOARD = 15273;
const DRY = process.argv.includes("--dry");

const token = execSync(
  `powershell.exe -NoProfile -Command "[Environment]::GetEnvironmentVariable('ATLASSIAN_API_TOKEN','User')"`,
  { encoding: "utf8" }
).trim();
if (!token) { console.error("ATLASSIAN_API_TOKEN 없음 (사용자 환경변수)"); process.exit(1); }
const AUTH = "Basic " + Buffer.from(`${EMAIL}:${token}`).toString("base64");

async function api(method, path, body, base = "/rest/api/2") {
  if (DRY && method !== "GET") { console.log(`[dry] ${method} ${path}`, body ? JSON.stringify(body).slice(0, 120) : ""); return {}; }
  const res = await fetch(SITE + base + path, {
    method,
    headers: { Authorization: AUTH, "Content-Type": "application/json", Accept: "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : {};
}
const agile = (m, p, b) => api(m, p, b, "/rest/agile/1.0");
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const backlog = JSON.parse(readFileSync(process.argv[2], "utf8"));

// ── 0. 메타 탐색: 이슈타입·필드·우선순위 ──
const project = await api("GET", `/project/${PROJECT}`);
const types = Object.fromEntries(project.issueTypes.map(t => [t.name, t.id]));
console.log("이슈타입:", Object.keys(types).join(", "));
const fields = await api("GET", "/field");
const spField = fields.find(f => /story points?( estimate)?/i.test(f.name) || f.name === "스토리 포인트")?.id;
const epicNameField = fields.find(f => f.name === "Epic Name" || f.name === "에픽 이름")?.id;
const epicLinkField = fields.find(f => f.name === "Epic Link" || f.name === "에픽 링크")?.id;
console.log("SP필드:", spField, "| EpicName:", epicNameField, "| EpicLink:", epicLinkField);
const priorities = await api("GET", "/priority");
const prio = Object.fromEntries(priorities.map(p => [p.name, p.id]));
const prioMap = (k) => prio[k] || prio[{Highest:"Highest",High:"High",Medium:"Medium",Low:"Low",Lowest:"Lowest"}[k]] || prio["Medium"];

// ── 1. 컴포넌트 ──
const existingComp = await api("GET", `/project/${PROJECT}/components`);
for (const c of backlog.components) {
  if (existingComp.some(e => e.name === c)) { console.log("컴포넌트 존재:", c); continue; }
  await api("POST", "/component", { name: c, project: PROJECT });
  console.log("컴포넌트 생성:", c);
}

// ── 2. 버전 ──
const existingVer = await api("GET", `/project/${PROJECT}/versions`);
for (const v of backlog.versions) {
  if (existingVer.some(e => e.name === v.name)) { console.log("버전 존재:", v.name); continue; }
  await api("POST", "/version", { name: v.name, projectId: Number(project.id), releaseDate: v.date, released: !!v.released, description: v.desc || "" });
  console.log("버전 생성:", v.name);
}

// ── 3. 에픽 ──
const epicKeys = {}; // name → key
const search = await api("GET", `/search/jql?jql=${encodeURIComponent(`project=${PROJECT} AND issuetype=Epic`)}&maxResults=100&fields=summary`, null, "/rest/api/3");
for (const it of search.issues || []) epicKeys[it.fields.summary] = it.key;
for (const e of backlog.epics) {
  if (epicKeys[e.name]) { console.log("에픽 존재:", e.name, epicKeys[e.name]); continue; }
  const f = { project: { key: PROJECT }, issuetype: { id: types["에픽"] || types["Epic"] }, summary: e.name, description: e.desc || "", labels: e.labels || [] };
  if (epicNameField) f[epicNameField] = e.name;
  const created = await api("POST", "/issue", { fields: f });
  epicKeys[e.name] = created.key;
  console.log("에픽 생성:", e.name, created.key);
  await sleep(150);
}

// ── 4. 이슈 ──
const createdIssues = []; // {key, sprint, done, resolutionSprint}
for (const i of backlog.issues) {
  const typeName = { 스토리: "스토리", 작업: "작업", 버그: "버그" }[i.type] || "작업";
  const f = {
    project: { key: PROJECT },
    issuetype: { id: types[typeName] || types[{스토리:"Story",작업:"Task",버그:"Bug"}[i.type]] },
    summary: i.title,
    description: i.desc || "",
    labels: i.labels || [],
    priority: { id: prioMap(i.priority || "Medium") },
    components: (i.components || []).map(n => ({ name: n })),
  };
  if (spField && i.sp) f[spField] = i.sp;
  if (i.version) f.fixVersions = [{ name: i.version }];
  if (epicLinkField && i.epic && epicKeys[i.epic]) f[epicLinkField] = epicKeys[i.epic];
  else if (i.epic && epicKeys[i.epic]) f.parent = { key: epicKeys[i.epic] };
  const created = await api("POST", "/issue", { fields: f });
  createdIssues.push({ key: created.key, ...i });
  console.log(`이슈 ${created.key} | ${i.title} | SP${i.sp} | ${i.sprint || "백로그"}${i.done ? " | DONE" : ""}`);
  await sleep(150);
}

// ── 5. 스프린트 ──
const sprintIds = {};
const existingSprints = await agile("GET", `/board/${BOARD}/sprint?state=active,future,closed`).catch(() => ({ values: [] }));
for (const s of existingSprints.values || []) sprintIds[s.name] = s.id;
for (const s of backlog.sprints) {
  if (sprintIds[s.name]) { console.log("스프린트 존재:", s.name); continue; }
  const created = await agile("POST", "/sprint", { name: s.name, startDate: s.start + "T00:00:00.000+09:00", endDate: s.end + "T23:59:00.000+09:00", originBoardId: BOARD, goal: s.goal || "" });
  sprintIds[s.name] = created.id;
  console.log("스프린트 생성:", s.name, created.id);
}

// ── 6. 이슈 → 스프린트 배치 ──
for (const s of backlog.sprints) {
  const keys = createdIssues.filter(i => i.sprint === s.name).map(i => i.key);
  for (let k = 0; k < keys.length; k += 50)
    await agile("POST", `/sprint/${sprintIds[s.name]}/issue`, { issues: keys.slice(k, k + 50) });
  if (keys.length) console.log(`배치: ${s.name} ← ${keys.length}건`);
}

// ── 7. Done 이슈 전환 ──
for (const i of createdIssues.filter(x => x.done)) {
  if (DRY) { console.log("[dry] Done 전환:", i.title); continue; }
  const tr = await api("GET", `/issue/${i.key}/transitions`);
  const done = tr.transitions.find(t => t.to?.statusCategory?.key === "done");
  if (done) { await api("POST", `/issue/${i.key}/transitions`, { transition: { id: done.id } }); }
  else console.log("⚠ Done 전환 못 찾음:", i.key);
  await sleep(120);
}
console.log("Done 전환:", createdIssues.filter(x => x.done).length, "건");

// ── 8. 소급 스프린트 닫기 + 현재 스프린트 시작 ──
for (const s of backlog.sprints.filter(x => x.state === "closed" || x.state === "active")) {
  const id = sprintIds[s.name];
  await agile("POST", `/sprint/${id}`, { state: "active", startDate: s.start + "T00:00:00.000+09:00", endDate: s.end + "T23:59:00.000+09:00" }).catch(e => console.log("start 실패:", s.name, e.message.slice(0, 120)));
  if (s.state === "closed")
    await agile("POST", `/sprint/${id}`, { state: "closed" }).catch(e => console.log("close 실패:", s.name, e.message.slice(0, 120)));
  console.log(`스프린트 ${s.state}:`, s.name);
}
// ── 9. 저장 필터 (JQL) ──
const filters = [
  { name: "SSAFESTA — 이번 스프린트 잔여", jql: `project = ${PROJECT} AND sprint in openSprints() AND statusCategory != Done ORDER BY priority DESC` },
  { name: "SSAFESTA — 블로커", jql: `project = ${PROJECT} AND (labels = blocked OR status = "BLOCKED") AND statusCategory != Done` },
  { name: "SSAFESTA — 릴리즈 노트 (v0.1.0-mvp1)", jql: `project = ${PROJECT} AND fixVersion = "v0.1.0-mvp1" AND statusCategory = Done ORDER BY component ASC` },
  { name: "SSAFESTA — 담당자 미배정", jql: `project = ${PROJECT} AND assignee IS EMPTY AND statusCategory != Done AND issuetype != Epic ORDER BY priority DESC` },
];
for (const fl of filters) {
  await api("POST", "/filter", { name: fl.name, jql: fl.jql, description: "docs/KHS/27 자동 생성" }).then(
    () => console.log("필터 생성:", fl.name),
    (e) => console.log("필터 스킵:", fl.name, e.message.slice(0, 80)));
}
console.log("\n완료. 보드: " + SITE + `/jira/software/c/projects/${PROJECT}/boards/${BOARD}`);
