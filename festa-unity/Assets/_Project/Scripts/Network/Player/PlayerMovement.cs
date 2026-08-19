using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;
using Festa.World;

namespace Festa.Network
{
    /// <summary>
    /// Owner 전용 WASD/화살표 이동. POC 수준 — CharacterController/물리는 월드 확정 후.
    /// AnimState를 이동 여부에 따라 갱신해 원격 클라이언트가 Idle/Walk를 표현할 수 있게 한다.
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class PlayerMovement : NetworkBehaviour
    {
        [SerializeField] float _moveSpeed = 36f;
        [SerializeField] float _runSpeed = 52f;
        [SerializeField, Min(0.01f)] float _turnSmoothTime = 0.08f;
        [SerializeField, Min(1f)] float _maxTurnSpeedDeg = 1080f;

        NetworkPlayer _player;
        PlayerCameraFollow _cameraFollow;
        float _turnVelocity;

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<NetworkPlayer>();
            _cameraFollow = GetComponent<PlayerCameraFollow>();
            enabled = IsOwner; // 원격 플레이어는 NetworkTransform 수신만
        }

        void Update()
        {
            if (!IsOwner) return;

            var input = ReadMoveInput();
            bool moving = input.sqrMagnitude > 0.0001f;
            bool running = moving && IsRunPressed();

            if (moving && _player.EmoteId.Value != PlayerEmoteId.None)
                _player.EmoteId.Value = PlayerEmoteId.None;

            if (moving)
            {
                var dir = CameraRelativeDirection(input);
                var speed = running ? _runSpeed : _moveSpeed;
                transform.position += dir * (speed * Time.deltaTime);

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
            }

            var next = !moving
                ? PlayerAnimState.Idle
                : running ? PlayerAnimState.Run : PlayerAnimState.Walk;
            if (_player.AnimState.Value != next)
                _player.AnimState.Value = next;
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
