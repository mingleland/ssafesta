using UnityEngine;

namespace Festa.Avatar
{
    /// <summary>
    /// 런타임 스킨메시 병합을 켜고 끄는 스위치 (S15P21A604-258).
    ///
    /// <para><b>왜 상수 게이트가 아니라 스위치인가.</b> 2026-08-26 에 WebGL 빌드에서 옷을 갈아입으면
    /// 목·손·종아리가 사라져 `Application.platform == WebGLPlayer` 면 병합을 건너뛰게 막았다
    /// (T-214). 원인은 "WebGL 스킨메시 처리와의 상성" 으로 **추정**만 하고 확정하지 못했고,
    /// 그 대가로 아바타당 약 −4 드로우콜을 포기한 채로 두 달이 지났다.</para>
    ///
    /// <para><b>그 추정을 의심할 근거가 생겼다.</b> 당시 증상은 <b>"옷을 갈아입으면"</b> 나타났는데,
    /// 이는 T-228 에서 잡은 재조립 버그와 <b>같은 방아쇠·같은 부위</b>다 — 병합이 원본을 끈 뒤
    /// 아무도 다시 켜지 않아, 두 번째 호출에서 새 병합체가 만들어지지 않는데 옛 병합체는
    /// 파괴돼 <b>그릴 것이 아무것도 남지 않는</b> 형태다. 그 버그는 2026-08-30 에 고쳐졌다.
    /// 게다가 당시 같은 날 "월드 전체 투명" 은 <b>오염된 증분 빌드 캐시</b>였고 클린 빌드로
    /// 나았는데, 병합 증상의 판정도 그 오염된 빌드에서 나왔을 수 있다.</para>
    ///
    /// <para><b>그래서 추측으로 게이트를 풀지 않는다.</b> 한 빌드 안에서 켜고 끌 수 있게 해
    /// <b>같은 빌드·같은 조건</b>에서 비교한다 — 빌드를 두 번 떠서 비교하면 빌드 간 차이가
    /// 섞여 조건 통제가 무너진다 (T-211). 기본값은 <b>지금까지의 동작 그대로</b>이므로
    /// 이 파일이 들어간다고 동작이 바뀌지는 않는다.</para>
    /// </summary>
    public static class AvatarMeshMerge
    {
        static bool? s_enabled;

        /// <summary>
        /// 기본값은 기존 동작 — WebGL 플레이어에서만 끈다.
        /// 확정 전까지 배포 동작을 바꾸지 않는다.
        /// </summary>
        public static bool DefaultEnabled => Application.platform != RuntimePlatform.WebGLPlayer;

        public static bool Enabled
        {
            get => s_enabled ?? DefaultEnabled;
            set => s_enabled = value;
        }

        /// <summary>기본값으로 되돌린다.</summary>
        public static void Reset() => s_enabled = null;

        /// <summary>
        /// 화면에 그대로 찍을 수 있는 상태 문자열. 스크린샷만 보고도 어느 조건의 결과인지
        /// 알 수 있어야 A/B 표본이 섞이지 않는다 (T-211).
        /// </summary>
        public static string StateLabel =>
            $"스킨메시 병합: {(Enabled ? "ON" : "OFF")}" +
            (s_enabled.HasValue ? " (수동)" : " (기본)");
    }
}
