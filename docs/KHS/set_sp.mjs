// SSAFESTA — 게임 파트 미입력 스토리 포인트 일괄 설정
// 사용: node set_sp.mjs [--apply]   (기본은 점검만)
const SITE = "https://ssafy.atlassian.net";
const EMAIL = "gudtnslwkd@naver.com";
const SP_FIELD = "customfield_10031";
const token = process.env.ATLASSIAN_API_TOKEN;
if (!token) { console.error("ATLASSIAN_API_TOKEN 없음"); process.exit(1); }
const AUTH = "Basic " + Buffer.from(EMAIL + ":" + token).toString("base64");
const APPLY = process.argv.includes("--apply");

const PLAN = [
  ["S15P21A604-585", 8], ["S15P21A604-676", 1], ["S15P21A604-694", 2],
  ["S15P21A604-703", 2], ["S15P21A604-730", 5], ["S15P21A604-738", 5],
  ["S15P21A604-747", 2], ["S15P21A604-749", 3], ["S15P21A604-750", 3],
  ["S15P21A604-759", 3], ["S15P21A604-760", 2], ["S15P21A604-761", 5],
  ["S15P21A604-762", 3], ["S15P21A604-774", 1], ["S15P21A604-777", 2],
  ["S15P21A604-778", 3], ["S15P21A604-779", 5], ["S15P21A604-780", 1],
  ["S15P21A604-781", 3], ["S15P21A604-782", 3], ["S15P21A604-803", 3],
  ["S15P21A604-862", 2], ["S15P21A604-875", 3], ["S15P21A604-883", 3],
  ["S15P21A604-937", 8],
];

async function api(method, path, body) {
  const res = await fetch(SITE + "/rest/api/2" + path, {
    method,
    headers: { Authorization: AUTH, "Content-Type": "application/json", Accept: "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  const t = await res.text();
  if (!res.ok) throw new Error(method + " " + path + " -> " + res.status + ": " + t.slice(0, 200));
  return t ? JSON.parse(t) : {};
}

let done = 0, skipped = 0, failed = 0;
for (const [key, sp] of PLAN) {
  try {
    const iss = await api("GET", "/issue/" + key + "?fields=summary,assignee," + SP_FIELD);
    const cur = iss.fields[SP_FIELD];
    const who = iss.fields.assignee ? iss.fields.assignee.displayName : "없음";
    if (cur !== null && cur !== undefined) {
      console.log("SKIP " + key + " 이미 SP=" + cur + " | " + iss.fields.summary.slice(0, 44));
      skipped++; continue;
    }
    if (!APPLY) {
      console.log("PLAN " + key + " SP=" + sp + " 담당=" + who + " | " + iss.fields.summary.slice(0, 44));
      continue;
    }
    await api("PUT", "/issue/" + key, { fields: { [SP_FIELD]: sp } });
    console.log("SET  " + key + " SP=" + sp + " 담당=" + who + " | " + iss.fields.summary.slice(0, 44));
    done++;
  } catch (e) {
    console.log("FAIL " + key + " :: " + String(e.message).slice(0, 180));
    failed++;
  }
}
console.log("---");
console.log((APPLY ? "적용" : "점검") + " 완료 — 설정 " + done + " / 건너뜀 " + skipped + " / 실패 " + failed);
