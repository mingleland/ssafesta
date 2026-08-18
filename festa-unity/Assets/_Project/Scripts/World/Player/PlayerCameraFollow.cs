using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World
{
    /// <summary>
    /// Owner 플레이어를 Main Camera가 뒤에서 따라가게 한다 (POC용 3인칭 고정 추적).
    /// Player Prefab에 부착. 자신(Owner)일 때만 동작하므로
    /// 원격 플레이어가 카메라를 빼앗는 일은 없다.
    /// 정식 카메라 워크(Cinemachine 등)는 월드 기능 spec 확정 후 교체.
    /// </summary>
    public class PlayerCameraFollow : NetworkBehaviour
    {
        [SerializeField] Vector3 _offset = new(0f, 4f, -6f);
        [SerializeField] float _followLerp = 8f;
        [SerializeField] float _collisionRadius = 0.25f;
        [SerializeField] float _collisionPadding = 0.15f;
        [SerializeField] LayerMask _collisionMask = ~0;
        [SerializeField] float _minDistance = 3f;
        [SerializeField] float _maxDistance = 8f;
        [SerializeField] float _zoomStep = 0.35f;

        Camera _cam;
        float _distance;

        public override void OnNetworkSpawn()
        {
            enabled = IsOwner;
            if (!IsOwner) return;

            _cam = Camera.main;
            _distance = Mathf.Clamp(Mathf.Abs(_offset.z), _minDistance, _maxDistance);
        }

        void LateUpdate()
        {
            if (_cam == null)
            {
                _cam = Camera.main;
                if (_cam == null) return;
            }

            UpdateDistance();

            var lookTarget = transform.position + Vector3.up * 1f;
            var localOffset = _offset;
            localOffset.z = -_distance;
            // 캐릭터가 이동 방향으로 회전해도 카메라 기준축은 흔들리지 않게
            // 월드 오프셋을 유지한다. 그렇지 않으면 A/D를 누르는 동안
            // 카메라까지 계속 회전해 이동 경로가 원을 그리게 된다.
            var desiredPos = transform.position + localOffset;
            var castDirection = desiredPos - lookTarget;
            var targetPos = desiredPos;

            if (castDirection.sqrMagnitude > 0.0001f)
            {
                var hits = Physics.SphereCastAll(
                    lookTarget,
                    _collisionRadius,
                    castDirection.normalized,
                    castDirection.magnitude,
                    _collisionMask,
                    QueryTriggerInteraction.Ignore);

                var nearestDistance = castDirection.magnitude;
                foreach (var hit in hits)
                {
                    if (hit.collider.transform.IsChildOf(transform)) continue;
                    if (hit.distance < nearestDistance) nearestDistance = hit.distance;
                }

                if (nearestDistance < castDirection.magnitude)
                {
                    targetPos = lookTarget + castDirection.normalized *
                        Mathf.Max(0f, nearestDistance - _collisionPadding);
                }
            }

            _cam.transform.position = Vector3.Lerp(
                _cam.transform.position, targetPos, _followLerp * Time.deltaTime);
            _cam.transform.LookAt(lookTarget);
        }

        void UpdateDistance()
        {
            var mouse = Mouse.current;
            if (mouse == null) return;

            var scroll = mouse.scroll.ReadValue().y;
            if (Mathf.Abs(scroll) < 0.01f) return;

            _distance = Mathf.Clamp(
                _distance - Mathf.Sign(scroll) * _zoomStep,
                _minDistance,
                _maxDistance);
        }
    }
}
