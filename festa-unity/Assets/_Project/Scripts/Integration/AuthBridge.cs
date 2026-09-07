using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// WebGL 호스트(React) → Unity Access Token 주입 경계.
    ///
    /// 호스트가 로그인 후 토큰을 밀어 넣는다:
    ///   unityInstance.SendMessage('AuthBridge', 'SetAccessToken', '&lt;access token&gt;')
    /// 로그아웃·만료 시:
    ///   unityInstance.SendMessage('AuthBridge', 'ClearAccessToken', '')
    ///
    /// **Refresh Token 은 절대 Unity 로 넘기지 않는다** (-75 작업 내용). 갱신은 호스트가 하고
    /// 새 Access Token 을 다시 SetAccessToken 으로 밀어 넣는다.
    ///
    /// 씬을 고치지 않는다 — 자동 등록 오브젝트라 CharacterLobby·main 양쪽에서 같은 이름으로 잡힌다.
    /// 토큰은 static 에 두어 씬 전환에도 유지된다.
    /// </summary>
    public class AuthBridge : MonoBehaviour
    {
        public const string ObjectName = "AuthBridge";

        static string s_accessToken;

        /// <summary>호스트가 토큰을 한 번이라도 밀어 넣었는지. 401 진단에 쓴다.</summary>
        public static bool HasToken => !string.IsNullOrEmpty(s_accessToken);

        internal static string CurrentToken => s_accessToken;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
            // Dedicated Server 는 호스트가 없어 브리지가 필요 없다.
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;

            var go = new GameObject(ObjectName);
            go.AddComponent<AuthBridge>();
            DontDestroyOnLoad(go);
#endif
        }

        /// <summary>호스트 → Unity. 로그인 직후·토큰 갱신 시 호출된다.</summary>
        public void SetAccessToken(string token)
        {
            if (string.IsNullOrWhiteSpace(token))
            {
                Debug.LogWarning("[AuthBridge] 빈 토큰이 들어와 무시한다 — 해제는 ClearAccessToken 을 쓴다");
                return;
            }

            s_accessToken = token.Trim();
            s_isGuest = ReadRoleIsGuest(s_accessToken);
            // 토큰 값은 절대 로그에 남기지 않는다. 길이만으로 주입 여부를 확인한다.
            Debug.Log($"[AuthBridge] Access Token 주입됨 (길이 {s_accessToken.Length}, guest={s_isGuest})");
            TokenChanged?.Invoke();
        }

        static bool s_isGuest;

        /// <summary>
        /// 주입된 토큰의 <c>role</c> 클레임이 GUEST 인가. 게스트는 커스터마이징 없이 바로 월드에 들어간다
        /// (S15P21A604-437). 토큰이 없으면 false — "없음" 은 게스트가 아니라 미로그인이다.
        /// </summary>
        public static bool IsGuest => HasToken && s_isGuest;

        /// <summary>토큰이 들어오거나 비워질 때. 로비가 이 시점에 게스트 판정을 다시 한다.</summary>
        public static event System.Action TokenChanged;

        /// <summary>
        /// JWT payload 의 <c>"role":"GUEST"</c> 를 문자열로만 찾는다. 서명은 검증하지 않는다 —
        /// 이 값으로 권한을 열지 않고 **UI 흐름만** 고르기 때문이다(권한 판정은 전부 서버·게임 서버).
        /// </summary>
        static bool ReadRoleIsGuest(string jwt)
        {
            try
            {
                var parts = jwt.Split('.');
                if (parts.Length < 2) return false;
                var b64 = parts[1].Replace('-', '+').Replace('_', '/');
                switch (b64.Length % 4) { case 2: b64 += "=="; break; case 3: b64 += "="; break; }
                var json = System.Text.Encoding.UTF8.GetString(System.Convert.FromBase64String(b64));
                return json.Contains("\"role\":\"GUEST\"") || json.Contains("\"role\": \"GUEST\"");
            }
            catch (System.Exception)
            {
                return false;
            }
        }

        /// <summary>호스트 → Unity. 로그아웃·만료 시 호출된다.</summary>
        public void ClearAccessToken(string _ = null)
        {
            s_accessToken = null;
            s_isGuest = false;
            Debug.Log("[AuthBridge] Access Token 해제됨");
            TokenChanged?.Invoke();
        }
    }

    /// <summary>
    /// WebGL 호스트가 주입한 토큰을 읽는 provider.
    /// 토큰이 없으면 null 을 돌려주고, HttpUserApiClient 가 401 을 그대로 드러낸다 —
    /// 여기서 Mock 토큰으로 대체하면 인증 실패가 조용히 묻힌다 (T-24 원칙).
    /// </summary>
    public class HostAccessTokenProvider : IAccessTokenProvider
    {
        public string GetAccessToken() => AuthBridge.CurrentToken;
    }
}
