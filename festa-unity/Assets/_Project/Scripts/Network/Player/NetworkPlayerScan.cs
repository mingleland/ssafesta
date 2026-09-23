// 한 프레임 안에서 반복되는 NetworkPlayer 전수 탐색을 한 번으로 줄인다.
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 점유 판정처럼 <b>한 프레임에 여러 번</b> 사람 목록을 묻는 곳을 위한 캐시 (S15P21A604-970).
    ///
    /// <para>오락기와 의자는 각자 <c>IsOccupied</c> 에서 씬 전체를 훑는다. 상호작용 스캔이 대상마다 그것을
    /// 부르므로, 오락기 20대와 의자 수십 개가 있는 월드에서는 같은 답을 얻으려고 한 프레임에 수십 번
    /// 전수 탐색을 돌았다. 답은 프레임 안에서 변하지 않으므로 한 번만 구해 나눠 쓴다.</para>
    ///
    /// <para><see cref="NetworkPlayer"/> 는 동결 기준선이라 등록 방식으로 바꾸지 않았다(헌법 27조).
    /// 여기서 프레임 단위로만 캐시한다 — 스폰·디스폰은 다음 프레임에 반영된다.</para>
    /// </summary>
    public static class NetworkPlayerScan
    {
        static NetworkPlayer[] s_players = System.Array.Empty<NetworkPlayer>();
        static int s_frame = -1;

        /// <summary>이번 프레임의 활성 <see cref="NetworkPlayer"/> 목록. 호출한 쪽은 내용을 바꾸지 않는다.</summary>
        public static NetworkPlayer[] All()
        {
            int frame = Time.frameCount;
            if (frame == s_frame && s_players != null) return s_players;

            s_frame = frame;
            s_players = Object.FindObjectsByType<NetworkPlayer>(FindObjectsInactive.Exclude, FindObjectsSortMode.None);
            return s_players;
        }
    }
}
