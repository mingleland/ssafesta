using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.Network
{
    /// <summary>
    /// Owner 전용 WASD/화살표 이동. POC 수준 — CharacterController/물리는 월드 확정 후.
    /// AnimState를 이동 여부에 따라 갱신해 원격 클라이언트가 Idle/Walk를 표현할 수 있게 한다.
    /// </summary>
    [RequireComponent(typeof(NetworkPlayer))]
    public class PlayerMovement : NetworkBehaviour
    {
        [SerializeField] float _moveSpeed = 4f;
        [SerializeField] float _runSpeed = 6.5f;
        [SerializeField] float _rotateSpeedDeg = 720f;

        NetworkPlayer _player;

        public override void OnNetworkSpawn()
        {
            _player = GetComponent<NetworkPlayer>();
            enabled = IsOwner; // 원격 플레이어는 NetworkTransform 수신만
        }

        void Update()
        {
            if (!IsOwner) return;

            var input = ReadMoveInput();
            bool moving = input.sqrMagnitude > 0.0001f;
            bool running = moving && IsRunPressed();

            if (moving)
            {
                var dir = CameraRelativeDirection(input);
                var speed = running ? _runSpeed : _moveSpeed;
                transform.position += dir * (speed * Time.deltaTime);
                var target = Quaternion.LookRotation(dir, Vector3.up);
                transform.rotation = Quaternion.RotateTowards(
                    transform.rotation, target, _rotateSpeedDeg * Time.deltaTime);
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

        static Vector3 CameraRelativeDirection(Vector2 input)
        {
            var camera = Camera.main;
            if (camera == null) return new Vector3(input.x, 0f, input.y).normalized;

            var forward = camera.transform.forward;
            var right = camera.transform.right;
            forward.y = 0f;
            right.y = 0f;

            if (forward.sqrMagnitude < 0.0001f || right.sqrMagnitude < 0.0001f)
                return new Vector3(input.x, 0f, input.y).normalized;

            return (forward.normalized * input.y + right.normalized * input.x).normalized;
        }
    }
}
