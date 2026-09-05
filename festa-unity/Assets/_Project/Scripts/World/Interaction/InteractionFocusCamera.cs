using Festa.Integration;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World
{
    /// <summary>
    /// 상호작용 대상으로 카메라를 끌어당기는 <b>초점 모드</b> (S15P21A604-439·-440).
    ///
    /// <para>slot machine·게임기처럼 "화면을 들여다보는" 상호작용은 3인칭 추적 카메라 그대로면 대상이
    /// 작게 보이고 캐릭터가 화면을 가린다. 초점 모드는 로컬 플레이어의 <see cref="PlayerCameraFollow"/> 를
    /// 잠시 끄고 메인 카메라를 대상 앞의 정해진 자리로 보간해 옮긴다. 그 동안 월드 입력은
    /// <see cref="InputBridge"/> 로 잠근다 — 캐릭터가 뛰어다니며 상호작용 프롬프트가 겹치는 일(-437)이 없다.</para>
    ///
    /// <para><b>갇히지 않는다.</b> Esc 로 언제든 나가고, 호스트(React)가 오버레이를 닫으며
    /// <c>SetInputLocked('0')</c> 을 보내면 그때도 풀린다. 어느 쪽이든 <see cref="Released"/> 가 한 번 발생한다.</para>
    ///
    /// <para>씬을 고치지 않는다 — 처음 쓸 때 자동 생성되는 단일 인스턴스다.</para>
    /// </summary>
    public sealed class InteractionFocusCamera : MonoBehaviour
    {
        static InteractionFocusCamera s_instance;

        /// <summary>초점 모드가 끝났다(Esc·외부 잠금 해제·명시적 Release). 어떤 이유든 한 번만.</summary>
        public static event System.Action Released;

        public static bool IsFocused => s_instance != null && s_instance._active;

        [SerializeField] float _lerp = 6f;

        Camera _cam;
        PlayerCameraFollow _follow;
        Vector3 _targetPos;
        Quaternion _targetRot;
        bool _active;
        bool _lockedByUs;
        bool _releasing;

        /// <summary>
        /// 대상 앞으로 카메라를 옮긴다. <paramref name="cameraLocal"/>·<paramref name="lookLocal"/> 은
        /// <paramref name="anchor"/> 로컬 좌표(월드 단위 — 1 m ≈ 13.26).
        /// 이미 초점 중이면 대상만 바꾼다.
        /// </summary>
        public static void Focus(Transform anchor, Vector3 cameraLocal, Vector3 lookLocal, bool lockInput = true)
        {
            if (anchor == null) return;
            var inst = Ensure();
            inst.BeginFocus(anchor, cameraLocal, lookLocal, lockInput);
        }

        /// <summary>초점 모드를 끝내고 추적 카메라·입력을 되돌린다. 초점 중이 아니면 아무 일도 없다.</summary>
        public static void Release()
        {
            if (s_instance == null) return;
            s_instance.EndFocus();
        }

        static InteractionFocusCamera Ensure()
        {
            if (s_instance != null) return s_instance;
            var go = new GameObject("@InteractionFocusCamera");
            s_instance = go.AddComponent<InteractionFocusCamera>();
            return s_instance;
        }

        void OnEnable() => InputBridge.LockedChanged += OnLockedChanged;
        void OnDisable() => InputBridge.LockedChanged -= OnLockedChanged;
        void OnDestroy() { if (s_instance == this) s_instance = null; }

        void BeginFocus(Transform anchor, Vector3 cameraLocal, Vector3 lookLocal, bool lockInput)
        {
            _cam = Camera.main;
            if (_cam == null)
            {
                Debug.LogError("[InteractionFocusCamera] Camera.main 이 없어 초점 모드를 열 수 없다.");
                return;
            }

            _targetPos = anchor.TransformPoint(cameraLocal);
            var lookAt = anchor.TransformPoint(lookLocal);
            _targetRot = Quaternion.LookRotation(lookAt - _targetPos, Vector3.up);

            if (!_active)
            {
                _follow = FindLocalFollow();
                if (_follow != null) _follow.enabled = false;
                _active = true;

                if (lockInput && !InputBridge.IsLocked)
                {
                    _lockedByUs = true;
                    InputBridge.SetLocked(true);
                }
                Debug.Log($"[InteractionFocusCamera] 초점 시작 → {anchor.name}");
            }
        }

        void EndFocus()
        {
            if (!_active || _releasing) return;
            _releasing = true;
            _active = false;

            if (_follow != null) _follow.enabled = true;   // 다음 LateUpdate 부터 추적 카메라가 보간으로 되돌아간다
            _follow = null;

            if (_lockedByUs)
            {
                _lockedByUs = false;
                InputBridge.SetLocked(false);   // LockedChanged(false) 가 다시 들어오지만 _active 가 false 라 무시된다
            }

            Debug.Log("[InteractionFocusCamera] 초점 해제");
            _releasing = false;
            Released?.Invoke();
        }

        void OnLockedChanged(bool locked)
        {
            // 호스트가 오버레이를 닫으며 잠금을 풀었다 — 초점도 함께 끝낸다.
            if (!locked && _active) EndFocus();
        }

        void LateUpdate()
        {
            if (!_active || _cam == null) return;

            var kb = Keyboard.current;
            if (kb != null && kb.escapeKey.wasPressedThisFrame)
            {
                EndFocus();
                return;
            }

            float t = 1f - Mathf.Exp(-_lerp * Time.deltaTime);
            var ct = _cam.transform;
            ct.position = Vector3.Lerp(ct.position, _targetPos, t);
            ct.rotation = Quaternion.Slerp(ct.rotation, _targetRot, t);
        }

        static PlayerCameraFollow FindLocalFollow()
        {
            // 소유자만 enabled 라(OnNetworkSpawn) 활성 컴포넌트가 곧 로컬 플레이어의 것이다.
            foreach (var f in FindObjectsByType<PlayerCameraFollow>(FindObjectsSortMode.None))
                if (f.enabled) return f;
            return null;
        }
    }
}
