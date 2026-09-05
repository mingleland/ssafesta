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
        // 게임 화면(제목·잭팟·릴, 세로 약 12 unit)만 거의 가득 차게 — 화면 중심(로컬 y≈1.74)에서 정면 12 unit(0.9 m). 사용자 지시 2026-09-06.
        [SerializeField] Vector3 _cameraLocal = new(0f, 1.90f, 1.10f);
        [SerializeField] Vector3 _lookLocal = new(0f, 1.86f, 0.12f);

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
