// 의자 착석 이모트(PlayerEmoteId.SitChair*)의 실측표 — 2026-09-18, HumanF_Model 에 클립을 샘플해
// (t = 25·50·75 %) 본 위치를 루트 기준으로 잰 값. 단위는 모델 원본(m). 런타임에는 외형 배율을 곱한다.
//
//   클립         엉덩이 관절 y   발 y     길이
//   SitChair1    0.599          0.072    45.0 s   (앉아서 이야기)
//   SitChair2    0.679          0.088     4.8 s   (앉아 있기)
//
// 왜 표가 필요한가: 두 클립의 앉은 높이가 8 cm 다르다. 루트를 바닥에 그냥 두면 한쪽은 좌면에 맞고
// 다른 쪽은 8 cm 떠 보인다. 좌면 높이에서 역산해 루트를 내려 두면 둘 다 같은 의자에 맞는다.
using Festa.Network;

namespace Festa.World
{
    public static class SitPoseTable
    {
        public static bool IsSit(PlayerEmoteId e) => e == PlayerEmoteId.SitChair1 || e == PlayerEmoteId.SitChair2;

        /// <summary>
        /// 루트 기준 <b>허벅지 관절</b> 높이(m). 좌면에 닿는 곳은 엉덩이 살이고 그 바로 위가 이 관절이라,
        /// 높이를 맞추는 기준으로 엉덩이 관절보다 이쪽이 정확하다.
        /// 실측 2026-09-18: SitChair1 0.539 · SitChair2 0.621.
        /// </summary>
        public static float ThighAboveRoot(PlayerEmoteId e) => e switch
        {
            PlayerEmoteId.SitChair1 => 0.539f,
            PlayerEmoteId.SitChair2 => 0.621f,
            _ => 0f,
        };

        /// <summary>루트 기준 발바닥 높이(m). 바닥을 뚫는지 보는 데 쓴다.</summary>
        public static float FootAboveRoot(PlayerEmoteId e) => e switch
        {
            PlayerEmoteId.SitChair1 => 0.072f,
            PlayerEmoteId.SitChair2 => 0.088f,
            _ => 0f,
        };

        /// <summary>
        /// 앉았을 때 허벅지 관절이 좌면 위로 뜨는 높이(m). 관절은 살 안쪽에 있으므로 닿아 있어도 0 이 아니다.
        /// 두 클립의 앉은 높이가 8 cm 다른데(0.539 / 0.621), 이 값을 기준으로 역산하면 같은 의자에 둘 다 맞는다.
        /// </summary>
        /// <para>0.06 으로 두자 살짝 떠 보인다는 지적을 받았다(2026-09-18). 관절이 좌면과 같은 높이면
        /// 엉덩이 살이 좌면에 조금 잠겨 닿아 보인다 — 뜨는 것보다 잠기는 쪽이 눈에 덜 걸린다.</para>
        public const float ThighClearanceAboveSeat = 0.005f;

        /// <summary>의자 F 가 고르는 후보. 무작위 선택은 앉는 사람이 하고 EmoteId 로 동기화된다.</summary>
        public static readonly PlayerEmoteId[] ChairPoses = { PlayerEmoteId.SitChair1, PlayerEmoteId.SitChair2 };

        /// <summary>
        /// 로컬 플레이어가 앉아 있는가. 앉은 동안에는 같은 의자에 F 를 다시 눌러도 아무 일도 없어야 한다 —
        /// 재텔레포트하면 자세만 바뀌며 몸이 튄다(눕기에서 겪은 것과 같은 결, T-24 기록).
        /// 일어나는 길은 WASD 하나다.
        /// </summary>
        public static bool IsLocalPlayerSitting()
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var po = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            var np = po != null ? po.GetComponent<NetworkPlayer>() : null;
            return np != null && IsSit(np.EmoteId.Value);
        }
    }
}
