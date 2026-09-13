// 이벤트 설문 key 는 두 곳에 적혀 있고 같아야 한다 (S15P21A604-619, GitLab #173).
//
// mock 어댑터가 앱 상수와 **같은 값**을 쓰는 것은 의도다 — mock 의 존재 이유가 BE 도달 전에 화면을
// 돌려 보는 것이라, 다른 key 를 주면 mock 모드에서 설문이 404 로만 보인다(api.mock.ts 주석).
// 문제는 값이 두 군데 따로 적혀 있어 한쪽만 바뀌면 **조용히** 어긋난다는 것이다. 상수를 한 곳으로
// 옮기면 entities 가 features 를 참조하게 되거나 shared 로 끌어올려야 해서 레이어를 건드린다 —
// 그 값어치보다 이 한 줄이 싸고, 어긋나는 순간 빨간불이 켜진다.
import { describe, expect, it } from 'vitest';
import { EVENT_SURVEY_KEY } from '../../model/surveyEntry';
import { MOCK_EVENT_SURVEY_KEY } from '../../../../entities/survey/api.mock';

describe('이벤트 설문 key', () => {
  it('mock 이 쓰는 key 와 앱이 쓰는 key 가 같다', () => {
    expect(MOCK_EVENT_SURVEY_KEY).toBe(EVENT_SURVEY_KEY);
  });
});
