// 선택된 오브젝트의 위치·회전·콘텐츠 연결을 편집하는 패널 — 편집기의 유일한 "정밀 입력" 지점
// 좌표는 캔버스 드래그로도 바뀌지만, 정확한 숫자 입력·configId 연결은 여기서만 가능하다
// 출처: specs/005-booth-studio-layout/FE/tasks.md T014, data-model.md ObjectType 판정표
// 표현은 Booth Studio Inspector(booth-2_5d-reference: Transform / 속성 / 재질) — 계약에 없는 항목은 목업(PROVISIONAL) 표기

import { useEffect, useState } from 'react';
import type { LayoutObject } from '../../../entities/layout/types';
import { OBJECT_LOCAL_BOUNDS, OBJECT_TYPE_INFO } from '../../../entities/layout/objectTypes';
import { CONFIG_ID_MAX, CONFIG_ID_MIN } from '../../../shared/config/studio';
import { clampToBooth, normalizeRotation } from '../lib/coords';
import { IcCopy, IcTrash } from './shell/icons';

interface Props {
  object: LayoutObject;
  bounds: { width: number; depth: number };
  onMove: (x: number, z: number) => void;
  onRotate: (rotationY: number) => void;
  onLinkContent: (configId: number | undefined) => void;
  onSetAssetCode: (assetCode: string | undefined) => void;
  onRemove: () => void;
}

const TYPE_LABEL: Record<string, string> = {
  AI_AGENT: 'AI 직원',
  VIDEO_SCREEN: '영상 스크린',
  PROJECT_PANEL: '그래픽 패널',
  SURVEY_KIOSK: '설문 키오스크',
  RECRUITMENT_BOARD: '채용 보드',
  CONSULTATION_DESK: '상담 데스크',
  LAPTOP: '노트북',
  LIKE_VOTE: '좋아요 스탠드',
  FURNITURE: '가구',
  DECORATION: '장식',
};

export function PropertiesPanel({ object, bounds, onMove, onRotate, onLinkContent, onSetAssetCode, onRemove }: Props) {
  // 서버가 이 편집기가 모르는 타입을 보낼 수 있다(#56 GAME_PORTAL 등) — 판정 대신 안내만 하고
  // 위치·회전 편집은 계속 허용한다(SC-005: 미지 타입이 있어도 나머지는 정상 동작해야 한다, T026)
  const info = OBJECT_TYPE_INFO[object.type];
  const isDecorative = info?.category === 'DECORATIVE';
  const local = OBJECT_LOCAL_BOUNDS[object.type];

  // 입력 중 빈 문자열·"-"까지 허용하기 위해 로컬 문자열 상태를 두고, 유효한 숫자일 때만 dispatch한다.
  const [xText, setXText] = useState(String(object.position.x));
  const [zText, setZText] = useState(String(object.position.z));
  const [configText, setConfigText] = useState(object.configId !== undefined ? String(object.configId) : '');

  // 위치는 이 패널 밖에서도 바뀐다 — 캔버스 드래그(MOVE_OBJECT)와 다른 오브젝트 선택.
  useEffect(() => {
    setXText(String(object.position.x));
    setZText(String(object.position.z));
  }, [object.objectId, object.position.x, object.position.z]);

  useEffect(() => {
    setConfigText(object.configId !== undefined ? String(object.configId) : '');
  }, [object.objectId, object.configId]);

  function commitPosition(nextXText: string, nextZText: string) {
    const x = Number(nextXText);
    const z = Number(nextZText);
    if (!Number.isFinite(x) || !Number.isFinite(z)) return; // 비숫자 입력단 차단 — 도달 자체를 막는다(FE 사전 검증 4)
    const clamped = clampToBooth(x, z, bounds);
    onMove(clamped.x, clamped.z);
  }

  // configId는 signed Int32의 양수만 유효하다(계약 §1). 0은 Unity가 "필드 부재"와 구별하지 못한다.
  function commitConfigId(text: string) {
    if (text.trim() === '') {
      onLinkContent(undefined);
      return;
    }
    const n = Number(text);
    if (!Number.isInteger(n) || n < CONFIG_ID_MIN || n > CONFIG_ID_MAX) return;
    onLinkContent(n);
  }

  const size = local
    ? { w: (local.max.x - local.min.x).toFixed(2), h: local.max.y.toFixed(2), d: (local.max.z - local.min.z).toFixed(2) }
    : null;

  return (
    <>
      <div className="studio-group">
        <p className="studio-group-title">Transform</p>
        <span className="studio-field-label">위치 (m)</span>
        <div className="studio-row">
          <label className="studio-input"><span className="studio-input-axis" data-axis="x">X</span>
            <input type="number" step={0.25} value={xText} aria-label="x" onChange={(e) => setXText(e.target.value)} onBlur={() => commitPosition(xText, zText)} />
          </label>
          <label className="studio-input" title="편집기는 바닥 높이 0 으로 고정 기록한다"><span className="studio-input-axis" data-axis="y">Y</span>
            <input type="number" value={0} aria-label="y" readOnly />
          </label>
          <label className="studio-input"><span className="studio-input-axis" data-axis="z">Z</span>
            <input type="number" step={0.25} value={zText} aria-label="z" onChange={(e) => setZText(e.target.value)} onBlur={() => commitPosition(xText, zText)} />
          </label>
        </div>
        <span className="studio-field-label">회전 (°)</span>
        <div className="studio-row">
          <label className="studio-input"><span className="studio-input-axis" data-axis="x">X</span><input type="number" value={0} readOnly aria-label="회전 x" /></label>
          <label className="studio-input"><span className="studio-input-axis" data-axis="y">Y</span>
            <input
              type="number"
              step={15}
              value={object.rotationY}
              aria-label="회전"
              onChange={(e) => {
                const n = Number(e.target.value);
                if (Number.isFinite(n)) onRotate(normalizeRotation(n));
              }}
            />
          </label>
          <label className="studio-input"><span className="studio-input-axis" data-axis="z">Z</span><input type="number" value={0} readOnly aria-label="회전 z" /></label>
        </div>
        <span className="studio-field-label">크기 (m) — 프리팹 고정</span>
        <div className="studio-row">
          <span className="studio-input"><span className="studio-input-axis" data-axis="x">X</span>{size?.w ?? '—'}</span>
          <span className="studio-input"><span className="studio-input-axis" data-axis="y">Y</span>{size?.h ?? '—'}</span>
          <span className="studio-input"><span className="studio-input-axis" data-axis="z">Z</span>{size?.d ?? '—'}</span>
        </div>
      </div>

      <div className="studio-group">
        <p className="studio-group-title">속성</p>
        <div className="studio-kv"><span className="studio-kv-label">타입</span><span>{TYPE_LABEL[object.type] ?? object.type}</span></div>
        {info === undefined ? (
          <p className="studio-note">알 수 없는 타입입니다 — 서버 계약이 이 편집기보다 앞서 있습니다. 위치·회전만 편집할 수 있습니다.</p>
        ) : isDecorative ? (
          <>
            <span className="studio-field-label">자산 코드</span>
            <label className="studio-input">
              <input type="text" value={object.assetCode ?? ''} aria-label="자산 코드" placeholder="외형 코드" onChange={(e) => onSetAssetCode(e.target.value.trim() === '' ? undefined : e.target.value)} />
            </label>
          </>
        ) : info.linksConfigId ? (
          <>
            <span className="studio-field-label">연결 콘텐츠 ID</span>
            <label className="studio-input">
              <input type="number" min={CONFIG_ID_MIN} max={CONFIG_ID_MAX} step={1} value={configText} aria-label="연결 콘텐츠 ID" placeholder="미연결" onChange={(e) => setConfigText(e.target.value)} onBlur={() => commitConfigId(configText)} />
            </label>
            {info.warnOnMissingConfig && configText === '' && <p className="studio-note">연결이 없으면 게시 시 경고 대상입니다.</p>}
          </>
        ) : (
          <p className="studio-note">홈페이지 주소는 부스 설정(외관 모드)에서 등록합니다.</p>
        )}
        <div className="studio-kv"><span className="studio-kv-label">양면<span className="studio-provisional">목업</span></span><button type="button" className="studio-toggle" role="switch" aria-checked="true" disabled aria-label="양면" /></div>
        <div className="studio-kv"><span className="studio-kv-label">그림자<span className="studio-provisional">목업</span></span><button type="button" className="studio-toggle" role="switch" aria-checked="true" disabled aria-label="그림자" /></div>
      </div>

      <div className="studio-group">
        <p className="studio-group-title">재질<span className="studio-provisional">목업</span></p>
        <div className="studio-kv"><span className="studio-kv-label">표면</span><span>무광 (Matte)</span></div>
        <div className="studio-kv"><span className="studio-kv-label">기본 색상</span><span className="studio-color-chip" style={{ background: '#e5e9f2' }} /></div>
        <div className="studio-kv"><span className="studio-kv-label">포인트 색상</span><span className="studio-color-chip" style={{ background: '#3b82f6' }} /></div>
        <p className="studio-note">재질·색은 계약에 없는 항목 — 실제 저장되지 않는다.</p>
      </div>

      <div className="studio-inspector-foot">
        <button type="button" className="studio-btn" disabled title="복제는 후속"><IcCopy size={15} /> 복제</button>
        <button type="button" className="studio-btn studio-btn-danger" onClick={onRemove}><IcTrash size={15} /> 삭제</button>
      </div>
    </>
  );
}
