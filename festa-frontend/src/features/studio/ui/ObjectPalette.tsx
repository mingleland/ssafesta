// 오브젝트 타입 10종을 배치하는 팔레트 — maxObjects 도달 시 전체를 비활성화한다(서버 §9 응답값)
import { OBJECT_TYPES } from '../../../entities/layout/objectTypes';
import type { ObjectType } from '../../../entities/layout/types';

interface Props {
  currentCount: number;
  maxObjects: number;
  onAdd: (type: ObjectType) => void;
}

export function ObjectPalette({ currentCount, maxObjects, onAdd }: Props) {
  const full = currentCount >= maxObjects;
  return (
    <div>
      <p>
        오브젝트 {currentCount}/{maxObjects}
      </p>
      <ul>
        {OBJECT_TYPES.map((type) => (
          <li key={type}>
            <button type="button" disabled={full} onClick={() => onAdd(type)}>
              {type}
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
