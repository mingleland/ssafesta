using System;
using UnityEngine;

namespace Festa.Booth
{
    // ── 전시 프로젝트 계약 ───────────────────────────────────────
    // GET /api/v1/booths/{boothId}/projects/published (docs/08 §5, spec 009 §6).
    // **방문자용이라 토큰이 없어도 200 이다** — 축제장에서 남의 부스 간판을 읽는 것이 정상 경로다.
    // Unity 는 읽기만 한다. 필요한 것은 name·thumbnailUrl 둘뿐이라 나머지 필드는 담지 않는다.
    //
    // 게이트가 계약이다 (docs/08 §5 표):
    //   404 BOOTH_NOT_FOUND · 409 BOOTH_LEASE_EXPIRED · 404 LAYOUT_NOT_PUBLISHED · 200 { "projects": [] }
    // Unity 는 이 넷을 전부 **"간판에 띄울 게 없다"** 하나로 접는다 — 축제장 밖에서 실패 이유를
    // 구분해 봐야 할 일이 없고, 이미 BoothVacancyPresenter 가 같은 판정으로 빈 부스를 끄고 있다.
    // ───────────────────────────────────────────────────────────

    [Serializable]
    public class BoothProjectDto
    {
        public int projectId;
        public string name;
        public string thumbnailUrl;   // https 만. null 가능 (spec 009 C-03 — 업로드 미지원·URL 참조)

        /// <summary>
        /// 소개 영상 주소. 서버는 예전부터 실어 보내고 있었는데(<c>VisitorProjectView.videoUrl</c>)
        /// Unity 가 받지 않고 버리고 있었다 — 부스 스크린이 쓰려고 이제 받는다 (2026-09-18).
        /// 지금 들어오는 값은 대부분 YouTube 링크다. null 가능.
        /// </summary>
        public string videoUrl;
    }

    [Serializable]
    public class BoothProjectsDto
    {
        public BoothProjectDto[] projects;
    }

    public static class BoothProjectParser
    {
        /// <summary>JSON → DTO. 실패 시 null(예외를 삼키지 않고 로그).</summary>
        public static BoothProjectsDto Parse(string json)
        {
            if (string.IsNullOrEmpty(json)) return null;
            try
            {
                var dto = JsonUtility.FromJson<BoothProjectsDto>(json);
                if (dto == null)
                {
                    Debug.LogError("[BoothProjectParser] 응답을 BoothProjectsDto 로 해석할 수 없다");
                    return null;
                }
                // projects 키가 아예 없으면 JsonUtility 는 null 을 남긴다. 빈 배열로 정규화해 두면
                // 소비자가 "파싱 실패(null)" 와 "전시 없음(빈 배열)" 을 계속 구분할 수 있다.
                dto.projects ??= Array.Empty<BoothProjectDto>();
                return dto;
            }
            catch (Exception e)
            {
                Debug.LogError($"[BoothProjectParser] Parse 실패: {e.Message}");
                return null;
            }
        }
    }
}
