// 이관된 GitLab 이슈·노트 속 GitHub 링크 재작성.
//   - issues/N, pull/N (앵커 없음) → #N  — 번호가 보존돼 GitLab에서 그대로 열린다
//   - blob/tree 파일 링크 → lab.ssafy.com 동일 경로 (커밋 SHA·브랜치 동일)
//   - "GitHub 이관" 출처 헤더 줄과 코멘트 앵커(#issuecomment) 링크는 원문 유지
// 사용: GITLAB_TOKEN=<토큰> node fixlinks.js
const HOST = 'https://lab.ssafy.com';
const PROJECT = encodeURIComponent('s15-metaverse-game-sub1/S15P21A604');
const GL_WEB = 'https://lab.ssafy.com/s15-metaverse-game-sub1/S15P21A604';
const TOKEN = process.env.GITLAB_TOKEN;
if (!TOKEN) { console.error('GITLAB_TOKEN 없음'); process.exit(1); }

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function api(method, path, body) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const res = await fetch(`${HOST}/api/v4/${path}`, {
      method,
      headers: { 'PRIVATE-TOKEN': TOKEN, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (res.status === 429 || res.status >= 500) { await sleep(2000 * (attempt + 1)); continue; }
    const text = await res.text();
    if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 200)}`);
    return text ? JSON.parse(text) : null;
  }
  throw new Error(`${method} ${path} — 재시도 소진`);
}

function rewrite(text) {
  if (!text) return text;
  return text.split('\n').map(line => {
    if (line.includes('GitHub 이관')) return line;   // 출처 헤더는 원문 유지
    return line
      // 앵커 없는 이슈/PR URL → #N (뒤에 단어문자·#·/ 가 이어지지 않을 때만)
      .replace(/https:\/\/github\.com\/kanghyunsoon\/ssafesta\/(?:issues|pull)\/(\d+)(?![\w#\/])/g, '#$1')
      // 파일 링크는 호스트만 GitLab 으로 (blob/tree 경로·SHA 동일)
      .replace(/https:\/\/github\.com\/kanghyunsoon\/ssafesta\/(blob|tree)\//g, `${GL_WEB}/-/$1/`);
  }).join('\n');
}

async function main() {
  let descFixed = 0, noteFixed = 0;
  for (let n = 1; n <= 83; n++) {
    const issue = await api('GET', `projects/${PROJECT}/issues/${n}`);
    const newDesc = rewrite(issue.description);
    if (newDesc !== issue.description) {
      await api('PUT', `projects/${PROJECT}/issues/${n}`, { description: newDesc });
      descFixed++;
      console.log(`#${n} 본문 재작성`);
    }
    // 노트 (시스템 노트 제외)
    for (let page = 1; ; page++) {
      const notes = await api('GET', `projects/${PROJECT}/issues/${n}/notes?per_page=100&page=${page}`);
      for (const note of notes) {
        if (note.system) continue;
        const newBody = rewrite(note.body);
        if (newBody !== note.body) {
          await api('PUT', `projects/${PROJECT}/issues/${n}/notes/${note.id}`, { body: newBody });
          noteFixed++;
          await sleep(100);
        }
      }
      if (notes.length < 100) break;
    }
    await sleep(80);
  }
  console.log(`완료: 본문 ${descFixed}건, 노트 ${noteFixed}건 재작성`);
}

main().catch(e => { console.error('중단:', e.message); process.exit(1); });
