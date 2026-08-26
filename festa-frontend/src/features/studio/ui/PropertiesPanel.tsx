// 선택된 오브젝트의 위치·회전·콘텐츠 연결을 편집하는 패널 — 편집기의 유일한 "정밀 입력" 지점
// 좌표는 EditorCanvas 드래그로도 바뀌지만, 정확한 숫자 입력·configId 연결은 여기서만 가능하다
// 출처: specs/005-booth-studio-layout/FE/tasks.md T014, data-model.md ObjectType 판정표

import { useEffect, useState } from 'react';
import type { LayoutObject } from '../../../entities/layout/types';
import { OBJECT_TYPE_INFO } from '../../../entities/layout/objectTypes';
import { CONFIG_ID_MAX, CONFIG_ID_MIN } from '../../../shared/config/studio';
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
  // 서버가 이 편집기가 모르는 타입을 보낼 수 있다(#56 GAME_PORTAL 등) — 판정 대신 안내만 하고
  // 위치·회전 편집은 계속 허용한다(SC-005: 미지 타입이 있어도 나머지는 정상 동작해야 한다, T026)
  const info = OBJECT_TYPE_INFO[object.type];
  const isDecorative = info?.category === 'DECORATIVE';

  // 입력 중 빈 문자열·"-"까지 허용하기 위해 로컬 문자열 상태를 두고, 유효한 숫자일 때만 dispatch한다.
  const [xText, setXText] = useState(String(object.position.x));
  const [zText, setZText] = useState(String(object.position.z));
  const [configText, setConfigText] = useState(object.configId !== undefined ? String(object.configId) : '');

  // 위치는 이 패널 밖에서도 바뀐다 — 캔버스 드래그(MOVE_OBJECT)와 다른 오브젝트 선택.
  // 로컬 문자열 상태만 두면 그 변화를 못 따라가 화면 숫자와 실제 저장값이 어긋난다(드래그로 옮겨도 0으로 남음).
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

  // configId는 signed Int32의 양수만 유효하다(계약 §1). 특히 0은 Unity가 "필드 부재"와 구별하지 못해
  // 미연결로 렌더링하고 서버 CHECK(config_id > 0)에도 걸리므로, 입력단에서 막아 상태에 들어가지 않게 한다.
  function commitConfigId(text: string) {
    if (text.trim() === '') {
      onLinkContent(undefined);
      return;
    }
    const n = Number(text);
    if (!Number.isInteger(n) || n < CONFIG_ID_MIN || n > CONFIG_ID_MAX) return;
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

      {info === undefined ? (
        <p>알 수 없는 타입입니다 — 서버 계약이 이 편집기보다 앞서 있습니다. 위치·회전만 편집할 수 있습니다.</p>
      ) : isDecorative ? (
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
            min={CONFIG_ID_MIN}
            max={CONFIG_ID_MAX}
            step={1}
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
