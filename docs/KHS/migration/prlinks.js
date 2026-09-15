// [PR] 이관 이슈에 GitLab 쪽 항해 링크를 붙인다.
// 머지 커밋 SHA 가 GitLab 히스토리에 그대로 있으므로(40/40 확인) 실제 diff 를 볼 수 있다.
// 사용: GITLAB_TOKEN=<토큰> node prlinks.js
const fs = require('fs');
const HOST = 'https://lab.ssafy.com';
const PROJECT = encodeURIComponent('s15-metaverse-game-sub1/S15P21A604');
const WEB = 'https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604';
const TOKEN = process.env.GITLAB_TOKEN;
if (!TOKEN) { console.error('GITLAB_TOKEN 없음'); process.exit(1); }
const load = f => JSON.parse(fs.readFileSync(f, 'utf8').replace(/\]\[/g, ','));
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function api(method, path, body) {
  for (let a = 0; a < 5; a++) {
    const res = await fetch(`${HOST}/api/v4/${path}`, {
      method, headers: { 'PRIVATE-TOKEN': TOKEN, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined });
    if (res.status === 429 || res.status >= 500) { await sleep(2000 * (a + 1)); continue; }
    const t = await res.text();
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${t.slice(0, 200)}`);
    return t ? JSON.parse(t) : null;
  }
  throw new Error(`${method} ${path} 재시도 소진`);
}

const MARK = '<!-- gitlab-nav -->';

async function main() {
  const items = load('items.json');
  const prs = items.filter(i => i.pull_request).map(i => i.number).sort((a, b) => a - b);
  let done = 0, skip = 0;
  for (const n of prs) {
    const pr = JSON.parse(fs.readFileSync(`pr_${n}.json`, 'utf8'));
    const issue = await api('GET', `projects/${PROJECT}/issues/${n}`);
    if ((issue.description || '').includes(MARK)) { skip++; continue; }

    const lines = [MARK, '', '---', '', '### GitLab 쪽 확인'];
    if (pr.merged_at) {
      const sha = pr.merge_commit_sha;
      lines.push(`- 머지 커밋(변경 내용 전체): ${WEB}/-/commit/${sha}`);
      lines.push(`- 대상 브랜치: [\`${pr.base.ref}\`](${WEB}/-/tree/${pr.base.ref})`);
      lines.push(`- 커밋 ${pr.commits}개 · +${pr.additions}/-${pr.deletions} · 파일 ${pr.changed_files}개`);
      lines.push('');
      lines.push('> 머지된 PR 은 소스 브랜치가 남아 있지 않아 MR 로 재현할 수 없다 —');
      lines.push('> 커밋 히스토리는 위 링크로 전부 확인된다 (SHA 동일).');
    } else if (pr.state === 'open') {
      lines.push(`- 원래 열려 있던 PR: \`${pr.base.ref}\` ← \`${pr.head.ref}\``);
      lines.push('> 소스 브랜치가 GitLab 에 올라오면 실제 MR 로 전환할 수 있다.');
    } else {
      lines.push(`- 머지 없이 닫힘: \`${pr.base.ref}\` ← \`${pr.head.ref}\``);
    }
    await api('PUT', `projects/${PROJECT}/issues/${n}`,
      { description: (issue.description || '') + '\n\n' + lines.join('\n') });
    done++;
    console.log(`#${n} 링크 추가${pr.merged_at ? ' (머지 커밋)' : ''}`);
    await sleep(120);
  }
  console.log(`완료: 추가 ${done}건, 이미 있어 건너뜀 ${skip}건`);
}
main().catch(e => { console.error('중단:', e.message); process.exit(1); });
