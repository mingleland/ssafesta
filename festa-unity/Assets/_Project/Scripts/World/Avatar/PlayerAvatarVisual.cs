using Festa.Network;
using Unity.Collections;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// NetworkPlayer의 avatarCode를 구독해 외형을 붙인다.
    /// Player Prefab에 이 컴포넌트를 추가하고 Catalog를 할당하면 되며,
    /// NetworkPlayer 자체는 수정하지 않는다 (기준선 보존).
    ///
    /// 외형은 순수 로컬 오브젝트다 — NetworkObject를 붙이지 않는다.
    /// avatarCode만 동기화되고 각 클라이언트가 스스로 생성한다 (Booth Runtime과 같은 원칙).
    /// Animator는 AnimState를 구독해 Idle/Walk를 전환한다.
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class PlayerAvatarVisual : NetworkBehaviour
    {
        static readonly int SpeedHash = Animator.StringToHash("Speed");
        static readonly int IsWalkingHash = Animator.StringToHash("IsWalking");

        [SerializeField] AvatarCatalog _catalog;

        [Tooltip("외형이 붙을 위치. 비우면 자신의 Transform 사용")]
        [SerializeField] Transform _visualRoot;

        [Tooltip("모델 피벗 보정 (캡슐 중심 기준이면 -1 정도)")]
        [SerializeField] Vector3 _visualOffset = new(0f, -1f, 0f);
        [SerializeField] float _visualScale = 1f;

        NetworkPlayer _player;
        IAvatarVisualProvider _provider;
        GameObject _currentVisual;
        Animator _animator;

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            _provider = new CatalogAvatarVisualProvider(_catalog);
            if (_visualRoot == null) _visualRoot = transform;
        }

        public override void OnNetworkSpawn()
        {
            _player.AvatarCode.OnValueChanged += OnAvatarCodeChanged;
            _player.AnimState.OnValueChanged += OnAnimStateChanged;
            Rebuild(_player.AvatarCode.Value.ToString());
        }

        public override void OnNetworkDespawn()
        {
            _player.AvatarCode.OnValueChanged -= OnAvatarCodeChanged;
            _player.AnimState.OnValueChanged -= OnAnimStateChanged;
        }

        void OnAvatarCodeChanged(FixedString32Bytes _, FixedString32Bytes next) => Rebuild(next.ToString());

        void OnAnimStateChanged(PlayerAnimState _, PlayerAnimState next) => ApplyAnimState(next);

        void Rebuild(string avatarCode)
        {
            if (_currentVisual != null) Destroy(_currentVisual);

            _currentVisual = _provider.CreateVisual(avatarCode, _visualRoot);
            if (_currentVisual == null) return;

            _currentVisual.transform.localPosition = _visualOffset;
            _currentVisual.transform.localScale = Vector3.one * _visualScale;

            _animator = _currentVisual.GetComponentInChildren<Animator>();
            ApplyAnimState(_player.AnimState.Value);

            Debug.Log($"[AvatarVisual] '{avatarCode}' 적용 (owner={OwnerClientId})");
        }

        /// <summary>
        /// Animator Controller가 없거나 파라미터가 없어도 안전하게 동작한다
        /// (컨트롤러 제작 전에도 외형만 먼저 검증 가능).
        /// </summary>
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
