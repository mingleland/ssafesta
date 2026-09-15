using Festa.Booth;
using Festa.Content;
using Festa.Integration;
using Festa.World;
using ithappy.Casino;
using UnityEngine;

namespace Festa.Minigame.Slot
{
    /// <summary>
    /// 광장 slot machine 에 F 상호작용을 붙인다 (S15P21A604-439).
    ///
    /// <para>F → 카메라가 기계 앞으로 zoom-in(<see cref="InteractionFocusCamera"/>, 입력 잠금) → HUD 에서 10코인 베팅.
    /// Esc 로 나가면 카메라·입력이 돌아온다. 벤더 <see cref="PresetUVSlotMachine"/> 은 공개 API 로만 쓴다.</para>
    ///
    /// <para><b>유휴 연출.</b> 벤더의 autoPlay 는 씬에서 꺼 두고(플레이 중 끼어들어 결과 연출과 섞이므로) 이 컴포넌트가
    /// 아무도 쓰지 않을 때만 6~10초 간격으로 <c>SpinRandom()</c> 을 불러 광장이 살아 있게 한다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class SlotMachineInteractable : MonoBehaviour, IBoothInteractable
    {
        [Header("식별")]
        [Tooltip("서버 판정에 실어 보내는 기계 id. 씬에서 유일해야 한다.")]
        [SerializeField] string _machineId = "plaza-slot-01";

        [Header("초점 카메라 (기계 로컬 좌표 — 스케일 포함)")]
        // **릴을 본다.** 실측: 릴 4개(Slot_Machine_01_Spin_01..04)의 중심이 로컬 (0, 1.44, 0.12) 이고
        // 블록 크기가 가로 0.51 · 세로 0.41 이다. 전에는 y 1.80 을 봤는데 그건 릴이 아니라 **위쪽 잭팟 판**이라,
        // 상호작용하면 돌아가는 릴이 화면 아래로 밀려났다 (2026-09-10 사용자 지적, 두 번째).
        // 카메라 z 0.72 → 릴 앞면(z≈0.19)에서 0.53 → 세로 화각 60° 기준 릴이 화면의 약 2/3 를 채운다.
        [SerializeField] Vector3 _cameraLocal = new(0f, 1.45f, 0.72f);
        [SerializeField] Vector3 _lookLocal = new(0f, 1.44f, 0.12f);

        [Header("릴 연출 프리셋 (PresetUVSlotMachine.presets 인덱스)")]
        [Tooltip("약한 당첨 → 강한 당첨 순. tier 1..N 에 대응.")]
        [SerializeField] int[] _winPresetIndices = { 2, 1, 0 };
        [SerializeField] int[] _losePresetIndices = { 3, 4, 5, 6, 7 };

        [Header("유휴 연출")]
        [SerializeField] bool _attractSpins = true;
        [SerializeField] Vector2 _attractIntervalSeconds = new(6f, 10f);

        PresetUVSlotMachine _reels;
        SlotMachineSession _session;
        float _nextAttractAt;

        public string MachineId => _machineId;

        void Awake()
        {
            _reels = GetComponent<PresetUVSlotMachine>();
            if (_reels == null)
                Debug.LogWarning($"[SlotMachineInteractable] {name} 에 PresetUVSlotMachine 이 없다 — 릴 연출 없이 판정만 한다.");

            if (GetComponentsInChildren<Collider>(true).Length == 0)
                gameObject.AddComponent<BoxCollider>();

            if (GetComponent<BoothInteractionTarget>() == null)
                gameObject.AddComponent<BoothInteractionTarget>();

            BoothInteractionInput.Ensure();
            _nextAttractAt = Time.time + Random.Range(_attractIntervalSeconds.x, _attractIntervalSeconds.y);
        }

        void Update()
        {
            if (!_attractSpins || _reels == null || _session != null) return;
            if (Time.time < _nextAttractAt) return;
            _nextAttractAt = Time.time + Random.Range(_attractIntervalSeconds.x, _attractIntervalSeconds.y);
            if (!_reels.IsSpinning) _reels.SpinRandom();
        }

        public void Interact()
        {
            if (_session != null || SlotMachineHud.IsOpen) return;

            ApiServices.EnsureInitialized();
            _session = new SlotMachineSession(ApiServices.Slot, ApiServices.Wallet, _machineId,
                                              _reels, _winPresetIndices, _losePresetIndices);

            InteractionFocusCamera.Focus(transform, _cameraLocal, _lookLocal);
            SlotMachineHud.Open(_session, OnHudClosed);
            _session.LoadBalance();
        }

        void OnHudClosed()
        {
            _session?.Dispose();
            _session = null;
            InteractionFocusCamera.Release();
            _nextAttractAt = Time.time + Random.Range(_attractIntervalSeconds.x, _attractIntervalSeconds.y);
        }

        void OnDestroy()
        {
            if (_session != null) OnHudClosed();
        }
    }
}
