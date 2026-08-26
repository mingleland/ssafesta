// 열려 있던 PR 4건(72·79·80·82)을 진짜 MR 로 만든다.
// 전제: 소스·대상 브랜치가 GitLab 에 올라와 있어야 한다 (codex/* 4개).
// 원 코멘트는 이미 [PR] 이슈에 있으므로, MR 본문에서 그 이슈를 가리키고
// 이슈에는 MR 링크를 남긴 뒤 이슈를 닫는다 (기록은 이슈, 작업은 MR).
// 사용: GITLAB_TOKEN=<토큰> node openmr.js
const fs = require('fs');
const HOST = 'https://lab.ssafy.com';
const PROJECT = encodeURIComponent('s15-metaverse-game-sub1/S15P21A604');
const TOKEN = process.env.GITLAB_TOKEN;
if (!TOKEN) { console.error('GITLAB_TOKEN 없음'); process.exit(1); }
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function api(method, path, body) {
  for (let a = 0; a < 5; a++) {
    const res = await fetch(`${HOST}/api/v4/${path}`, {
      method, headers: { 'PRIVATE-TOKEN': TOKEN, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined });
    if (res.status === 429 || res.status >= 500) { await sleep(2000 * (a + 1)); continue; }
    const t = await res.text();
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${t.slice(0, 300)}`);
    return t ? JSON.parse(t) : null;
  }
  throw new Error(`${method} ${path} 재시도 소진`);
}

async function main() {
  const branches = (await api('GET', `projects/${PROJECT}/repository/branches?per_page=100`)).map(b => b.name);
  const targets = [72, 79, 80, 82];
  for (const n of targets) {
    const pr = JSON.parse(fs.readFileSync(`pr_${n}.json`, 'utf8'));
    const src = pr.head.ref, dst = pr.base.ref;
    if (!branches.includes(src) || !branches.includes(dst)) {
      console.log(`#${n} 건너뜀 — 브랜치 없음 (src ${src}:${branches.includes(src)}, dst ${dst}:${branches.includes(dst)})`);
      continue;
    }
    const body = [
      `> **GitHub 이관** — 원본 PR: ${pr.html_url}`,
      `> 작성: @${pr.user.login} · 논의 기록은 #${n} 에 있다.`,
      '', '---', '', pr.body || '_(본문 없음)_',
    ].join('\n');
    let mr;
    try {
      mr = await api('POST', `projects/${PROJECT}/merge_requests`, {
        source_branch: src, target_branch: dst,
        title: pr.title, description: body,
        remove_source_branch: false,
      });
    } catch (e) {
      console.log(`#${n} MR 생성 실패 — ${e.message.slice(0, 160)}`);
      continue;
    }
    await api('POST', `projects/${PROJECT}/issues/${n}/notes`,
      { body: `이 PR 은 실제 MR 로 전환됐다: !${mr.iid} (${mr.web_url})\n\n논의 기록은 이 이슈에 남기고, 작업·리뷰는 MR 에서 진행한다.` });
    await api('PUT', `projects/${PROJECT}/issues/${n}`, { state_event: 'close' });
    console.log(`#${n} → MR !${mr.iid}  (${dst} ← ${src})`);
    await sleep(200);
  }
}
main().catch(e => { console.error('중단:', e.message); process.exit(1); });
