// SSAFESTA Jira 백로그 v2 개정 실행기 — 일정 변경(9/18 마감)·용어 통일·이슈 분할·현업 템플릿
// 사용: node jira_seed_v2.mjs [--dry]
// 입력: jira_v2_meta.json, jira_v2_updates_*.json, jira_v2_creates.json (같은 폴더)
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const DIR = dirname(fileURLToPath(import.meta.url));
const SITE = "https://ssafy.atlassian.net";
const EMAIL = "gudtnslwkd@naver.com";
const PROJECT = "S15P21A604";
const BOARD = 15273;
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
const agile = (m, p, b) => api(m, p, b, "/rest/agile/1.0");
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

const meta = JSON.parse(readFileSync(join(DIR, "jira_v2_meta.json"), "utf8"));
const updates = readdirSync(DIR).filter(f => /^jira_v2_updates_.*\.json$/.test(f)).sort()
  .flatMap(f => JSON.parse(readFileSync(join(DIR, f), "utf8")));
const creates = JSON.parse(readFileSync(join(DIR, "jira_v2_creates.json"), "utf8"));
console.log(`입력: updates ${updates.length} · creates ${creates.length}`);

// 우선순위 id
const prios = await api("GET", "/priority");
const prio = Object.fromEntries(prios.map(p => [p.name, p.id]));

// 에픽 name→key
const epicKeys = {};
const es = await api("GET", `/search/jql?jql=${encodeURIComponent(`project=${PROJECT} AND issuetype=Epic`)}&maxResults=100&fields=summary`, null, "/rest/api/3");
for (const it of es.issues || []) epicKeys[it.fields.summary] = it.key;

// ── 1. 버전 개정 (rename/date/create) ──
const vers = await api("GET", `/project/${PROJECT}/versions`);
for (const v of meta.versions) {
  if (v.create) {
    if (vers.some(x => x.name === v.name)) { console.log("버전 존재:", v.name); continue; }
    const proj = await api("GET", `/project/${PROJECT}`);
    await api("POST", "/version", { name: v.name, projectId: Number(proj.id), releaseDate: v.date, description: v.desc });
    console.log("버전 생성:", v.name);
  } else {
    const cur = vers.find(x => x.name === v.find);
    if (!cur) { console.log("⚠ 버전 못 찾음:", v.find); continue; }
    await api("PUT", `/version/${cur.id}`, { name: v.name, releaseDate: v.date, description: v.desc });
    console.log(`버전 개정: ${v.find} → ${v.name} (${v.date})`);
  }
}

// ── 2. 스프린트 날짜·골 개정 + Sprint 8 삭제 ──
const sp = await agile("GET", `/board/${BOARD}/sprint?state=active,future,closed`);
const sprintIds = Object.fromEntries((sp.values || []).map(s => [s.name, s.id]));
for (const s of meta.sprints) {
  const id = sprintIds[s.name];
  if (!id) { console.log("⚠ 스프린트 못 찾음:", s.name); continue; }
  await api("POST", `/sprint/${id}`, { name: s.name, startDate: s.start + "T00:00:00.000+09:00", endDate: s.end + "T23:59:00.000+09:00", goal: s.goal }, "/rest/agile/1.0");
  console.log(`스프린트 개정: ${s.name} ${s.start}~${s.end}`);
}
for (const name of meta.deleteSprints || []) {
  const id = sprintIds[name];
  if (!id) continue;
  await api("DELETE", `/sprint/${id}`, null, "/rest/agile/1.0").then(
    () => console.log("스프린트 삭제:", name),
    e => console.log("⚠ 삭제 실패:", name, e.message.slice(0, 100)));
}

// ── 3. 제자리 갱신 (제목·설명·SP·우선순위·버전·라벨) + 스프린트 이동 ──
const sprintMoves = {}; // sprintName → [keys], "backlog" → [keys]
for (const u of updates) {
  const f = {};
  if (u.title) f.summary = u.title;
  if (u.desc) f.description = u.desc;
  if (u.sp != null) f[SP_FIELD] = u.sp;
  if (u.priority) f.priority = { id: prio[u.priority] };
  if (u.version !== undefined) f.fixVersions = u.version ? [{ name: u.version }] : [];
  if (u.labels) f.labels = u.labels;
  await api("PUT", `/issue/${u.key}`, { fields: f });
  if (u.sprint) (sprintMoves[u.sprint] = sprintMoves[u.sprint] || []).push(u.key);
  console.log("갱신:", u.key, "|", (u.title || "(설명만)").slice(0, 60));
  await sleep(120);
}

// ── 4. 분할·신규 생성 ──
const project = await api("GET", `/project/${PROJECT}`);
const types = Object.fromEntries(project.issueTypes.map(t => [t.name, t.id]));
const createdByTitle = {};
for (const i of creates) {
  const f = {
    project: { key: PROJECT },
    issuetype: { id: types[i.type] || types["작업"] },
    summary: i.title,
    description: i.desc || "",
    labels: i.labels || [],
    priority: { id: prio[i.priority || "Medium"] },
    components: (i.components || []).map(n => ({ name: n })),
  };
  if (i.sp) f[SP_FIELD] = i.sp;
  if (i.version) f.fixVersions = [{ name: i.version }];
  if (i.epic && epicKeys[i.epic]) f[EPIC_FIELD] = epicKeys[i.epic];
  const c = await api("POST", "/issue", { fields: f });
  createdByTitle[i.title] = c.key;
  if (i.sprint) (sprintMoves[i.sprint] = sprintMoves[i.sprint] || []).push(c.key);
  console.log("생성:", c.key, "|", i.title.slice(0, 60), "| SP" + i.sp, "|", i.sprint || "백로그");
  await sleep(120);
}

// ── 5. 스프린트 이동 / 백로그 이동 ──
for (const [name, keys] of Object.entries(sprintMoves)) {
  for (let k = 0; k < keys.length; k += 50) {
    const chunk = keys.slice(k, k + 50);
    if (name === "backlog") await agile("POST", "/backlog/issue", { issues: chunk });
    else await agile("POST", `/sprint/${sprintIds[name]}/issue`, { issues: chunk });
  }
  console.log("이동:", name, "←", keys.length + "건");
}

// ── 6. 이슈 링크 ──
const resolveRef = (r) => r.startsWith("T:") ? createdByTitle[r.slice(2)] : r;
for (const l of meta.links || []) {
  const outward = resolveRef(l.outward), inward = resolveRef(l.inward);
  if (!outward || !inward) { console.log("⚠ 링크 참조 실패:", l.outward, "→", l.inward); continue; }
  await api("POST", "/issueLink", { type: { name: l.type }, outwardIssue: { key: inward }, inwardIssue: { key: outward } }).then(
    () => console.log("링크:", outward, "blocks", inward),
    e => console.log("⚠ 링크 실패:", outward, inward, e.message.slice(0, 80)));
  await sleep(100);
}
console.log("\nv2 개정 완료");
