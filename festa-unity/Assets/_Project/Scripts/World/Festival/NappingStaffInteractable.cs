using Festa.Booth;
using Festa.Content;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 판넬 뒤 숨은 틈에서 자고 있는 직원 (S15P21A604-672).
    ///
    /// <para>거기까지 걸어 들어온 사람에게만 보이는 것이라 <b>네트워크를 타지 않는다.</b>
    /// 여러 명이 같은 상태를 보아야 할 이유가 없고, 동기화하면 계약과 서버 부하만 늘어난다.
    /// 각자의 화면에서 각자 깨우고 각자 5분을 센다.</para>
    ///
    /// <para><b>대사는 2D 토스트다.</b> 처음에는 월드 안 말풍선으로 만들었는데, 이 틈은 가용 폭이
    /// 10 u 뿐이라 판넬·기둥 모서리에 글자가 잘려 <b>무슨 말인지 읽을 수 없었다.</b> 위치·크기·
    /// 끌어내기를 세 번 바꿔도 마찬가지였다(2026-09-13 실측). 화면 평면에 그리면 지형과 무관하게
    /// 항상 온전히 보인다 — 대사 한 줄 때문에 공간과 싸울 이유가 없다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class NappingStaffInteractable : MonoBehaviour, IBoothInteractable
    {
        const string CaughtLine = "죄..죄송합니다. 얼른 들어가겠습니다.";

        [Tooltip("들킨 뒤 다시 돌아오기까지(초)")]
        [SerializeField] float _returnAfter = 300f;

        [Tooltip("대사를 띄워 두는 시간(초). 이 뒤에 사라진다")]
        [SerializeField] float _lineDuration = 2.6f;

        /// <summary>자는 포즈로 쓸 이모트. 인스펙터에서 바꿀 수 있게 둔다 — 클립마다 누운 방향이 다르다.</summary>
        [SerializeField] Festa.Network.PlayerEmoteId _sleepEmote = Festa.Network.PlayerEmoteId.LieSofa;

        Animator _animator;
        GameObject[] _visuals;
        float _hiddenUntil;
        float _lineUntil;
        bool _caught;

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();
            BoothInteractionInput.Ensure();

            _animator = GetComponentInChildren<Animator>();

            // 숨길 대상은 **자식 렌더러를 가진 오브젝트**다. 이 오브젝트 자체를 끄면 이 스크립트도 멈춰
            // 5분 뒤 복귀가 영영 오지 않는다 — 그래서 껍데기는 살려 두고 보이는 것만 끈다.
            var renderers = GetComponentsInChildren<Renderer>(true);
            _visuals = new GameObject[renderers.Length];
            for (int i = 0; i < renderers.Length; i++) _visuals[i] = renderers[i].gameObject;

            PlaySleep();
        }

        void PlaySleep()
        {
            if (_animator == null) return;
            var state = $"Emote_{_sleepEmote}";
            if (_animator.HasState(0, Animator.StringToHash(state))) _animator.CrossFadeInFixedTime(state, 0.25f, 0);
            else Debug.LogWarning($"[NappingStaff] 자는 포즈 상태를 찾지 못했다: {state}");
        }

        void SetVisible(bool on)
        {
            if (_visuals == null) return;
            for (int i = 0; i < _visuals.Length; i++)
                if (_visuals[i] != null) _visuals[i].SetActive(on);
            var target = GetComponent<BoothInteractionTarget>();
            if (target != null) target.enabled = on;   // 안 보이는 사람을 조준하지 않게
        }

        void LateUpdate()
        {
            if (!_caught) return;

            // 대사 시간이 지나면 사라지고, 그 뒤 5분이 지나면 돌아온다.
            if (_lineUntil > 0f && Time.time >= _lineUntil)
            {
                _lineUntil = 0f;
                SetVisible(false);
                _hiddenUntil = Time.time + _returnAfter;
            }
            else if (_hiddenUntil > 0f && Time.time >= _hiddenUntil)
            {
                _hiddenUntil = 0f;
                _caught = false;
                SetVisible(true);
                PlaySleep();
            }
        }

        public void Interact()
        {
            if (_caught) return;   // 이미 들켰다 — 두 번 깨우지 않는다
            _caught = true;
            _lineUntil = Time.time + _lineDuration;

            // 화면 평면에 띄운다 (위 클래스 주석 참조).
            BoothInteractionInput.Toast(CaughtLine, _lineDuration);

            // 일어나는 척 — 누운 이모트를 풀면 기본 자세로 돌아간다.
            if (_animator != null && _animator.HasState(0, Animator.StringToHash("Idle")))
                _animator.CrossFadeInFixedTime("Idle", 0.2f, 0);
        }
    }
}
