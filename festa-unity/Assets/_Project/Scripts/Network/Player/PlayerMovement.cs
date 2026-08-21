using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;
using Festa.World;

namespace Festa.Network
{
    /// <summary>
    /// Owner 전용 WASD/화살표 이동. CharacterController 로 월드 콜리전을 받는다.
    ///
    /// 이동은 client-authoritative 다 (<see cref="ClientAuthoritativeNetworkTransform"/>).
    /// 각 Owner 가 자기 클라이언트에서 충돌을 해결하고 결과 위치만 복제되므로,
    /// 월드 콜라이더는 각 클라이언트 씬에 있으면 된다 — 월드는 로컬 씬 오브젝트라 자동으로 충족된다.
    /// 서버는 이동을 검증하지 않는다.
    ///
    /// AnimState를 이동 여부에 따라 갱신해 원격 클라이언트가 Idle/Walk를 표현할 수 있게 한다.
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class PlayerMovement : NetworkBehaviour
    {
        [SerializeField] float _moveSpeed = 36f;
        [SerializeField] float _runSpeed = 52f;
        [SerializeField, Min(0.01f)] float _turnSmoothTime = 0.08f;
        [SerializeField, Min(1f)] float _maxTurnSpeedDeg = 1080f;

        [Header("물리")]
        [Tooltip("중력 가속도. 월드가 1 m = 10 unit 이므로 9.81 m/s² = 98.1 unit/s² 이다.")]
        [SerializeField] float _gravity = 98.1f;
        [Tooltip("접지 상태에서 유지하는 하강 속도 — 경사·계단에서 붙어 있게 한다.")]
        [SerializeField] float _groundedStick = -20f;

        // 점프 — 스페이스바. 도약 속도는 원하는 높이에서 역산한다.
        // h = v² / 2g 이므로 v = √(2gh). g=98.1, h=45(=4.5 m 는 과하다) 대신
        // h≈4.5 unit(0.45 m) 를 노려 v≈30 을 쓴다. 체공 시간 t = 2v/g ≈ 0.61초로
        // Jumping 클립(1.90초)보다 짧아 착지 시 로코모션으로 크로스페이드된다.
        [Tooltip("점프 초기 상승 속도 (unit/s). 1 m = 10 unit 이다.")]
        // 도약에는 **중력을 따로 쓴다.** 현실 중력(98.1 = 9.81 m/s² × 10)으로는 0.6 m 를
        // 뛰면 체공이 0.70초밖에 안 나와 눈으로 읽히기 전에 끝난다 — "점프가 너무 빠르다".
        // 체공을 늘리려면 현실 중력에서는 1 m 씩 뛰어야 하는데 로비 천장에 맞지 않는다.
        // 그래서 게임들이 하는 대로 도약 구간만 중력을 낮춘다. 낙하(발판에서 벗어남)는
        // 현실 중력을 그대로 쓴다.
        //
        // v = 26.5, g = 59 → 체공 0.898초, 도달 5.95 unit = 0.60 m.
        // 이 체공에 Jump 클립 재생 속도(0.705)를 맞춰 포물선과 포즈가 겹치게 했다.
        [SerializeField] float _jumpSpeed = 26.5f;
        [SerializeField] float _jumpGravity = 59f;

        // ── 스폰 위치 강제 ────────────────────────────────────────
        // 서버가 접속 승인에서 배정한 위치. 이동 권위가 Owner(클라이언트)에 있으므로
        // (<see cref="ClientAuthoritativeNetworkTransform"/>) 스폰 직후 경합에서 Owner 쪽
        // 초기 상태(프리팹 원위치 = 원점)가 이기면 서버 배정이 무시된다 — 원점은 방 밖
        // 허공이라 "대부분 허공에서 떨어지고 가끔만 맵에서 스폰" 이 된다 (T-177).
        // 권위자인 Owner 스스로 이 값으로 텔레포트하면 어떤 경합이 있어도 결정적이다.
        public NetworkVariable<Vector3> ServerSpawnPosition = new(
            Vector3.zero, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        const float SpawnWaitTimeout = 3f; // 값 미수신(버전 불일치 등) 시 현재 위치로 진행

        NetworkPlayer _player;
        PlayerCameraFollow _cameraFollow;
        CharacterController _controller;
        Unity.Netcode.Components.NetworkTransform _networkTransform;
        float _turnVelocity;
        float _verticalSpeed;
        bool _spawnPlaced;
        float _spawnWaitStart;
        bool _airborne;
        bool _jumped;
        float _airborneSince;
        const float AirborneAnimGrace = 0.12f;

        bool _jumpPending;
        float _jumpPressedAt;
        // Jump_Launch 클립(f7~f15 = 0.267초)을 speed 2.05 로 재생하는 시간.
        // 이 값이 그 재생 시간과 어긋나면 도약 순간이 다시 어긋난다 — 같이 바꿔야 한다.
        const float JumpAnticipation = 0.13f;

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<NetworkPlayer>();
            _cameraFollow = GetComponent<PlayerCameraFollow>();
            _controller = GetComponent<CharacterController>();
            _networkTransform = GetComponent<Unity.Netcode.Components.NetworkTransform>();
            enabled = IsOwner; // 원격 플레이어는 NetworkTransform 수신만

            // 원격 플레이어는 NetworkTransform 이 transform 을 직접 쓴다.
            // CharacterController 가 켜져 있으면 그 대입과 싸우므로 Owner 만 남긴다.
            if (_controller != null) _controller.enabled = IsOwner;

            if (IsServer)
                ServerSpawnPosition.Value = transform.position; // 승인 위치 그대로

            if (IsOwner)
            {
                _spawnWaitStart = Time.time;
                ServerSpawnPosition.OnValueChanged += OnServerSpawnPositionChanged;
                TryPlaceAtServerSpawn(); // 초기 동기화로 이미 와 있으면 즉시
            }
            else
            {
                _spawnPlaced = true; // 원격/서버 표현은 NetworkTransform 이 책임진다
            }
        }

        public override void OnNetworkDespawn()
        {
            if (IsOwner) ServerSpawnPosition.OnValueChanged -= OnServerSpawnPositionChanged;
        }

        void OnServerSpawnPositionChanged(Vector3 _, Vector3 next)
        {
            if (!_spawnPlaced) PlaceAt(next);
        }

        void TryPlaceAtServerSpawn()
        {
            var pos = ServerSpawnPosition.Value;
            if (pos == Vector3.zero) return; // 아직 미수신 — 실제 스폰 y 는 항상 0보다 크다
            PlaceAt(pos);
        }

        /// <summary>
        /// Owner 를 서버 배정 위치에 강제로 놓는다.
        /// CharacterController 는 켜진 상태의 transform 대입을 무시하므로 껐다 켠다.
        /// NetworkTransform.Teleport 로 원격에도 보간 없이 즉시 반영한다.
        /// </summary>
        void PlaceAt(Vector3 pos)
        {
            _spawnPlaced = true;

            float drift = Vector3.Distance(transform.position, pos);
            if (drift > 0.5f)
                Debug.LogWarning($"[PlayerMovement] 스폰 경합 감지 — Owner 가 {transform.position} 에 있었다. " +
                                 $"서버 배정 {pos} 로 강제 이동 (이탈 {drift:F1})");

            bool controllerWasEnabled = _controller != null && _controller.enabled;
            if (controllerWasEnabled) _controller.enabled = false;

            if (_networkTransform != null && _networkTransform.CanCommitToTransform)
                _networkTransform.Teleport(pos, transform.rotation, transform.localScale);
            else
                transform.position = pos;
            Physics.SyncTransforms();

            if (controllerWasEnabled) _controller.enabled = true;
            _verticalSpeed = 0f;
        }

        void Update()
        {
            if (!IsOwner) return;

            // 서버 배정 위치를 받기 전에는 움직이지 않는다 — 잘못된 자리에서
            // 중력으로 떨어지기 시작하면 배정 위치가 와도 이미 이탈해 있다.
            if (!_spawnPlaced)
            {
                TryPlaceAtServerSpawn();
                if (!_spawnPlaced && Time.time - _spawnWaitStart > SpawnWaitTimeout)
                {
                    Debug.LogWarning("[PlayerMovement] 서버 스폰 위치를 받지 못했다 — 현재 위치로 진행 " +
                                     "(서버/클라이언트 빌드 버전이 같은지 확인해라)");
                    _spawnPlaced = true;
                }
                if (!_spawnPlaced) return;
            }

            var input = ReadMoveInput();
            bool moving = input.sqrMagnitude > 0.0001f;
            bool running = moving && IsRunPressed();

            if (moving && _player.EmoteId.Value != PlayerEmoteId.None)
                _player.EmoteId.Value = PlayerEmoteId.None;

            // 중력은 정지 중에도 적용한다 — 그러지 않으면 발판에서 벗어나도 공중에 선다.
            bool grounded = false;
            if (_controller != null && _controller.enabled)
            {
                grounded = _controller.isGrounded;
                // 의도한 도약 중에는 낮춘 중력을 쓴다 (위 주석). 그냥 떨어지는 것은 현실 중력.
                float gravity = _jumped ? _jumpGravity : _gravity;
                _verticalSpeed = grounded
                    ? _groundedStick
                    : _verticalSpeed - gravity * Time.deltaTime;

                // 스페이스를 누르면 **애니메이션만 먼저** 시작하고 몸은 아직 바닥에 있다.
                //
                // 즉시 띄우면 안 된다. Animator 는 applyRootMotion = false 라 클립의 수직
                // 이동(RootT.y)을 버리므로, 화면에 보이는 것은 엉덩이 기준 다리 포즈뿐이다.
                // 도약 프레임부터 재생하면 시작 포즈가 "다리 펴고 선" 자세이고 다리 접기는
                // 0.1~0.3초 뒤에 나온다 — 몸이 먼저 올라가고 애니메이션이 뒤따르는 것으로
                // 보인다. 발 구르기(Jump_Launch)를 바닥에서 먼저 재생하고 그 뒤에 띄운다.
                //
                // 접지 상태에서만 시작한다 — 이중 점프를 만들지 않는다.
                if (grounded && !_jumpPending && IsJumpPressed())
                {
                    _jumpPending = true;
                    _jumpPressedAt = Time.time;
                }

                // 중력·접지 처리 **뒤에** 적용해야 _groundedStick 이 도약을 지우지 않는다.
                if (_jumpPending && Time.time - _jumpPressedAt >= JumpAnticipation)
                {
                    _verticalSpeed = _jumpSpeed;
                    _jumpPending = false;
                    _airborne = true;
                    _jumped = true;   // 의도한 도약은 유예 없이 즉시 포즈를 낸다
                    grounded = false;
                }
                else if (grounded)
                {
                    _airborne = false;
                    _jumped = false;
                }
                else
                {
                    // 발 구르는 중에 발판에서 벗어났다면 도약은 취소한다.
                    _jumpPending = false;
                    if (!_airborne) _airborneSince = Time.time;
                    _airborne = true;
                }
            }

            if (moving)
            {
                var dir = CameraRelativeDirection(input);
                var speed = running ? _runSpeed : _moveSpeed;
                MoveWithCollision(dir * speed);

                // 이동 벡터는 즉시 새 입력을 따르되, 보이는 방향만 짧게 보간한다.
                // 키를 바꿀 때 한 프레임 만에 각도가 튀는 현상을 없애면서도
                // 카메라 기준 WASD 조작 방향은 그대로 유지한다.
                var targetYaw = Mathf.Atan2(dir.x, dir.z) * Mathf.Rad2Deg;
                var nextYaw = Mathf.SmoothDampAngle(
                    transform.eulerAngles.y,
                    targetYaw,
                    ref _turnVelocity,
                    _turnSmoothTime,
                    _maxTurnSpeedDeg,
                    Time.deltaTime);
                transform.rotation = Quaternion.Euler(0f, nextYaw, 0f);
            }
            else
            {
                _turnVelocity = 0f;
                MoveWithCollision(Vector3.zero); // 정지 중에도 중력은 적용한다
            }

            // 공중에서는 이동 입력과 무관하게 Jump 를 보낸다 — 원격 클라이언트가
            // 같은 애니메이션을 재생한다 (AnimState 는 Owner 쓰기 권한이다).
            // 접지 판정은 바닥 이음새·경사에서 한두 프레임씩 끊긴다. 그때마다 점프
            // 포즈가 번쩍이지 않도록 **의도한 도약이 아니면** 짧은 유예를 둔다.
            bool showAirborne = _airborne &&
                (_jumped || Time.time - _airborneSince > AirborneAnimGrace);
            var next = _jumpPending
                ? PlayerAnimState.JumpLaunch
                : showAirborne
                ? PlayerAnimState.Jump
                : !moving ? PlayerAnimState.Idle
                : running ? PlayerAnimState.Run : PlayerAnimState.Walk;
            if (_player.AnimState.Value != next)
                _player.AnimState.Value = next;
        }

        /// <summary>
        /// 수평 속도에 중력을 더해 CharacterController 로 이동한다.
        /// CharacterController 가 없으면(구 프리팹·테스트 씬) 이전처럼 transform 을 직접 옮긴다 —
        /// 콜리전은 없지만 이동 자체는 멈추지 않게 한다.
        /// </summary>
        void MoveWithCollision(Vector3 horizontalVelocity)
        {
            if (_controller == null || !_controller.enabled)
            {
                if (horizontalVelocity != Vector3.zero)
                    transform.position += horizontalVelocity * Time.deltaTime;
                return;
            }

            var velocity = horizontalVelocity + Vector3.up * _verticalSpeed;
            _controller.Move(velocity * Time.deltaTime);
        }

        static Vector2 ReadMoveInput()
        {
            var kb = Keyboard.current;
            if (kb == null) return Vector2.zero;

            var v = Vector2.zero;
            if (kb.wKey.isPressed || kb.upArrowKey.isPressed) v.y += 1f;
            if (kb.sKey.isPressed || kb.downArrowKey.isPressed) v.y -= 1f;
            if (kb.dKey.isPressed || kb.rightArrowKey.isPressed) v.x += 1f;
            if (kb.aKey.isPressed || kb.leftArrowKey.isPressed) v.x -= 1f;
            return v;
        }

        static bool IsRunPressed()
        {
            var kb = Keyboard.current;
            return kb != null && (kb.leftShiftKey.isPressed || kb.rightShiftKey.isPressed);
        }

        /// <summary>
        /// 스페이스바가 이번 프레임에 눌렸는지. `isPressed` 가 아니라 `wasPressedThisFrame` 이다 —
        /// 누르고 있는 동안 매 프레임 도약하면 바닥에 닿을 때마다 튀어오른다.
        /// </summary>
        static bool IsJumpPressed()
        {
            var kb = Keyboard.current;
            return kb != null && kb.spaceKey.wasPressedThisFrame;
        }

        Vector3 CameraRelativeDirection(Vector2 input)
        {
            if (_cameraFollow != null &&
                _cameraFollow.TryGetPlanarBasis(out var forward, out var right))
            {
                return (forward * input.y + right * input.x).normalized;
            }

            // 카메라 Follow가 없는 테스트 프리팹에서만 전역 카메라를 폴백으로 쓴다.
            var camera = Camera.main;
            if (camera == null)
                return new Vector3(input.x, 0f, input.y).normalized;

            forward = Vector3.ProjectOnPlane(camera.transform.forward, Vector3.up);
            right = Vector3.ProjectOnPlane(camera.transform.right, Vector3.up);

            if (forward.sqrMagnitude < 0.0001f || right.sqrMagnitude < 0.0001f)
                return new Vector3(input.x, 0f, input.y).normalized;

            return (forward.normalized * input.y + right.normalized * input.x).normalized;
        }
    }
}
