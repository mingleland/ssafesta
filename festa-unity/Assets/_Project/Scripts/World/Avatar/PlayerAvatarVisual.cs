using Festa.Network;
using Unity.Collections;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// PlayerAppearanceController의 동기화 문자열을 구독해 외형을 생성한다.
    /// NetworkPlayer는 수정하지 않는다(기준선 보존).
    ///
    /// 외형은 순수 로컬 오브젝트다 — NetworkObject를 붙이지 않는다.
    /// 문자열 하나만 동기화되고 각 클라이언트가 스스로 만든다 (Booth Runtime과 같은 원칙).
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    [RequireComponent(typeof(PlayerAppearanceController))]
    public class PlayerAvatarVisual : NetworkBehaviour
    {
        static readonly int SpeedHash = Animator.StringToHash("Speed");
        static readonly int IsWalkingHash = Animator.StringToHash("IsWalking");

        [SerializeField] AvatarCatalog _catalog;
        [SerializeField] Festa.Avatar.AvatarCatalog _modularCatalog;

        [Tooltip("외형이 붙을 위치. 비우면 자신의 Transform 사용")]
        [SerializeField] Transform _visualRoot;

        [Tooltip("모델 피벗 보정 (캡슐 중심 기준이면 Y=-1)")]
        [SerializeField] Vector3 _visualOffset = new(0f, -1f, 0f);
        [SerializeField] float _visualScale = 1f;

        [Tooltip("런타임 조립 아바타에 적용할 Animator Controller (프리팹 아바타는 자체 보유)")]
        [SerializeField] RuntimeAnimatorController _animatorController;

        NetworkPlayer _player;
        PlayerAppearanceController _appearance;
        IAvatarVisualProvider _provider;
        GameObject _currentVisual;
        Animator _animator;
        string _appliedEncoded;

        public AvatarCatalog Catalog => _catalog;
        public Festa.Avatar.AvatarCatalog ModularCatalog => _modularCatalog;

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            _appearance = GetComponent<PlayerAppearanceController>();
            _provider = new CatalogAvatarVisualProvider(_catalog, _modularCatalog);
            if (_visualRoot == null) _visualRoot = transform;
        }

        public override void OnNetworkSpawn()
        {
            _appearance.Encoded.OnValueChanged += OnEncodedChanged;
            _player.AnimState.OnValueChanged += OnAnimStateChanged;

            Rebuild(_appearance.Encoded.Value.ToString());
        }

        public override void OnNetworkDespawn()
        {
            _appearance.Encoded.OnValueChanged -= OnEncodedChanged;
            _player.AnimState.OnValueChanged -= OnAnimStateChanged;
        }

        void OnEncodedChanged(FixedString4096Bytes _, FixedString4096Bytes next) => Rebuild(next.ToString());

        void OnAnimStateChanged(PlayerAnimState _, PlayerAnimState next) => ApplyAnimState(next);

        void Rebuild(string encoded)
        {
            if (string.IsNullOrEmpty(encoded)) return;

            if (_currentVisual != null) Destroy(_currentVisual);

            var appearance = AvatarAppearance.Decode(encoded);
            _currentVisual = _provider.CreateVisual(appearance, _visualRoot);
            _appliedEncoded = encoded;

            if (_currentVisual == null) return;

            _currentVisual.transform.localPosition = _visualOffset;
            _currentVisual.transform.localScale = Vector3.one * _visualScale;

            _animator = _currentVisual.GetComponentInChildren<Animator>();
            EnsureAnimatorController();

            ApplyAnimState(_player.AnimState.Value);
            Debug.Log($"[AvatarVisual] 적용 (owner={OwnerClientId}, modular={appearance.IsModular})");
        }

        /// <summary>
        /// 런타임 조립 아바타는 Sidekick이 Animator를 붙여주지만 **Controller는 비워둔 채로 준다**
        /// (SidekickRuntime 생성자에 animationController를 null로 넘기기 때문).
        /// Controller가 없으면 애니메이션이 하나도 재생되지 않아 T포즈로 굳는다 (T-27).
        ///
        /// 인스펙터 슬롯이 비어 있어도 동작하도록, 루트 Animator의 Controller를 폴백으로 쓴다.
        /// </summary>
        void EnsureAnimatorController()
        {
            if (_animator == null)
            {
                Debug.LogWarning("[AvatarVisual] 생성된 외형에 Animator가 없습니다 — 애니메이션 재생 불가");
                return;
            }

            if (_animator.runtimeAnimatorController != null) return;

            var controller = _animatorController;

            if (controller == null)
            {
                // Player 루트에 이미 설정해 둔 Animator가 있으면 그 Controller를 그대로 사용
                var rootAnimator = GetComponent<Animator>();
                if (rootAnimator != null) controller = rootAnimator.runtimeAnimatorController;
            }

            if (controller == null)
            {
                Debug.LogError(
                    "[AvatarVisual] Animator Controller를 찾지 못했습니다 — 캐릭터가 T포즈가 됩니다. " +
                    "Player 프리팹의 PlayerAvatarVisual > 'Animator Controller' 슬롯에 AvatarAnimator를 넣으세요.");
                return;
            }

            _animator.runtimeAnimatorController = controller;
            _animator.Rebind();

            if (_animator.avatar == null)
                Debug.LogWarning("[AvatarVisual] Animator에 Avatar(휴머노이드 정의)가 없습니다 — " +
                                 "Sidekick 기본 모델의 Rig가 Humanoid인지 확인하세요");
        }

        void ApplyAnimState(PlayerAnimState state)
        {
            if (_animator == null || _animator.runtimeAnimatorController == null) return;

            bool walking = state == PlayerAnimState.Walk;
            foreach (var p in _animator.parameters)
            {
                if (p.nameHash == IsWalkingHash && p.type == AnimatorControllerParameterType.Bool)
                    _animator.SetBool(IsWalkingHash, walking);
                else if (p.nameHash == SpeedHash && p.type == AnimatorControllerParameterType.Float)
                    _animator.SetFloat(SpeedHash, walking ? 1f : 0f);
            }
        }
    }
}
