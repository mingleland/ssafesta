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
        // 속도는 아바타 스케일과 한 몸이다 — 2026-08-24 실측 스케일업(×1.25, 기준: 11층
        // 실측 인체 모델 22.4 unit)에 맞춰 36/52 에서 함께 올렸다. 모델이 커지면 보폭도
        // 커지므로 속도를 같은 비율로 올려야 발이 미끄러지지 않는다.
        [SerializeField] float _moveSpeed = 45f;
        [SerializeField] float _runSpeed = 65f;
        [SerializeField, Min(0.01f)] float _turnSmoothTime = 0.08f;
        [SerializeField, Min(1f)] float _maxTurnSpeedDeg = 1080f;

        [Header("물리")]
        [Tooltip("중력 가속도. 월드가 1 m = 10 unit 이므로 9.81 m/s² = 98.1 unit/s² 이다.")]
        [SerializeField] float _gravity = 98.1f;
        [Tooltip("접지 상태에서 유지하는 하강 속도 — 경사·계단에서 붙어 있게 한다.")]
        [SerializeField] float _groundedStick = -25f;

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
        // v = 40.2, g = 90 → 체공 0.893초, 도달 8.98 unit = 0.90 m.
        // 0.60 m 는 발 구르기 웅크림(0.34 m)에 먹혀 낮아 보였다. 체공은 그대로 두고
        // 높이만 1.5배 올렸다 — 애니메이션 재생 속도(0.709)를 건드리지 않아도 된다.
        //
        // 2026-08-24 아바타 스케일업(×1.25)에 맞춰 v·g 를 같은 비율로 올렸다:
        // v = 50.25, g = 112.5 → 체공 0.893초, 높이 11.2 unit = 1.12 m.
        //
        // 이후 "점프가 길어 답답하다" 피드백으로 체공을 0.75초로 줄였다:
        // v = 56, g = 149.33 → 체공 2v/g = 0.75초, 높이 v²/2g = 10.5 unit = 1.05 m.
        //
        // 2026-08-25 점프 3단 재구성 — 공중 클립을 **루프(Fall01)** 로 바꿨다. 이전에는
        // 비루프 클립이라 체공을 바꿀 때마다 재생 속도를 다시 계산해야 했다(착지 시점
        // 클립 위치 = speed × 체공). 루프는 체공이 얼마든 버티므로 **그 연동이 사라졌다** —
        // 낙하가 길어지는 지형에서도 공중 포즈가 끝나 버리지 않는다.
        [SerializeField] float _jumpSpeed = 56f;
        [SerializeField] float _jumpGravity = 149.33f;

        // ── 스폰 위치 강제 ────────────────────────────────────────
        // 서버가 접속 승인에서 배정한 위치. 이동 권위가 Owner(클라이언트)에 있으므로
        // (<see cref="ClientAuthoritativeNetworkTransform"/>) 스폰 직후 경합에서 Owner 쪽
        // 초기 상태(프리팹 원위치 = 원점)가 이기면 서버 배정이 무시된다 — 원점은 방 밖
        // 허공이라 "대부분 허공에서 떨어지고 가끔만 맵에서 스폰" 이 된다 (T-177).
        // 권위자인 Owner 스스로 이 값으로 텔레포트하면 어떤 경합이 있어도 결정적이다.
        public NetworkVariable<Vector3> ServerSpawnPosition = new(
            Vector3.zero, NetworkVariableReadPermission.Everyone, NetworkVariableWritePermission.Server);

        // 값 미수신(버전 불일치 등) 시 현재 위치로 진행하는 한계선 (S15P21A604-259).
        //
        // **시간만으로 재면 가려진 탭에서 너무 일찍 포기한다.** 백그라운드 탭은 rAF 스로틀링으로
        // 약 1 FPS 로 도는데, NetworkVariable 수신도 Update 를 타므로 **기다린 시간이 아니라
        // 기다린 프레임 수**가 실제 기회 횟수다. 3초면 60 FPS 에서 180번 시도하지만 1 FPS 에서는
        // 몇 번에 그친다.
        //
        // 그래서 **둘 다** 넘겨야 포기한다 — 실시간 3초 그리고 최소 시도 횟수.
        // 시간은 `realtimeSinceStartup` 으로 잰다. `Time.time` 은 쓸 수 없다 — 유니티 문서가
        // `Time.maximumDeltaTime` 을 "limits the increase of Time.time between two frames" 라고
        // 규정하고 이 프로젝트 설정값이 0.333s 다. 즉 1 FPS 에서 Time.time 은 프레임당 0.333s 만
        // 흘러 실제 경과와 3배 어긋난다 — 타임아웃 기준으로 삼을 수 없는 시계다.
        const float SpawnWaitTimeout = 3f;
        const int SpawnWaitMinFrames = 180;   // 60 FPS 기준 3초에 해당하는 시도 횟수

        // 다만 시도 횟수만 믿으면 **얼어붙을 수 있다.** 가려진 탭은 포그라운드로 오면 프레임이
        // 금방 채워지지만, 포그라운드인데도 계속 저프레임인 클라이언트는 180프레임을 채우는 데
        // 수십 초가 걸린다. 그동안 플레이어는 이유도 모른 채 움직이지 못한다.
        // 그래서 실시간 상한을 따로 둔다 — 여기까지 오면 시도 횟수와 무관하게 포기하고 알린다.
        const float SpawnWaitHardTimeout = 30f;

        NetworkPlayer _player;
        PlayerCameraFollow _cameraFollow;
        CharacterController _controller;
        Unity.Netcode.Components.NetworkTransform _networkTransform;
        float _turnVelocity;
        float _verticalSpeed;
        bool _spawnPlaced;
        float _spawnWaitStart;
        int _spawnWaitFrames;          // 실제로 시도한 횟수 — 스로틀 탭에서는 시간보다 이쪽이 진실이다
        bool _spawnValueEverChanged;   // 값이 오긴 왔는지 (미수신과 늦은 수신을 로그에서 구분한다)
        bool _airborne;
        bool _jumped;
        float _airborneSince;
        const float AirborneAnimGrace = 0.12f;
        bool _wasShowingAirborne;
        float _landHoldUntil;

        // ── 낙하 안전장치 (2026-09-06) ──────────────────────────────
        // 바닥이 없는 곳(콜라이더 틈·포털 밖)으로 떨어지면 끝없이 추락한다 — 실측 y=-111,829.
        // 일반적인 3인칭 게임의 kill-plane: 한계선 아래로 가면 마지막 접지 위치(없으면 서버 스폰)로 되돌린다.
        const float FallLimitY = -150f;
        const float GroundedRecordInterval = 0.5f;
        Vector3? _lastGroundedPosition;
        float _nextGroundedRecordAt;

        bool _jumpPending;
        float _jumpPressedAt;
        // 발 구르기(Jump_Launch)를 바닥에서 재생하는 시간. 애니메이터의 Jump_Launch
        // 상태 speed 와 한 쌍이다 — 어긋나면 도약 순간이 어긋난다.
        //
        // 2026-08-25 실측 기반 재계산. 클립 `HumanF@Jump01 - Begin`(0.667초)의 루트 Y 는
        // 0.603 → 1.079 로 오른다. **서 있는 엉덩이 높이 1.056 을 통과하는 0.45초가
        // 발이 땅을 떠나는 순간**이다(그 뒤 0.22초는 이미 공중에서 몸을 뻗는 구간).
        // 즉 클립의 접지 구간은 0.45초다. 이것을 이 시간 안에 재생해야 도약과 맞는다:
        //     speed = 0.45 / JumpAnticipation = 0.45 / 0.22 = 2.05
        // 0.22초는 절충값이다 — 더 짧으면(0.13초) 선 자세에서 깊은 웅크림으로 뚝 끊기고,
        // 더 길면(0.30초) "점프가 길어 답답하다" 는 체감이 돌아온다.
        // **제자리 점프에만 적용된다.** 이동 중 점프는 발 구르기를 생략하고 즉시
        // 도약한다 — 몸은 전진하는데 발은 제자리를 딛는 모션이라 미끄러져 보였다.
        const float JumpAnticipation = 0.22f;

        // 착지 유지 시간 — `HumanF@Jump01 - Land`(0.600초)의 흡수·복귀를 보여준다.
        // 클립 앞 0.15초는 **아직 하강 중**(루트 Y 1.057 → 0.653)이므로 재생을 그 지점부터
        // 시작한다(PlayerAvatarVisual). 남는 0.45초 중 앞부분을 이 시간만큼 보여주고
        // 나머지는 로코모션으로 크로스페이드하며 흘린다.
        // 이동 중에는 짧게 끊는다 — 달리다 착지해 무릎을 오래 굽히면 급정지처럼 보인다.
        const float LandHoldIdle = 0.30f;
        const float LandHoldMoving = 0.10f;


        // ── 마인크래프트식 조작 보조 ────────────────────────────────
        static readonly int MoveXHash = Animator.StringToHash("MoveX");
        static readonly int MoveYHash = Animator.StringToHash("MoveY");

        PlayerAvatarVisual _visual;

        /// <summary>카메라 궤도 yaw. 시선과 몸 정렬의 단일 기준이다.</summary>
        float CameraYaw()
        {
            if (_cameraFollow != null && _cameraFollow.TryGetPlanarBasis(out var forward, out _))
                return Mathf.Atan2(forward.x, forward.z) * Mathf.Rad2Deg;
            return transform.eulerAngles.y;
        }

        /// <summary>몸을 목표 yaw 로 부드럽게 돌린다 (한 프레임에 튀지 않게).</summary>
        void AlignBodyTo(float targetYaw)
        {
            var next = Mathf.SmoothDampAngle(
                transform.eulerAngles.y, targetYaw, ref _turnVelocity,
                _turnSmoothTime, _maxTurnSpeedDeg, Time.deltaTime);
            transform.rotation = Quaternion.Euler(0f, next, 0f);
        }

        /// <summary>
        /// 다리가 재생할 방향을 애니메이터에 넘긴다. 몸이 카메라를 보는 동안 옆·뒤로
        /// 움직이면 **몸 기준 지역 방향**이 곧 재생해야 할 클립이다 (8방향 블렌드 트리).
        /// 값을 부드럽게 밀어 넣어 방향 전환에서 다리가 튀지 않게 한다.
        /// </summary>
        void UpdateMoveParams(Vector2 input, bool moving, bool running)
        {
            _visual ??= GetComponent<PlayerAvatarVisual>();
            var animator = _visual != null ? _visual.CurrentAnimator : null;
            if (animator == null) return;

            Vector2 target = Vector2.zero;
            if (moving)
            {
                // 입력을 카메라 기준 월드 방향으로 바꾸고, 다시 몸 기준으로 되돌린다.
                var world = CameraRelativeDirection(input);
                var local = transform.InverseTransformDirection(world);
                target = new Vector2(local.x, local.z).normalized;
            }
            float lerp = 1f - Mathf.Exp(-12f * Time.deltaTime);
            animator.SetFloat(MoveXHash, Mathf.Lerp(animator.GetFloat(MoveXHash), target.x, lerp));
            animator.SetFloat(MoveYHash, Mathf.Lerp(animator.GetFloat(MoveYHash), target.y, lerp));
        }

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<NetworkPlayer>();
            _cameraFollow = GetComponent<PlayerCameraFollow>();
            _controller = GetComponent<CharacterController>();
            _networkTransform = GetComponent<Unity.Netcode.Components.NetworkTransform>();
            _visual = GetComponent<PlayerAvatarVisual>();
            enabled = IsOwner; // 원격 플레이어는 NetworkTransform 수신만

            // 원격 플레이어는 NetworkTransform 이 transform 을 직접 쓴다.
            // CharacterController 가 켜져 있으면 그 대입과 싸우므로 Owner 만 남긴다.
            if (_controller != null) _controller.enabled = IsOwner;

            if (IsServer)
                ServerSpawnPosition.Value = transform.position; // 승인 위치 그대로

            if (IsOwner)
            {
                _spawnWaitStart = Time.realtimeSinceStartup;
                _spawnWaitFrames = 0;
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
            _spawnValueEverChanged = true;
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
        /// <summary>
        /// 포털 등 게임플레이 텔레포트. 스폰 배치(PlaceAt)와 같은 절차 —
        /// CC 끔 → NetworkTransform.Teleport → Physics.SyncTransforms → CC 켬 (T-177).
        /// </summary>
        public void TeleportTo(Vector3 pos)
        {
            bool wasEnabled = _controller != null && _controller.enabled;
            if (wasEnabled) _controller.enabled = false;

            if (_networkTransform != null && _networkTransform.CanCommitToTransform)
                _networkTransform.Teleport(pos, transform.rotation, transform.localScale);
            else
                transform.position = pos;
            Physics.SyncTransforms();

            if (wasEnabled) _controller.enabled = true;
            _verticalSpeed = 0f;
            _airborne = false;
            _jumped = false;
            _jumpPending = false;
            // 텔레포트 직후 한 프레임은 CC 가 접지로 보고할 수 있다 — 바닥 없는 자리를 "마지막 접지" 로 기록하지 않게 잠시 미룬다 (2026-09-06 실측).
            _nextGroundedRecordAt = Time.time + 1f;
        }

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
                _spawnWaitFrames++;
                TryPlaceAtServerSpawn();

                // 시간과 시도 횟수를 **둘 다** 넘겨야 포기한다 (S15P21A604-259).
                // 하나만 보면 가려진 탭에서 몇 번 시도해 보지도 못하고 원점에서 출발한다.
                float waited = Time.realtimeSinceStartup - _spawnWaitStart;
                bool waitedLongEnough = waited > SpawnWaitTimeout;
                bool triedOftenEnough = _spawnWaitFrames >= SpawnWaitMinFrames;
                bool gaveUp = (waitedLongEnough && triedOftenEnough) || waited > SpawnWaitHardTimeout;
                if (!_spawnPlaced && gaveUp)
                {
                    // **재발했을 때 추측하지 않아도 되게 실측값을 남긴다.** 이 경로는 재현이
                    // 어려워(가려진 탭·버전 불일치) 로그가 유일한 증거다. 값이 오긴 왔는지,
                    // 몇 번 시도했는지, 실제로 몇 초였는지가 없으면 다음에도 원인을 추정만 하게 된다.
                    Debug.LogError(
                        "[PlayerMovement] 서버 스폰 위치를 받지 못해 현재 위치에서 시작한다 — " +
                        "원점 근처면 허공에서 떨어진다. " +
                        $"시도 {_spawnWaitFrames}프레임 / {waited:F1}초, " +
                        $"변경 이벤트 {(_spawnValueEverChanged ? "수신" : "없음")}, " +
                        $"ServerSpawnPosition={ServerSpawnPosition.Value}, 현재 위치={transform.position}. " +
                        "서버/클라이언트 빌드 버전이 같은지 확인해라.");
                    _spawnPlaced = true;
                }
                if (!_spawnPlaced) return;
            }

            if (transform.position.y < FallLimitY)
            {
                var back = _lastGroundedPosition ?? (ServerSpawnPosition.Value != Vector3.zero ? ServerSpawnPosition.Value : transform.position);
                back.y = Mathf.Max(back.y, 0f) + 0.5f;
                Debug.LogWarning($"[PlayerMovement] 낙하 한계 초과(y={transform.position.y:F0}) — 마지막 접지 위치 {back} 로 복귀");
                _lastGroundedPosition = null;   // 같은 자리에서 또 떨어지면 다음엔 서버 스폰으로
                TeleportTo(back);
                return;
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
                if (grounded && Time.time >= _nextGroundedRecordAt)
                {
                    _lastGroundedPosition = transform.position;
                    _nextGroundedRecordAt = Time.time + GroundedRecordInterval;
                }
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
                    if (moving)
                    {
                        // 이동 중 점프는 발 구르기 없이 즉시 도약한다. 발 구르기는
                        // 제자리 딛기 모션이라 전진 중에 재생하면 발이 미끄러져
                        // 보이고, Run→Launch→Air 전환이 끼어들어 끊겨 보인다.
                        // 달리기 점프가 즉발인 것은 3인칭 게임의 관례이기도 하다.
                        _verticalSpeed = _jumpSpeed;
                        _airborne = true;
                        _jumped = true;
                        grounded = false;
                    }
                    else
                    {
                        _jumpPending = true;
                        _jumpPressedAt = Time.time;
                    }
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

            // ── 몸 정렬·이동 (마인크래프트식) ───────────────────────────
            // 이전에는 **이동 방향으로 몸을 돌렸다.** 그래서 옆으로 가면 몸이 그쪽을 보고,
            // 보고 있던 부스를 놓친다. 서 있을 때는 카메라만 돌아 캐릭터가 박혀 보였다.
            // "묶여 있는 것 같다" 는 체감이 여기서 나왔다.
            //
            // 바꾼 규칙:
            //   · 이동 중에는 몸을 **카메라 정면**으로 정렬한다. 다리는 8방향 블렌드 트리가
            //     실제 이동 방향을 재생하므로, 옆·뒤로 가도 시선을 유지한 채 걷는다.
            //   · 서 있을 때는 몸을 **아예 돌리지 않는다.** 고개·상체만 따라간다(AvatarLook).
            //     그래야 카메라를 돌려 자기 캐릭터의 정면·측면 애니메이션을 볼 수 있다.
            if (moving)
            {
                var dir = CameraRelativeDirection(input);
                var speed = running ? _runSpeed : _moveSpeed;
                MoveWithCollision(dir * speed);
                AlignBodyTo(CameraYaw());
            }
            else
            {
                _turnVelocity = 0f;
                MoveWithCollision(Vector3.zero);   // 정지 중에도 중력은 적용한다
                // 서 있을 때는 몸을 **전혀** 돌리지 않는다. 카메라를 돌려도 캐릭터가
                // 그 자리 자세를 유지해야 자기 애니메이션을 정면·측면에서 볼 수 있다.
                // 고개만 따라간다 (AvatarLook, ±90°).
            }

            // 다리가 재생할 방향 — 몸 기준 지역 좌표계로 넘긴다.
            UpdateMoveParams(input, moving, running);

            // 공중에서는 이동 입력과 무관하게 Jump 를 보낸다 — 원격 클라이언트가
            // 같은 애니메이션을 재생한다 (AnimState 는 Owner 쓰기 권한이다).
            // 접지 판정은 바닥 이음새·경사에서 한두 프레임씩 끊긴다. 그때마다 점프
            // 포즈가 번쩍이지 않도록 **의도한 도약이 아니면** 짧은 유예를 둔다.
            bool showAirborne = _airborne &&
                (_jumped || Time.time - _airborneSince > AirborneAnimGrace);
            // 착지 순간 — **공중 포즈가 실제로 화면에 나왔을 때만** 착지 모션을 낸다.
            // 바닥 이음새에서 한두 프레임 뜨는 것에까지 착지를 재생하면 그냥 걷는 동안
            // 계속 무릎을 굽힌다. showAirborne 은 이미 그 유예를 통과한 값이다.
            if (_wasShowingAirborne && !showAirborne)
                _landHoldUntil = Time.time + (moving ? LandHoldMoving : LandHoldIdle);
            _wasShowingAirborne = showAirborne;

            var next = _jumpPending
                ? PlayerAnimState.JumpLaunch
                : showAirborne
                ? PlayerAnimState.Jump
                : Time.time < _landHoldUntil
                ? PlayerAnimState.JumpLand
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

        // ── 합성 입력 (부하 테스트 봇 전용) ─────────────────────────
        // 봇이 CharacterController 를 직접 밀면 이 경로를 건너뛰어 **AnimState 가
        // Idle 에 머문다.** 그러면 걷기 애니메이션이 원격에 전파되지 않아
        //   ① AnimState 동기화 트래픽이 빠지고
        //   ② 다른 클라이언트가 그 아바타를 애니메이션하지 않아 CPU 가 과소평가된다.
        // 실제 사용자와 같은 부하를 만들려면 입력 자체를 주입해야 한다.
        [System.NonSerialized] public bool UseSyntheticInput;
        [System.NonSerialized] public Vector2 SyntheticInput;
        [System.NonSerialized] public bool SyntheticRun;

        Vector2 ReadMoveInput()
        {
            if (UseSyntheticInput) return SyntheticInput;
            return ReadKeyboardInput();
        }

        bool IsRunPressed()
        {
            if (UseSyntheticInput) return SyntheticRun;
            return IsRunKeyPressed();
        }

        static Vector2 ReadKeyboardInput()
        {
            // 호스트 Overlay 가 열려 있으면 월드 입력을 읽지 않는다 (G-8, InputBridge).
            if (Festa.Integration.InputBridge.IsLocked) return Vector2.zero;
            var kb = Keyboard.current;
            if (kb == null) return Vector2.zero;

            var v = Vector2.zero;
            if (kb.wKey.isPressed || kb.upArrowKey.isPressed) v.y += 1f;
            if (kb.sKey.isPressed || kb.downArrowKey.isPressed) v.y -= 1f;
            if (kb.dKey.isPressed || kb.rightArrowKey.isPressed) v.x += 1f;
            if (kb.aKey.isPressed || kb.leftArrowKey.isPressed) v.x -= 1f;
            return v;
        }

        static bool IsRunKeyPressed()
        {
            if (Festa.Integration.InputBridge.IsLocked) return false;
            var kb = Keyboard.current;
            return kb != null && (kb.leftShiftKey.isPressed || kb.rightShiftKey.isPressed);
        }

        /// <summary>
        /// 스페이스바가 이번 프레임에 눌렸는지. `isPressed` 가 아니라 `wasPressedThisFrame` 이다 —
        /// 누르고 있는 동안 매 프레임 도약하면 바닥에 닿을 때마다 튀어오른다.
        /// </summary>
        static bool IsJumpPressed()
        {
            if (Festa.Integration.InputBridge.IsLocked) return false;
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
