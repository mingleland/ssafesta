// 이벤트 설문 key 는 **한 곳에만** 적혀 있어야 한다 (S15P21A604-619, GitLab #173 Q4).
//
// 예전에는 값이 앱과 mock 두 곳에 따로 적혀 있었고, 이 테스트는 그 둘이 같은지를 비교했다.
// 이제 계약 정본(`shared/contracts/survey`)에서 둘 다 가져오므로 비교는 항진명제가 됐다.
// 그래서 잠그는 대상을 바꾼다 — **값이 같은가**가 아니라 **값이 다시 흩어지지 않았는가**다.
// 리터럴이 소비자 쪽에 되살아나는 순간 옛 결함이 그대로 돌아온다(한쪽만 바뀌면 mock 모드에서
// 설문이 404 로만 보이고 앱은 멀쩡해 보인다).
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { EVENT_SURVEY_KEY } from '../../../../shared/contracts/survey';
import { resolveEventSurveyTarget } from '../../model/surveyEntry';
import { MOCK_EVENT_SURVEY_KEY } from '../../../../entities/survey/api.mock';

const read = (relative: string): string => (
  readFileSync(new URL(relative, import.meta.url), 'utf8')
);

describe('이벤트 설문 key — 단일 정본', () => {
  it('앱 진입과 mock 이 모두 계약 정본 값을 쓴다', () => {
    expect(resolveEventSurveyTarget()).toEqual({ kind: 'event', surveyKey: EVENT_SURVEY_KEY });
    expect(MOCK_EVENT_SURVEY_KEY).toBe(EVENT_SURVEY_KEY);
  });

  it('소비자 쪽에 key 리터럴이 다시 생기지 않았다', () => {
    // 정본 파일 밖에서 이 문자열을 다시 적는 순간 두 벌 관계가 부활한다.
    expect(read('../../model/surveyEntry.ts')).not.toContain(EVENT_SURVEY_KEY);
    expect(read('../../../../entities/survey/api.mock.ts')).not.toContain(EVENT_SURVEY_KEY);
  });
});

