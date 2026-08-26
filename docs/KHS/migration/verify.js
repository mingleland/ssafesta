// 이관 정합성 검증: GitLab 이슈 1~83 을 GitHub 원본 덤프와 대조한다.
// 확인: 제목, 열림/닫힘 상태, 노트 수(코멘트+리뷰), 라벨.
// 사용: GITLAB_TOKEN=<토큰> node verify.js
const fs = require('fs');

const HOST = 'https://lab.ssafy.com';
const PROJECT = encodeURIComponent('s15-metaverse-game-sub1/S15P21A604');
const TOKEN = process.env.GITLAB_TOKEN;
if (!TOKEN) { console.error('GITLAB_TOKEN 없음'); process.exit(1); }

const load = f => JSON.parse(fs.readFileSync(f, 'utf8').replace(/\]\[/g, ','));
const items = load('items.json');
const comments = load('comments.json');

async function api(path) {
  const res = await fetch(`${HOST}/api/v4/${path}`, { headers: { 'PRIVATE-TOKEN': TOKEN } });
  if (!res.ok) throw new Error(`${path} → ${res.status}`);
  return res.json();
}

function expectedNotes(item) {
  const n = item.number;
  let count = comments.filter(c => c.issue_url.endsWith(`/issues/${n}`)).length;
  if (item.pull_request) {
    const reviews = load(`pr_${n}_reviews.json`);
    count += reviews.filter(r => r.body || r.state !== 'COMMENTED').length;
  }
  return count;
}

async function main() {
  // GitLab 이슈 전체 (100개씩 페이지)
  const gl = [];
  for (let page = 1; ; page++) {
    const batch = await api(`projects/${PROJECT}/issues?per_page=100&page=${page}&state=all`);
    gl.push(...batch);
    if (batch.length < 100) break;
  }
  const byIid = new Map(gl.map(i => [i.iid, i]));
  console.log(`GitLab 이슈 총 ${gl.length}개 (기대 83)`);

  let ok = 0, bad = 0;
  for (const item of items.sort((a, b) => a.number - b.number)) {
    const n = item.number;
    const g = byIid.get(n);
    const problems = [];
    if (!g) { console.log(`✗ #${n}: GitLab에 없음`); bad++; continue; }

    const wantTitle = (item.pull_request ? '[PR] ' : '') + item.title;
    if (g.title !== wantTitle) problems.push(`제목 불일치: "${g.title}"`);

    const wantClosed = item.state === 'closed';
    if ((g.state === 'closed') !== wantClosed) problems.push(`상태 불일치: GH=${item.state} GL=${g.state}`);

    const wantNotes = expectedNotes(item);
    if (g.user_notes_count !== wantNotes) problems.push(`노트 수: GL=${g.user_notes_count} 기대=${wantNotes}`);

    const wantLabels = new Set(item.labels.map(l => l.name).concat(item.pull_request ? ['gh:PR'] : []));
    const glLabels = new Set(g.labels);
    for (const l of wantLabels) if (!glLabels.has(l)) problems.push(`라벨 누락: ${l}`);

    if (problems.length) { console.log(`✗ #${n}: ${problems.join(' | ')}`); bad++; }
    else ok++;
  }
  console.log(`\n결과: 일치 ${ok} / 불일치 ${bad}`);
  const extra = gl.filter(g => g.iid > 83);
  if (extra.length) console.log('⚠ 83번 초과 이슈:', extra.map(g => g.iid).join(','));
}

main().catch(e => { console.error('오류:', e.message); process.exit(1); });
