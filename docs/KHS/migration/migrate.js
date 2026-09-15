// GitHub kanghyunsoon/ssafesta → GitLab s15-metaverse-game-sub1/S15P21A604 이슈·PR 이관.
//
// 전제: GitLab 프로젝트에 이슈가 0개 (iid 자동증가가 1부터 시작).
// 방식: GitHub 번호 1..83 순서대로 GitLab 이슈를 만들어 번호를 그대로 보존한다.
//       PR 은 [PR] 접두사 이슈로 기록한다 (본문 헤더에 base←head·머지 정보).
//       매 생성마다 반환 iid 를 검증하고, 어긋나면 즉시 중단한다.
// 사용: GITLAB_TOKEN=<api 스코프 토큰> node migrate.js [시작번호]
const fs = require('fs');

const HOST = 'https://lab.ssafy.com';
const PROJECT = encodeURIComponent('s15-metaverse-game-sub1/S15P21A604');
const TOKEN = process.env.GITLAB_TOKEN;
const START = parseInt(process.argv[2] || '1', 10);
if (!TOKEN) { console.error('GITLAB_TOKEN 없음'); process.exit(1); }

const load = f => JSON.parse(fs.readFileSync(f, 'utf8').replace(/\]\[/g, ','));
const items = load('items.json');
const comments = load('comments.json');
const ghLabels = JSON.parse(fs.readFileSync('labels.json', 'utf8'));

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function api(method, path, body) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = await fetch(`${HOST}/api/v4/${path}`, {
      method,
      headers: { 'PRIVATE-TOKEN': TOKEN, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 429 || res.status >= 500) {  // 레이트리밋·서버 오류는 재시도
      await sleep(2000 * (attempt + 1));
      continue;
    }
    const text = await res.text();
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 300)}`);
    return text ? JSON.parse(text) : null;
  }
  throw new Error(`${method} ${path} — 재시도 소진`);
}

const kst = iso => new Date(iso).toLocaleString('sv-SE', { timeZone: 'Asia/Seoul' }).slice(0, 16) + ' KST';

function header(item, pr) {
  const lines = [
    '> **GitHub 이관** — 원본: ' + item.html_url,
    `> 작성: @${item.user.login} · ${kst(item.created_at)}`,
  ];
  if (pr) {
    lines.push(`> PR: \`${pr.base.ref}\` ← \`${pr.head.ref}\``);
    if (pr.merged_at) lines.push(`> 머지: ${kst(pr.merged_at)} · merge commit \`${(pr.merge_commit_sha || '').slice(0, 9)}\``);
    else if (item.state === 'closed') lines.push('> 머지 없이 닫힘');
  }
  if (item.closed_at && !(pr && pr.merged_at)) lines.push(`> 닫힘: ${kst(item.closed_at)}`);
  return lines.join('\n') + '\n\n---\n\n';
}

async function main() {
  // 0) 사전 검증: 프로젝트 접근 + 시작번호 정합
  const proj = await api('GET', `projects/${PROJECT}`);
  console.log(`프로젝트 OK: ${proj.path_with_namespace} (id ${proj.id})`);
  const existing = await api('GET', `projects/${PROJECT}/issues?per_page=1&order_by=created_at&sort=desc`);
  const maxIid = existing.length ? existing[0].iid : 0;
  if (maxIid !== START - 1)
    throw new Error(`iid 정합 실패: 프로젝트 최대 iid=${maxIid}, 기대=${START - 1}. 시작번호를 확인해라.`);

  // 1) 라벨 (이미 있으면 409 → 무시)
  if (START === 1) {
    for (const l of ghLabels) {
      try { await api('POST', `projects/${PROJECT}/labels`, { name: l.name, color: '#' + l.color, description: l.description || '' }); }
      catch (e) { if (!String(e).includes('already exists') && !String(e).includes('409')) throw e; }
    }
    try { await api('POST', `projects/${PROJECT}/labels`, { name: 'gh:PR', color: '#6e49cb', description: 'GitHub PR 이관 기록' }); } catch (e) {}
    console.log(`라벨 ${ghLabels.length}+1개 준비`);
  }

  // 2) 이슈·PR 순차 생성
  const byNumber = new Map(items.map(i => [i.number, i]));
  for (let n = START; n <= 83; n++) {
    const item = byNumber.get(n);
    if (!item) throw new Error(`#${n} 원본 없음`);
    const isPr = !!item.pull_request;
    const pr = isPr ? JSON.parse(fs.readFileSync(`pr_${n}.json`, 'utf8')) : null;

    const title = (isPr ? '[PR] ' : '') + item.title;
    const labels = item.labels.map(l => l.name).concat(isPr ? ['gh:PR'] : []).join(',');
    const desc = header(item, pr) + (item.body || '_(본문 없음)_');

    const created = await api('POST', `projects/${PROJECT}/issues`, {
      title, description: desc.slice(0, 1048576), labels,
    });
    if (created.iid !== n)
      throw new Error(`번호 어긋남: #${n} 생성 결과 iid=${created.iid}. 즉시 중단 — GitLab 이슈 ${created.iid} 삭제 후 ${n}부터 재개해라.`);

    // 노트: 이슈 코멘트 + PR 리뷰를 시간순으로
    const notes = comments.filter(c => c.issue_url.endsWith(`/issues/${n}`))
      .map(c => ({ at: c.created_at, body: `> @${c.user.login} · ${kst(c.created_at)} (GitHub 이관)\n\n${c.body || ''}` }));
    if (isPr) {
      const reviews = load(`pr_${n}_reviews.json`);
      for (const r of reviews)
        if (r.body || r.state !== 'COMMENTED')
          notes.push({ at: r.submitted_at, body: `> @${r.user.login} 리뷰 **${r.state}** · ${kst(r.submitted_at)} (GitHub 이관)\n\n${r.body || ''}` });
    }
    notes.sort((a, b) => new Date(a.at) - new Date(b.at));
    for (const note of notes) {
      await api('POST', `projects/${PROJECT}/issues/${n}/notes`, { body: note.body.slice(0, 1000000) });
      await sleep(120);
    }

    if (item.state === 'closed')
      await api('PUT', `projects/${PROJECT}/issues/${n}`, { state_event: 'close' });

    console.log(`#${n} ${isPr ? 'PR' : '이슈'} ✓ 노트 ${notes.length}${item.state === 'closed' ? ' (닫음)' : ''}`);
    await sleep(150);
  }
  console.log('이관 완료: 1~83');
}

main().catch(e => { console.error('중단:', e.message); process.exit(1); });
