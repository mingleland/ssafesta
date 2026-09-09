using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// 호스트(React) ↔ Unity 소리 제어 경계.
    ///
    /// <para><b>무엇을 푸는가.</b> 로그인·로비 화면의 음소거 버튼은 FE 가 자기 오디오만 끈다.
    /// Unity 에는 그 사실을 전달할 통로가 없어서, 소리를 꺼 놓고 월드에 들어가면 BGM 이 다시 나왔다.
    /// 게다가 월드 안에는 끄는 방법이 아예 없어서 탭을 닫는 것 말고는 방법이 없었다 (2026-09-08 조사).</para>
    ///
    /// <para>사용법 — <see cref="AuthBridge"/>·<see cref="InputBridge"/> 와 같은 규약이다.
    /// <code>
    /// unityInstance.SendMessage('AudioBridge', 'SetMuted', '1');   // 음소거
    /// unityInstance.SendMessage('AudioBridge', 'SetMuted', '0');   // 해제
    /// unityInstance.SendMessage('AudioBridge', 'SetVolume', '0.4') // 0~1
    /// </code></para>
    ///
    /// <para><see cref="AudioListener"/> 하나로 Unity 전체를 덮는다. 개별 AudioSource 를 돌아다니며
    /// 끄지 않는 이유: 나중에 추가되는 소리를 빠뜨리게 되고, 원래 볼륨을 되돌릴 책임이 생긴다.
    /// 리스너 볼륨은 되돌릴 상태가 하나뿐이라 틀릴 여지가 없다.</para>
    ///
    /// <para>씬을 고치지 않는다 — 자동 등록 오브젝트다. 상태는 static 이라 씬 전환에도 유지된다.</para>
    /// </summary>
    public class AudioBridge : MonoBehaviour
    {
        public const string ObjectName = "AudioBridge";

        /// <summary>호스트가 지정한 볼륨(0~1). 음소거는 이 값과 별개로 유지된다 — 해제하면 원래 크기로 돌아온다.</summary>
        public static float HostVolume { get; private set; } = 1f;

        /// <summary>호스트가 음소거를 걸었는가.</summary>
        public static bool IsMuted { get; private set; }

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void AutoRegister()
        {
#if UNITY_SERVER
            return;
#else
            if (GameObject.Find(ObjectName) != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<AudioBridge>();
            DontDestroyOnLoad(go);
            Apply();
#endif
        }

        /// <summary>호스트 → Unity. "1"/"true" 음소거, 그 밖은 해제.</summary>
        public void SetMuted(string value)
        {
            bool muted = value == "1" || string.Equals(value, "true", System.StringComparison.OrdinalIgnoreCase);
            if (muted == IsMuted) return;
            IsMuted = muted;
            Apply();
            Debug.Log($"[AudioBridge] 소리 {(muted ? "음소거" : "해제")}");
        }

        /// <summary>호스트 → Unity. 0~1 문자열. 범위를 벗어나면 자른다.</summary>
        public void SetVolume(string value)
        {
            if (!float.TryParse(value, System.Globalization.NumberStyles.Float,
                                System.Globalization.CultureInfo.InvariantCulture, out var v))
            {
                Debug.LogWarning($"[AudioBridge] 볼륨 값을 읽지 못했다: '{value}'");
                return;
            }
            HostVolume = Mathf.Clamp01(v);
            Apply();
            Debug.Log($"[AudioBridge] 볼륨 {HostVolume:F2}");
        }

        static void Apply() => AudioListener.volume = IsMuted ? 0f : HostVolume;
    }
}
