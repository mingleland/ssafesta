// 캔버스 렌더러가 공유하는 계약 — SVG(TemporaryIsoRenderer)와 R3F(R3FBoothRenderer)가 같은 props 를 받는다.
// 이 파일이 생기기 전에는 BoothDecor 가 TemporaryIsoRenderer 안에 있어서, 두 번째 렌더러를 만들면
// SVG 구현을 import 해야 타입이 잡혔다. 렌더러를 바꿔 끼우는 seam 이 타입 때문에 한쪽에 묶이면 안 된다.
import type { LayoutObject } from '../../../../entities/layout/types';
import type { TransformTool } from '../../model/studioMode';

export interface BoothDecor {
  floorHex: string;
  wallHex: string;
  primaryHex: string;
  signText: string;
  graphic: boolean;
}

export interface BoothBounds {
  width: number;
  depth: number;
  height: number;
}

export interface BoothRendererProps {
  objects: LayoutObject[];
  selectedObjectId: string | null;
  bounds: BoothBounds;
  /** 1 = 기본. 두 렌더러 모두 이 배율만큼 확대한다 */
  zoom: number;
  tool: TransformTool;
  snapOn: boolean;
  decor: BoothDecor;
  onSelect: (objectId: string | null) => void;
  onMove: (objectId: string, x: number, z: number) => void;
  onRotate: (objectId: string, rotationY: number) => void;
}
