using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// Stores the local player's selected appearance while moving between the
    /// character lobby and the networked world. This is a local handoff cache;
    /// the authoritative profile remains the backend when that API is enabled.
    /// </summary>
    public static class AvatarSceneHandoff
    {
        public const string LobbySceneName = "CharacterLobby";
        public const string WorldSceneName = "main";

        const string AppearanceKeyPrefix = "festa.avatar.scene-handoff";
        const string PendingConnectionKey = "festa.avatar.pending-world-connection";

        // Scene transitions happen within the same running client. Keep an in-memory
        // copy as the primary handoff so WebGL storage timing cannot replace the
        // newly selected modular appearance with an older fallback value.
        static string s_runtimeAppearance;

        /// <summary>
        /// 저장 키는 **로그인한 사람마다 다르다.**
        ///
        /// <para>예전에는 <c>festa.avatar.scene-handoff</c> 하나였다. PlayerPrefs 는 브라우저 오리진 단위로
        /// 남고 로그아웃해도 지워지지 않으므로, 교육장 공용 PC 에서 앞사람이 만든 외형을 뒷사람이 그대로
        /// 읽었다. 게다가 이 값이 있으면 로비가 <c>_restoredExistingAppearance</c> 를 세워 **서버 프로필을
        /// 아예 조회하지 않기 때문에**, 그대로 입장하면 앞사람 외형이 뒷사람 계정에 저장됐다 (2026-09-08).</para>
        ///
        /// <para>토큰이 없으면(미로그인) 접미사가 <c>.anon</c> 이다 — 로그인한 사람의 값과 절대 섞이지 않는다.</para>
        /// </summary>
        static string AppearanceKey
        {
            get
            {
                var subject = Festa.Integration.AuthBridge.SubjectKey;
                return string.IsNullOrEmpty(subject)
                    ? AppearanceKeyPrefix + ".anon"
                    : AppearanceKeyPrefix + "." + subject;
            }
        }

        public static void Save(AvatarAppearance appearance)
        {
            var encoded = appearance.Encode();
            if (string.IsNullOrEmpty(encoded) || encoded.Length > AvatarAppearance.MaxEncodedLength) return;

            s_runtimeAppearance = encoded;
            PlayerPrefs.SetString(AppearanceKey, encoded);
            PlayerPrefs.Save();
        }

        public static string GetEncodedOrFallback(string fallback)
        {
            if (!string.IsNullOrEmpty(s_runtimeAppearance) &&
                s_runtimeAppearance.Length <= AvatarAppearance.MaxEncodedLength)
            {
                return s_runtimeAppearance;
            }

            var encoded = PlayerPrefs.GetString(AppearanceKey, string.Empty);
            return !string.IsNullOrEmpty(encoded) && encoded.Length <= AvatarAppearance.MaxEncodedLength
                ? encoded
                : fallback;
        }

        /// <summary>
        /// 사용자가 바뀌었을 때 세션 안에 남은 메모리 사본을 버린다. 키가 사용자별이라 PlayerPrefs 는
        /// 이미 섞이지 않지만, <see cref="s_runtimeAppearance"/> 는 프로세스 전역이라 따로 비워야 한다.
        /// </summary>
        public static void ClearRuntime() => s_runtimeAppearance = null;

        /// <summary>
        /// 예전 버전이 사용자 구분 없이 남긴 값을 지운다. 남겨 두면 그 브라우저를 쓰는 다음 사람이
        /// 계속 읽을 수 있다 — 마이그레이션이라기보다 정리다.
        /// </summary>
        public static void PurgeLegacyKey()
        {
            if (!PlayerPrefs.HasKey(AppearanceKeyPrefix)) return;
            PlayerPrefs.DeleteKey(AppearanceKeyPrefix);
            PlayerPrefs.Save();
            Debug.Log("[AvatarSceneHandoff] 사용자 구분 없던 옛 핸드오프 키를 지웠다");
        }

        static string s_lastSubject;

        /// <summary>
        /// 부팅 때 한 번 건다. 계층을 지키려고 여기서 <c>AuthBridge</c> 를 구독한다 —
        /// <c>Festa.Integration</c> 이 <c>Festa.World</c> 를 알 이유는 없다.
        /// </summary>
        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Install()
        {
#if !UNITY_SERVER
            PurgeLegacyKey();
            s_lastSubject = Festa.Integration.AuthBridge.SubjectKey;
            Festa.Integration.AuthBridge.TokenChanged += OnTokenChanged;
#endif
        }

        static void OnTokenChanged()
        {
            var now = Festa.Integration.AuthBridge.SubjectKey;
            if (now == s_lastSubject) return;
            // 사람이 바뀌었다. 앞사람 외형이 뒷사람 계정으로 넘어가면 안 된다.
            s_lastSubject = now;
            ClearRuntime();
            Debug.Log("[AvatarSceneHandoff] 로그인 주체가 바뀌어 메모리 핸드오프를 비웠다");
        }

        public static void RequestWorldConnection()
        {
            PlayerPrefs.SetInt(PendingConnectionKey, 1);
            PlayerPrefs.Save();
        }

        public static bool ConsumeWorldConnectionRequest()
        {
            if (PlayerPrefs.GetInt(PendingConnectionKey, 0) == 0) return false;
            PlayerPrefs.DeleteKey(PendingConnectionKey);
            PlayerPrefs.Save();
            return true;
        }
    }
}
