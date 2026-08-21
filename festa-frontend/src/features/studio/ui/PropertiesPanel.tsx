// 선택된 오브젝트의 위치·회전·콘텐츠 연결을 편집하는 패널 — 편집기의 유일한 "정밀 입력" 지점
// 좌표는 EditorCanvas 드래그로도 바뀌지만, 정확한 숫자 입력·configId 연결은 여기서만 가능하다
// 출처: specs/005-booth-studio-layout/FE/tasks.md T014, data-model.md ObjectType 판정표

import { useState } from 'react';
import type { LayoutObject } from '../../../entities/layout/types';
import { OBJECT_TYPE_INFO } from '../../../entities/layout/objectTypes';
import { clampToBooth, normalizeRotation } from '../lib/coords';

interface Props {
  object: LayoutObject;
  bounds: { width: number; depth: number };
  onMove: (x: number, z: number) => void;
  onRotate: (rotationY: number) => void;
  onLinkContent: (configId: number | undefined) => void;
  onSetAssetCode: (assetCode: string | undefined) => void;
  onRemove: () => void;
}

export function PropertiesPanel({ object, bounds, onMove, onRotate, onLinkContent, onSetAssetCode, onRemove }: Props) {
  const info = OBJECT_TYPE_INFO[object.type];
  const isDecorative = info.category === 'DECORATIVE';

  // 입력 중 빈 문자열·"-"까지 허용하기 위해 로컬 문자열 상태를 두고, 유효한 숫자일 때만 dispatch한다.
  const [xText, setXText] = useState(String(object.position.x));
  const [zText, setZText] = useState(String(object.position.z));
  const [configText, setConfigText] = useState(object.configId !== undefined ? String(object.configId) : '');

  function commitPosition(nextXText: string, nextZText: string) {
    const x = Number(nextXText);
    const z = Number(nextZText);
    if (!Number.isFinite(x) || !Number.isFinite(z)) return; // 비숫자 입력단 차단 — 도달 자체를 막는다(FE 사전 검증 4)
    const clamped = clampToBooth(x, z, bounds);
    onMove(clamped.x, clamped.z);
  }

  function commitConfigId(text: string) {
    if (text.trim() === '') {
      onLinkContent(undefined);
      return;
    }
    const n = Number(text);
    if (!Number.isFinite(n)) return;
    onLinkContent(n);
  }

  return (
    <div>
      <p>{object.type}</p>

      <label>
        x
        <input
          type="number"
          value={xText}
          onChange={(e) => setXText(e.target.value)}
          onBlur={() => commitPosition(xText, zText)}
        />
      </label>
      <label>
        z
        <input
          type="number"
          value={zText}
          onChange={(e) => setZText(e.target.value)}
          onBlur={() => commitPosition(xText, zText)}
        />
      </label>
      <label>
        회전
        <input
          type="number"
          value={object.rotationY}
          onChange={(e) => {
            const n = Number(e.target.value);
            if (Number.isFinite(n)) onRotate(normalizeRotation(n));
          }}
        />
      </label>

      {isDecorative ? (
        <label>
          자산 코드
          <input
            type="text"
            value={object.assetCode ?? ''}
            onChange={(e) => onSetAssetCode(e.target.value.trim() === '' ? undefined : e.target.value)}
          />
        </label>
      ) : (
        <label>
          연결 콘텐츠 ID
          <input
            type="number"
            value={configText}
            onChange={(e) => setConfigText(e.target.value)}
            onBlur={() => commitConfigId(configText)}
          />
        </label>
      )}

      <button type="button" onClick={onRemove}>
        삭제
      </button>
    </div>
  );
}
