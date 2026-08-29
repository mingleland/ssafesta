using UnityEngine;
using UnityEngine.Rendering;

namespace Festa.Core
{
    /// <summary>
    /// "이 프로세스는 화면을 갖고 있는가" 를 한 곳에서 판정한다 (S15P21A604-314).
    ///
    /// 왜 <c>#if UNITY_SERVER</c> 가 아닌가 — 그 심볼은 **빌드 타깃이 Dedicated Server 이면
    /// 에디터에도 정의된다.** 그것 때문에 이미 한 번 크게 헤맸다 (T-182): 심볼만 보고 분기했더니
    /// Play 를 누른 에디터가 서버가 돼버렸다. 컴파일 타임 심볼은 "지금 화면이 있는가" 를
    /// 말해주지 못한다.
    ///
    /// 왜 <c>Application.isBatchMode</c> 도 아닌가 — 배치 모드라고 그래픽 장치가 없는 것은 아니다
    /// (<c>-batchmode</c> 만 주고 <c>-nographics</c> 를 안 주면 장치가 살아 있다).
    ///
    /// 그래서 **그래픽 장치의 유무를 직접 본다.** 데디케이티드 서버 빌드와 <c>-nographics</c> 는
    /// 둘 다 <see cref="GraphicsDeviceType.Null"/> 이다. 머티리얼·셰이더를 만들지 말지의 기준으로는
    /// 이게 가장 정확하다 — 판단하려는 것이 정확히 "그릴 대상이 있는가" 이기 때문이다.
    /// </summary>
    public static class HeadlessRuntime
    {
        static int s_cached = -1;

        /// <summary>
        /// 화면이 없다면 true. 렌더링 자원(머티리얼·셰이더·폰트)을 만들지 않아야 한다.
        ///
        /// **콜라이더·트랜스폼까지 같이 끄면 안 된다** — 서버는 충돌·위치 권위를 갖는다.
        /// 이 플래그는 "그릴 것"에만 쓴다.
        /// </summary>
        public static bool IsHeadless
        {
            get
            {
                // 장치 종류는 프로세스 수명 동안 바뀌지 않는다. 매 오브젝트 생성마다
                // SystemInfo 를 두드릴 이유가 없다.
                if (s_cached < 0)
                    s_cached = SystemInfo.graphicsDeviceType == GraphicsDeviceType.Null ? 1 : 0;
                return s_cached == 1;
            }
        }
    }
}
