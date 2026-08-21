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
        static readonly int IsRunningHash = Animator.StringToHash("IsRunning");

        [SerializeField] AvatarCatalog _catalog;
        [SerializeField] Festa.Avatar.AvatarCatalog _modularCatalog;

        [Tooltip("외형이 붙을 위치. 비우면 자신의 Transform 사용")]
        [SerializeField] Transform _visualRoot;

        [Tooltip("렌더러 경계를 바닥에 맞추기 전에 적용할 모델 피벗 보정")]
        [SerializeField] Vector3 _visualOffset = new(0f, -0.75f, 0f);
        [SerializeField] float _visualScale = 1f;

        [Tooltip("월드에서 보일 아바타의 목표 높이. 0이면 원본 높이를 유지한다.")]
        [SerializeField, Min(0f)] float _targetVisualHeight = 17.9f;

        [Tooltip("발이 바닥에 묻히지 않도록 바닥에서 띄우는 최소 간격")]
        [SerializeField, Min(0f)] float _groundClearance = 0.03f;

        [Tooltip("아바타 발밑에 표시할 원형 접지 그림자의 가로/세로 크기")]
        [SerializeField] Vector2 _groundShadowSize = new(6.4f, 4.4f);

        [Tooltip("런타임 조립 아바타에 적용할 Animator Controller (프리팹 아바타는 자체 보유)")]
        [SerializeField] RuntimeAnimatorController _animatorController;

        NetworkPlayer _player;
        PlayerAppearanceController _appearance;
        IAvatarVisualProvider _provider;
        GameObject _currentVisual;
        GameObject _groundShadow;
        MeshRenderer _groundShadowRenderer;
        Material _groundShadowMaterial;
        Texture2D _groundShadowTexture;
        Animator _animator;
        string _appliedEncoded;

        public AvatarCatalog Catalog => _catalog;
        public Festa.Avatar.AvatarCatalog ModularCatalog => _modularCatalog;
        public Animator CurrentAnimator => _animator;

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
            _player.EmoteId.OnValueChanged += OnEmoteChanged;

            Rebuild(_appearance.Encoded.Value.ToString());
        }

        public override void OnNetworkDespawn()
        {
            _appearance.Encoded.OnValueChanged -= OnEncodedChanged;
            _player.AnimState.OnValueChanged -= OnAnimStateChanged;
            _player.EmoteId.OnValueChanged -= OnEmoteChanged;
            DestroyGroundShadow();
        }

        void OnEncodedChanged(FixedString4096Bytes _, FixedString4096Bytes next) => Rebuild(next.ToString());

        void OnAnimStateChanged(PlayerAnimState _, PlayerAnimState next) => ApplyAnimState(next);

        void OnEmoteChanged(PlayerEmoteId _, PlayerEmoteId next) => ApplyEmote(next);

        void Rebuild(string encoded)
        {
            if (string.IsNullOrEmpty(encoded)) return;

            if (_currentVisual != null) Destroy(_currentVisual);

            var appearance = AvatarAppearance.Decode(encoded);
            _currentVisual = _provider.CreateVisual(appearance, _visualRoot);
            _appliedEncoded = encoded;

            if (_currentVisual == null) return;

            FitVisualToWorld();
            ConfigureAvatarShadows();

            _animator = _currentVisual.GetComponentInChildren<Animator>();
            EnsureAnimatorController();

            ApplyAnimState(_player.AnimState.Value);
            ApplyEmote(_player.EmoteId.Value);
            Debug.Log($"[AvatarVisual] 적용 (owner={OwnerClientId}, modular={appearance.IsModular})");
        }

        void FitVisualToWorld()
        {
            var visualTransform = _currentVisual.transform;
            // 모델별 피벗 보정을 먼저 적용하고, 마지막에 실제 렌더러 최하단을
            // 바닥에 맞춘다. 바닥 정렬 뒤 Y 오프셋을 더하면 발이 다시 묻힌다.
            visualTransform.localPosition = _visualOffset;
            visualTransform.localScale = Vector3.one * _visualScale;

            if (_targetVisualHeight > 0f && TryGetRendererBounds(out var initialBounds))
            {
                var currentHeight = initialBounds.size.y;
                if (currentHeight > 0.001f)
                {
                    var heightScale = _targetVisualHeight / currentHeight;
                    visualTransform.localScale *= heightScale;
                }
            }

            // SkinnedMeshRenderer.bounds는 Sidekick 골격 전체 범위를 반환해
            // 실제 신발이 바닥 아래로 들어가도 감지하지 못한다. 현재 포즈의
            // 활성 스킨 메시를 한 번 베이크해 실제 보이는 최하단을 사용한다.
            if (TryGetVisibleGeometryBounds(out var fittedBounds) &&
                TryFindGroundHeight(out var groundY))
            {
                var desiredBottom = groundY + _groundClearance;
                visualTransform.position += Vector3.up * (desiredBottom - fittedBounds.min.y);
            }

            // 월드 플레이어 루트(스폰)는 Y=0을 유지하고, 조립된 외형만 Y=10에 둔다.
            // 바닥 탐색 성공 여부와 무관하게 항상 같은 높이가 적용되어야 한다.
            var position = visualTransform.localPosition;
            position.y = 10f;
            visualTransform.localPosition = position;

        }

        bool TryGetVisibleGeometryBounds(out Bounds bounds)
        {
            bounds = default;
            var found = false;

            foreach (var renderer in _currentVisual.GetComponentsInChildren<Renderer>(false))
            {
                if (renderer == null || !renderer.enabled || !renderer.gameObject.activeInHierarchy)
                    continue;

                var skinned = renderer as SkinnedMeshRenderer;
                if (skinned == null || skinned.sharedMesh == null)
                {
                    AddBounds(ref bounds, ref found, renderer.bounds);
                    continue;
                }

                var baked = new Mesh();
                skinned.BakeMesh(baked);
                var localBounds = baked.bounds;
                Destroy(baked);

                // BakeMesh 결과에는 SkinnedMeshRenderer의 스케일이 이미 적용된다.
                // TransformPoint를 쓰면 스케일이 한 번 더 곱해지므로 위치와 회전만 적용한다.
                var center = localBounds.center;
                var extents = localBounds.extents;
                for (var x = -1; x <= 1; x += 2)
                for (var y = -1; y <= 1; y += 2)
                for (var z = -1; z <= 1; z += 2)
                {
                    var localCorner = center + Vector3.Scale(extents, new Vector3(x, y, z));
                    var worldCorner = skinned.transform.position + skinned.transform.rotation * localCorner;
                    if (!found)
                    {
                        bounds = new Bounds(worldCorner, Vector3.zero);
                        found = true;
                    }
                    else
                    {
                        bounds.Encapsulate(worldCorner);
                    }
                }
            }

            return found;
        }

        bool TryFindGroundHeight(out float groundY)
        {
            groundY = 0f;
            var origin = _visualRoot.position + Vector3.up * 50f;
            var hits = Physics.RaycastAll(origin, Vector3.down, 100f, ~0, QueryTriggerInteraction.Ignore);
            var found = false;
            var bestY = float.NegativeInfinity;

            foreach (var hit in hits)
            {
                if (hit.transform == null || hit.transform.IsChildOf(transform))
                    continue;
                if (hit.point.y <= bestY)
                    continue;

                bestY = hit.point.y;
                found = true;
            }

            if (found) groundY = bestY;
            return found;
        }

        static void AddBounds(ref Bounds target, ref bool found, Bounds value)
        {
            if (!found)
            {
                target = value;
                found = true;
            }
            else
            {
                target.Encapsulate(value);
            }
        }

        void ConfigureAvatarShadows()
        {
            // 스킨 메시의 실시간 그림자는 큰 스케일의 모듈 골격 Bounds 때문에
            // 일부 파츠(특히 머리)만 남는 현상이 있다. 월드 아바타는 접지용
            // 원형 그림자 하나만 사용한다.
            foreach (var renderer in _currentVisual.GetComponentsInChildren<Renderer>(true))
            {
                renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            }

            // 외형 갱신 때 이전 그림자는 현재 외형의 자식이 아니므로 자동 삭제되지 않는다.
            // 새 그림자를 만들기 전에 정리해 플레이어당 하나만 유지한다.
            DestroyGroundShadow();

            // 바닥을 못 찾아도 **만들어 두고 숨긴다.** 예전에는 여기서 조기 반환했는데,
            // 스폰 직후 플레이어가 아직 방 밖(원점)에 있는 동안 이 함수가 돌면 아래에
            // 바닥이 없어 **그림자가 영구히 생기지 않았다** — 외형을 다시 바꿀 때까지
            // 복구되지 않는다 (T-183). 이제 `LateUpdate` 가 매 프레임 바닥을 다시 찾아
            // 위치를 맞추고 보이기/숨기기를 결정한다.
            bool groundFound = TryFindGroundHeight(out var groundY);

            var shadow = GameObject.CreatePrimitive(PrimitiveType.Quad);
            shadow.name = "AvatarGroundShadow";
            _groundShadow = shadow;
            var collider = shadow.GetComponent<Collider>();
            if (collider != null) Destroy(collider);

            shadow.transform.SetParent(transform, true);
            shadow.transform.position = new Vector3(transform.position.x, groundY + 0.025f, transform.position.z);
            shadow.transform.rotation = Quaternion.Euler(90f, 0f, 0f);
            shadow.transform.localScale = new Vector3(_groundShadowSize.x, _groundShadowSize.y, 1f);

            var texture = new Texture2D(64, 64, TextureFormat.RGBA32, false)
            {
                name = "AvatarGroundShadowTexture",
                wrapMode = TextureWrapMode.Clamp,
                filterMode = FilterMode.Bilinear
            };
            _groundShadowTexture = texture;
            for (var y = 0; y < texture.height; y++)
            for (var x = 0; x < texture.width; x++)
            {
                var uv = new Vector2(
                    (x + 0.5f) / texture.width * 2f - 1f,
                    (y + 0.5f) / texture.height * 2f - 1f);
                var alpha = Mathf.Clamp01(1f - uv.magnitude);
                alpha = alpha * alpha * 0.32f;
                texture.SetPixel(x, y, new Color(0.02f, 0.025f, 0.035f, alpha));
            }
            texture.Apply(false, true);

            var shader = Shader.Find("Sprites/Default");
            if (shader == null) shader = Shader.Find("Universal Render Pipeline/Unlit");
            if (shader == null) return;

            var material = new Material(shader)
            {
                name = "AvatarGroundShadowMaterial",
                mainTexture = texture,
                renderQueue = 3000
            };
            _groundShadowMaterial = material;
            var meshRenderer = shadow.GetComponent<MeshRenderer>();
            meshRenderer.sharedMaterial = material;
            meshRenderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            meshRenderer.receiveShadows = false;
            _groundShadowRenderer = meshRenderer;
            meshRenderer.enabled = groundFound;
        }

        /// <summary>
        /// 접지 그림자를 매 프레임 바닥에 붙인다.
        ///
        /// 생성 시점 한 번만 놓으면 두 가지가 깨진다 — 스폰 직후 방 밖에 있었으면 영구히
        /// 안 보이고(T-183), 층높이가 다른 곳(라운지 데크 0.32 단차 등)으로 가면 뜬다.
        /// 아바타당 레이캐스트 1회라 40명이어도 프레임당 40회로 무시할 수 있다.
        /// </summary>
        void LateUpdate()
        {
            if (_groundShadow == null || _groundShadowRenderer == null) return;

            if (!TryFindGroundHeight(out var groundY))
            {
                _groundShadowRenderer.enabled = false;   // 바닥이 없으면 숨긴다
                return;
            }

            _groundShadowRenderer.enabled = true;
            _groundShadow.transform.position = new Vector3(
                _visualRoot.position.x, groundY + 0.025f, _visualRoot.position.z);
            // 회전은 고정한다 — 부모(플레이어)가 돌아도 그림자는 바닥에 누워 있어야 한다.
            _groundShadow.transform.rotation = Quaternion.Euler(90f, 0f, 0f);
        }

        void DestroyGroundShadow()
        {
            var trackedShadow = _groundShadow;
            _groundShadowRenderer = null;
            if (_groundShadow != null)
            {
                Destroy(_groundShadow);
                _groundShadow = null;
            }

            // 이전 코드가 남긴 중복 그림자도 다음 외형 갱신 시 함께 제거한다.
            for (var i = transform.childCount - 1; i >= 0; i--)
            {
                var child = transform.GetChild(i);
                if (child != null && child.gameObject != trackedShadow && child.name == "AvatarGroundShadow")
                    Destroy(child.gameObject);
            }

            if (_groundShadowMaterial != null)
            {
                Destroy(_groundShadowMaterial);
                _groundShadowMaterial = null;
            }

            if (_groundShadowTexture != null)
            {
                Destroy(_groundShadowTexture);
                _groundShadowTexture = null;
            }
        }

        bool TryGetRendererBounds(out Bounds bounds)
        {
            var renderers = _currentVisual.GetComponentsInChildren<Renderer>(true);
            bounds = default;
            var found = false;

            foreach (var renderer in renderers)
            {
                if (renderer == null) continue;

                if (!found)
                {
                    bounds = renderer.bounds;
                    found = true;
                }
                else
                {
                    bounds.Encapsulate(renderer.bounds);
                }
            }

            return found;
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

            // 월드 이동과 방향은 PlayerMovement/NetworkTransform이 권한을 갖는다.
            // 클립의 루트 모션이 플레이어 루트나 시각 축을 다시 움직이지 않게 한다.
            _animator.applyRootMotion = false;

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

            // Jump 를 "걷는 중" 으로 신고하지 않는다. 이 컨트롤러에는 IsWalking 으로
            // 걸린 Idle↔Walk 트랜지션이 있어서, 제자리에서 뛸 때 Idle→Walk 가 동시에
            // 유효해진다. 지금은 크로스페이드가 진행 중인 트랜지션을 그래프가 끊지
            // 못해 문제되지 않지만, 상태를 거짓으로 알리는 것 자체가 함정이다.
            bool moving = state == PlayerAnimState.Walk || state == PlayerAnimState.Run;
            bool running = state == PlayerAnimState.Run;
            _animator.speed = 1f;
            foreach (var p in _animator.parameters)
            {
                if (p.nameHash == IsWalkingHash && p.type == AnimatorControllerParameterType.Bool)
                    _animator.SetBool(IsWalkingHash, moving);
                else if (p.nameHash == IsRunningHash && p.type == AnimatorControllerParameterType.Bool)
                    _animator.SetBool(IsRunningHash, running);
                else if (p.nameHash == SpeedHash && p.type == AnimatorControllerParameterType.Float)
                    _animator.SetFloat(SpeedHash, running ? 2f : moving ? 1f : 0f);
            }

            if (_player.EmoteId.Value == PlayerEmoteId.None)
                CrossFadeLocomotion(state);
        }

        void ApplyEmote(PlayerEmoteId emote)
        {
            if (_animator == null || _animator.runtimeAnimatorController == null) return;
            if (emote == PlayerEmoteId.None)
            {
                CrossFadeLocomotion(_player.AnimState.Value);
                return;
            }

            var stateName = $"Emote_{emote}";
            if (_animator.HasState(0, Animator.StringToHash(stateName)))
                _animator.CrossFadeInFixedTime(stateName, 0.2f, 0);
            else
                Debug.LogWarning($"[AvatarVisual] 감정표현 상태를 찾지 못했습니다: {stateName}");
        }

        // 나올 때의 블렌드 길이를 정하려면 직전 상태를 알아야 한다 (착지 처리).
        PlayerAnimState _lastLocomotion = PlayerAnimState.Idle;

        void CrossFadeLocomotion(PlayerAnimState state)
        {
            var stateName = state switch
            {
                PlayerAnimState.JumpLaunch => "Jump_Launch",
                PlayerAnimState.Jump => "Jump_Air",
                PlayerAnimState.Run => "Run",
                PlayerAnimState.Walk => "Walk",
                _ => "Idle",
            };
            // 들어갈 때: 점프는 짧아서 0.2초 블렌드면 도약 순간을 놓친다.
            // 나올 때: 착지를 짧게 끊으면 급정지처럼 보인다. Jump 클립의 무릎 접기
            // (착지 흡수) 앞부분이 블렌드 동안 재생되도록 길게 준다 — 별도 착지
            // 상태를 만들지 않고 클립이 이어 재생되는 것을 그대로 쓴다.
            // 도약은 **보여야 하는 순간**이라 거의 스냅으로 넣는다. Launch → Air 는
            // 인접한 프레임끼리라 짧게 섞어도 이어져 보인다.
            float fade = state == PlayerAnimState.JumpLaunch || state == PlayerAnimState.Jump
                ? 0.03f
                : _lastLocomotion == PlayerAnimState.Jump ? 0.15f
                : 0.2f;
            _lastLocomotion = state;
            _animator.CrossFadeInFixedTime(stateName, fade, 0);
        }
    }
}
