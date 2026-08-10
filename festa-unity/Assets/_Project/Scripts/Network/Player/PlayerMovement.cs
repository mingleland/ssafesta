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

            if (moving)
            {
                var dir = new Vector3(input.x, 0f, input.y).normalized;
                transform.position += dir * (_moveSpeed * Time.deltaTime);
                var target = Quaternion.LookRotation(dir, Vector3.up);
                transform.rotation = Quaternion.RotateTowards(
                    transform.rotation, target, _rotateSpeedDeg * Time.deltaTime);
            }

            var next = moving ? PlayerAnimState.Walk : PlayerAnimState.Idle;
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
    }
}
