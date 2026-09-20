/*
 * Booth Studio 임시 편집기 — Unity 검증 전용.
 *
 * 좌표계는 spec 005 C-02 확정 규칙을 그대로 쓴다:
 *   단위 미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0 = +Z
 * 화면은 위에서 내려다본 평면도다. 화면 오른쪽 = +X, 화면 아래 = +Z.
 */
const BOOTH = 7;
const API = `/api/v1/booths/${BOOTH}`;
const SNAP = 0.25;

let cat = null;                 // catalog.json
let objects = [];               // { objectId, type, position:{x,y,z}, rotationY, configId }
let version = 0;
let selected = -1;
let drag = null;
let seq = 0;

const cv = document.getElementById('cv');
const ctx = cv.getContext('2d');
const stEl = document.getElementById('st');

function status(msg, kind) {
  stEl.className = 'status' + (kind ? ' ' + kind : '');
  stEl.innerHTML = `<b>${msg}</b>`;
}

/* ---------- 좌표 변환: 미터 <-> 캔버스 픽셀 ---------- */
function view() {
  const w = cat ? cat.footprintMeters.w : 6;
  const d = cat ? cat.footprintMeters.d : 6;
  const pad = 40;
  const s = Math.min((cv.width - pad * 2) / w, (cv.height - pad * 2) / d);
  return { s, cx: cv.width / 2, cy: cv.height / 2, w, d };
}
const m2p = (x, z) => { const v = view(); return [v.cx + x * v.s, v.cy + z * v.s]; };
const p2m = (px, pz) => { const v = view(); return [(px - v.cx) / v.s, (pz - v.cy) / v.s]; };
const snap = n => Math.round(n / SNAP) * SNAP;

function partOf(type) { return cat.parts.find(p => p.type === type); }

/* 회전 반영한 사각형 반너비/반깊이 */
function halfExtent(part, rot) {
  const r = ((rot % 360) + 360) % 360;
  const swap = (r === 90 || r === 270);
  return swap ? [part.d / 2, part.w / 2] : [part.w / 2, part.d / 2];
}

/* ---------- 그리기 ---------- */
function draw() {
  cv.width = cv.clientWidth; cv.height = cv.clientHeight;
  ctx.clearRect(0, 0, cv.width, cv.height);
  if (!cat) return;
  const v = view();

  // 부스 바닥
  const [x0, z0] = m2p(-v.w / 2, -v.d / 2);
  ctx.fillStyle = '#161b25';
  ctx.fillRect(x0, z0, v.w * v.s, v.d * v.s);

  // 격자 0.5 m
  ctx.strokeStyle = '#222a38'; ctx.lineWidth = 1;
  for (let g = -v.w / 2; g <= v.w / 2 + 1e-6; g += 0.5) {
    const [px] = m2p(g, 0);
    ctx.beginPath(); ctx.moveTo(px, z0); ctx.lineTo(px, z0 + v.d * v.s); ctx.stroke();
  }
  for (let g = -v.d / 2; g <= v.d / 2 + 1e-6; g += 0.5) {
    const [, pz] = m2p(0, g);
    ctx.beginPath(); ctx.moveTo(x0, pz); ctx.lineTo(x0 + v.w * v.s, pz); ctx.stroke();
  }

  // 중심축
  ctx.strokeStyle = '#33405a';
  ctx.beginPath(); ctx.moveTo(v.cx, z0); ctx.lineTo(v.cx, z0 + v.d * v.s); ctx.stroke();
  ctx.beginPath(); ctx.moveTo(x0, v.cy); ctx.lineTo(x0 + v.w * v.s, v.cy); ctx.stroke();

  // 부스 경계 + 정면 표시
  ctx.strokeStyle = '#4b8ef0'; ctx.lineWidth = 2;
  ctx.strokeRect(x0, z0, v.w * v.s, v.d * v.s);
  ctx.fillStyle = '#4b8ef0'; ctx.font = '11px sans-serif'; ctx.textAlign = 'center';
  ctx.fillText('정면 (+Z, 방문자 진입)', v.cx, z0 + v.d * v.s + 18);
  ctx.fillText('뒷벽 (-Z)', v.cx, z0 - 8);

  // 오브젝트
  objects.forEach((o, i) => {
    const part = partOf(o.type);
    if (!part) return;
    const [hw, hd] = halfExtent(part, o.rotationY);
    const [px, pz] = m2p(o.position.x, o.position.z);
    const w = hw * 2 * v.s, h = hd * 2 * v.s;
    const on = i === selected;
    ctx.fillStyle = on ? 'rgba(240,166,46,.30)' : 'rgba(75,142,240,.20)';
    ctx.strokeStyle = on ? '#f0a62e' : '#4b8ef0';
    ctx.lineWidth = on ? 2 : 1.2;
    ctx.fillRect(px - w / 2, pz - h / 2, w, h);
    ctx.strokeRect(px - w / 2, pz - h / 2, w, h);

    // 정면 방향 표시 (rotationY 0 = +Z = 화면 아래)
    const rad = o.rotationY * Math.PI / 180;
    const fx = Math.sin(rad), fz = Math.cos(rad);
    ctx.strokeStyle = on ? '#f0a62e' : '#6fa4f5';
    ctx.beginPath(); ctx.moveTo(px, pz);
    ctx.lineTo(px + fx * Math.min(w, h) * 0.55, pz + fz * Math.min(w, h) * 0.55);
    ctx.stroke();

    ctx.fillStyle = on ? '#f7d9a0' : '#c8d6ef';
    ctx.font = '10px sans-serif'; ctx.textAlign = 'center';
    ctx.fillText(o.objectId, px, pz - h / 2 - 4);
  });

  document.getElementById('cnt').textContent = objects.length;
  document.getElementById('ver').textContent = version;
}

/* ---------- 선택 패널 ---------- */
function renderSel() {
  const el = document.getElementById('sel');
  if (selected < 0 || !objects[selected]) { el.textContent = '없음'; return; }
  const o = objects[selected];
  const p = partOf(o.type);
  el.innerHTML = `
    <div class="t">${p ? p.label : o.type}</div>
    <div class="row"><label>objectId</label><input type="text" id="eId" value="${o.objectId}"></div>
    <div class="row"><label>x (m)</label><input type="number" step="0.25" id="eX" value="${o.position.x}"></div>
    <div class="row"><label>z (m)</label><input type="number" step="0.25" id="eZ" value="${o.position.z}"></div>
    <div class="row"><label>rotationY</label><input type="number" step="15" id="eR" value="${o.rotationY}"></div>
    <div class="row"><label>configId</label><input type="number" step="1" id="eC" value="${o.configId}"></div>
    <button id="eDel" style="width:100%;margin-top:4px">삭제</button>`;
  const bind = (id, fn) => {
    const n = document.getElementById(id);
    n.addEventListener('change', () => { fn(n.value); draw(); });
  };
  bind('eId', v => o.objectId = v.trim() || o.objectId);
  bind('eX', v => o.position.x = parseFloat(v) || 0);
  bind('eZ', v => o.position.z = parseFloat(v) || 0);
  bind('eR', v => o.rotationY = ((parseFloat(v) || 0) % 360 + 360) % 360);
  bind('eC', v => o.configId = parseInt(v, 10) || 0);
  document.getElementById('eDel').onclick = () => {
    objects.splice(selected, 1); selected = -1; renderSel(); draw();
  };
}

/* ---------- 카탈로그 ---------- */
function renderCat() {
  const el = document.getElementById('cat');
  el.innerHTML = '';
  cat.parts.forEach(p => {
    const b = document.createElement('button');
    b.innerHTML = `<span>${p.label}</span><span class="dim">${p.w}×${p.d}</span>`;
    b.onclick = () => addPart(p);
    el.appendChild(b);
  });
}

function addPart(p) {
  if (objects.length >= cat.maxObjects) {
    status(`오브젝트는 최대 ${cat.maxObjects}개다 (FR-010)`, 'err'); return;
  }
  seq += 1;
  const id = p.type.toLowerCase().replace(/_/g, '-') + '-' + seq;
  objects.push({ objectId: id, type: p.type, position: { x: 0, y: 0, z: 0 }, rotationY: 0, configId: seq });
  selected = objects.length - 1;
  renderSel(); draw();
  status(`${p.label} 추가 — 드래그로 배치`, 'ok');
}

/* ---------- 마우스 ---------- */
function hit(mx, mz) {
  for (let i = objects.length - 1; i >= 0; i--) {
    const o = objects[i], p = partOf(o.type);
    if (!p) continue;
    const [hw, hd] = halfExtent(p, o.rotationY);
    if (Math.abs(mx - o.position.x) <= hw && Math.abs(mz - o.position.z) <= hd) return i;
  }
  return -1;
}
cv.addEventListener('pointerdown', e => {
  const r = cv.getBoundingClientRect();
  const [mx, mz] = p2m(e.clientX - r.left, e.clientY - r.top);
  const i = hit(mx, mz);
  selected = i; renderSel();
  if (i >= 0) { drag = { i, dx: objects[i].position.x - mx, dz: objects[i].position.z - mz }; cv.setPointerCapture(e.pointerId); }
  draw();
});
cv.addEventListener('pointermove', e => {
  if (!drag) return;
  const r = cv.getBoundingClientRect();
  const [mx, mz] = p2m(e.clientX - r.left, e.clientY - r.top);
  const o = objects[drag.i], p = partOf(o.type);
  const [hw, hd] = halfExtent(p, o.rotationY);
  const lim = cat.footprintMeters;
  o.position.x = Math.max(-lim.w / 2 + hw, Math.min(lim.w / 2 - hw, snap(mx + drag.dx)));
  o.position.z = Math.max(-lim.d / 2 + hd, Math.min(lim.d / 2 - hd, snap(mz + drag.dz)));
  draw();
});
cv.addEventListener('pointerup', e => { drag = null; renderSel(); });

window.addEventListener('keydown', e => {
  if (selected < 0) return;
  if (e.target.tagName === 'INPUT') return;
  if (e.key === 'r' || e.key === 'R') {
    objects[selected].rotationY = (objects[selected].rotationY + 90) % 360;
    renderSel(); draw(); e.preventDefault();
  }
  if (e.key === 'Delete' || e.key === 'Backspace') {
    objects.splice(selected, 1); selected = -1; renderSel(); draw(); e.preventDefault();
  }
});
window.addEventListener('resize', draw);

/* ---------- API ---------- */
function layoutBody() {
  return { boothId: BOOTH, template: 'PROJECT_EXHIBITION', version, objects };
}

async function loadDraft() {
  try {
    const r = await fetch(`${API}/layouts/draft`);
    const j = await r.json();
    objects = j.objects || []; version = j.version || 0;
    seq = objects.length;
    selected = -1; renderSel(); draw();
    status(`Draft 불러옴 — version ${version}, 오브젝트 ${objects.length}개`, 'ok');
  } catch (err) { status('Draft 불러오기 실패: ' + err.message, 'err'); }
}

async function saveDraft() {
  try {
    const r = await fetch(`${API}/layouts/draft`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(layoutBody()),
    });
    const j = await r.json();
    if (r.status === 409) {
      // C-05 확정 계약 — code 로만 분기한다
      status(`저장 거부 [${j.code}] ${j.message} — 다시 불러오세요`, 'err');
      return;
    }
    if (!r.ok) { status(`저장 실패 [${j.code}] ${j.message}`, 'err'); return; }
    version = j.version;
    draw();
    status(`Draft 저장됨 — version ${version} (방문자에게는 아직 안 보임)`, 'ok');
  } catch (err) { status('저장 실패: ' + err.message, 'err'); }
}

async function publish() {
  try {
    const r = await fetch(`${API}/layouts/publish`, { method: 'POST' });
    const j = await r.json();
    if (!r.ok) { status(`공개 실패 [${j.code}] ${j.message}`, 'err'); return; }
    status(`공개됨 — version ${j.publishedVersion}. Unity 에서 부스를 다시 로드하면 반영된다`, 'ok');
  } catch (err) { status('공개 실패: ' + err.message, 'err'); }
}

async function saveFacade() {
  const body = {
    themeCode: 'CUSTOM',
    primaryColor: document.getElementById('fcolorHex').value,
    signText: document.getElementById('fsign').value,
    logoUrl: null,
  };
  try {
    const r = await fetch(`${API}/facade`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!r.ok) { status('Facade 저장 실패', 'err'); return; }
    status(`Facade 저장됨 — ${body.primaryColor}`, 'ok');
  } catch (err) { status('Facade 저장 실패: ' + err.message, 'err'); }
}

/* ---------- 초기화 ---------- */
document.getElementById('load').onclick = loadDraft;
document.getElementById('save').onclick = saveDraft;
document.getElementById('publish').onclick = publish;
document.getElementById('clear').onclick = () => { objects = []; selected = -1; renderSel(); draw(); };
document.getElementById('fsave').onclick = saveFacade;
const fc = document.getElementById('fcolor'), fh = document.getElementById('fcolorHex');
fc.addEventListener('input', () => fh.value = fc.value.toUpperCase());
fh.addEventListener('change', () => { if (/^#[0-9a-fA-F]{6}$/.test(fh.value)) fc.value = fh.value; });

(async function init() {
  try {
    cat = await (await fetch('catalog.json')).json();
    renderCat(); draw();
    await loadDraft();
  } catch (err) {
    status('catalog.json 로드 실패: ' + err.message, 'err');
  }
})();
