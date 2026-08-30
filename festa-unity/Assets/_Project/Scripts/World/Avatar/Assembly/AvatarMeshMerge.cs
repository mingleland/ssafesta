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
        /// 기본값 — **모든 플랫폼에서 켠다.**
        ///
        /// <para>2026-08-26 부터 WebGL 에서만 꺼져 있었다(T-214). 근거는 "런타임 병합 스킨메시가
        /// WebGL 빌드에서만 그려지지 않는다" 였는데, <b>2026-08-30 실측으로 그 서술이 틀렸음을
        /// 확인했다.</b> WebGL Development 빌드에서 병합을 켜자 병합체가 정상 생성되고
        /// (원본 12개 → 1개) 몸이 온전히 그려졌으며, 옷을 3회 갈아입은 뒤에도 목·귀·얼굴 피부가
        /// 유지됐다 — <b>목이 사라지는 것이 당시 증상의 핵심이었다.</b></para>
        ///
        /// <para>당시 증상은 두 결함이 겹친 것이었다 — ① 오염된 증분 빌드 캐시(같은 날 "월드 전체
        /// 투명" 의 원인, 클린 빌드로 해소) ② T-228 재조립 버그(병합이 원본을 끄고 다시 켜지 않아
        /// 두 번째 조립에서 그릴 것이 남지 않음, 2026-08-30 수정). <b>플랫폼 상성이라는 원인은
        /// 처음부터 없었다.</b></para>
        ///
        /// <para>⚠ 확인은 <b>Development 빌드</b>에서 했다. 릴리즈는 셰이더 스트리핑이 다르지만
        /// 병합은 <b>이미 쓰이는 재질을 그대로 재사용</b>하므로(새 <c>Shader.Find</c> 없음)
        /// T-213 류의 위험은 없다. 그래도 다음 릴리즈 빌드에서 한 번 확인한다.</para>
        /// </summary>
        public static bool DefaultEnabled => true;

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

        /// <summary>
        /// F10 토글을 **모든 씬에서** 쓸 수 있게 스스로 설치한다.
        ///
        /// 씬에 올려 두는 방식이면 그 씬에 없을 때 조용히 안 먹는다 — 실제로 그래서 로비에서
        /// 못 눌렀다. 검증 수단이 "배치를 빠뜨리면 죽는" 구조면 정작 필요할 때 없다.
        ///
        /// 개발 빌드·에디터에서만 설치한다. 릴리즈에서 실사용자가 누를 수 있으면 안 된다.
        /// </summary>
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void InstallToggle()
        {
            if (!Debug.isDebugBuild && !Application.isEditor) return;

            var go = new GameObject("@AvatarMeshMergeToggle");
            go.hideFlags = HideFlags.HideAndDontSave;
            Object.DontDestroyOnLoad(go);
            go.AddComponent<Toggle>();
            Debug.Log($"[AvatarMeshMerge] F10 토글 설치 — {StateLabel}");
        }

        /// <summary>F10 을 눌러 병합을 켜고 끈다. 반영은 <b>다음 조립부터</b>다(옷을 갈아입으면 된다).</summary>
        class Toggle : MonoBehaviour
        {
            void Update()
            {
                if (!Input.GetKeyDown(KeyCode.F10)) return;
                Enabled = !Enabled;
                // 로비에는 HUD 가 없으므로 로그로 남긴다 — 브라우저 콘솔에서 읽힌다.
                Debug.Log($"[AvatarMeshMerge] {StateLabel} — 옷을 갈아입어야 반영된다");
            }
        }
    }
}
