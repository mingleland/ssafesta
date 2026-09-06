using System.Runtime.InteropServices;
using System.Text;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 호스트(React)가 런타임에 주입한 설정을 읽는다 — 현재는 API base URL 하나 (S15P21A604-459).
    ///
    /// <para><b>왜 필요한가.</b> 지금까지 Spring URL 은 <see cref="ApiConfig"/> 의 활성 환경에서 **빌드 타임**에 정해졌다.
    /// 그래서 릴리스 WebGL 산출물은 `https://api.ssafesta.world` 가 박힌 채 나오고, 그 호스트가 아직 없는 로컬에서는
    /// 같은 산출물을 검증할 수 없다 — 배포 직전에야 처음 돌려 보게 되고, URL 이 바뀌면 40분짜리 재빌드가 필요하다
    /// (2026-09-06 실측).</para>
    ///
    /// <para>FE 는 이미 같은 문제를 <c>window.__FESTA_CONFIG__</c>(컨테이너 entrypoint 가 <c>/runtime-config.js</c> 를 다시 써서 채운다)
    /// 로 풀었다 — `festa-frontend/src/shared/config/runtime.ts`. Unity 도 **같은 값**을 읽으면 하나의 산출물이 local·dev·demo 에서 뜬다.
    /// 값이 없으면(개발 서버·probe.html·단독 실행) 종전대로 빌드 타임 값으로 내려간다 — 회귀가 없다.</para>
    ///
    /// <para>문자열은 호출자가 준 버퍼에 UTF-8 로 채워 받는다. jslib 에서 <c>_malloc</c> 한 포인터를 돌려주면
    /// C# 쪽에서 해제 책임이 생기는데, 그 규약을 한 곳에서만 쓰자고 두기에는 사고가 나기 쉽다.</para>
    /// </summary>
    public static class HostRuntimeConfig
    {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern int FestaHostApiBaseUrl(byte[] buffer, int bufferLength);
#endif

        const int MaxUrlBytes = 512;

        static bool s_read;
        static string s_apiBaseUrl;

        /// <summary>
        /// 호스트가 준 API base URL. 주입이 없으면 <c>null</c> — 호출자는 빌드 타임 값을 쓴다.
        /// 한 번만 읽고 캐시한다(호스트가 로드 뒤에 값을 바꾸지 않는다).
        /// </summary>
        public static string ApiBaseUrl
        {
            get
            {
                if (s_read) return s_apiBaseUrl;
                s_read = true;
                s_apiBaseUrl = Read();
                return s_apiBaseUrl;
            }
        }

        static string Read()
        {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try
            {
                var buffer = new byte[MaxUrlBytes];
                int written = FestaHostApiBaseUrl(buffer, buffer.Length);
                if (written <= 0) return null;
                if (written > buffer.Length) written = buffer.Length;
                var url = Encoding.UTF8.GetString(buffer, 0, written).Trim();
                return string.IsNullOrEmpty(url) ? null : url.TrimEnd('/');
            }
            catch (System.Exception ex)
            {
                // 주입을 못 읽는 것이 기동을 막으면 안 된다 — 빌드 타임 값으로 간다.
                Debug.LogWarning($"[HostRuntimeConfig] apiBaseUrl 주입을 읽지 못했다: {ex.Message}");
                return null;
            }
#else
            return null;
#endif
        }
    }
}
