using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;
using Festa.Network;

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
        [SerializeField] Vector3 _offset = new(0f, 4f, -18f);
        [SerializeField] float _followLerp = 8f;
        [SerializeField] float _collisionRadius = 0.25f;
        [SerializeField] float _collisionPadding = 0.15f;
        [SerializeField] LayerMask _collisionMask = ~0;
        [SerializeField] float _minDistance = 9f;
        [SerializeField] float _maxDistance = 36f;
        [SerializeField] float _zoomStep = 1.2f;
        [SerializeField] float _orbitSensitivity = 0.12f;
        [SerializeField] float _minPitch = -20f;
        [SerializeField] float _maxPitch = 65f;
        [SerializeField] float _collisionReturnLerp = 5f;
        [SerializeField] float _lookHeight = 8.05f;

        Camera _cam;
        float _distance;
        float _yaw;
        float _pitch = 27f;
        float _resolvedDistance;
        readonly RaycastHit[] _collisionHits = new RaycastHit[64];

        /// <summary>
        /// 이 플레이어를 실제로 따라가는 카메라의 수평 이동 축을 반환한다.
        /// 전역 Camera.main을 다시 찾지 않고 카메라 궤도 yaw를 단일 기준으로 쓴다.
        /// </summary>
        public bool TryGetPlanarBasis(out Vector3 forward, out Vector3 right)
        {
            if (!IsOwner)
            {
                forward = Vector3.forward;
                right = Vector3.right;
                return false;
            }

            var yawRotation = Quaternion.Euler(0f, _yaw, 0f);
            forward = yawRotation * Vector3.forward;
            right = yawRotation * Vector3.right;
            return true;
        }

        public override void OnNetworkSpawn()
        {
            enabled = IsOwner;
            if (!IsOwner) return;

            _cam = Camera.main;
            _distance = Mathf.Clamp(Mathf.Abs(_offset.z), _minDistance, _maxDistance);
            _resolvedDistance = _distance;
            _yaw = transform.eulerAngles.y;
        }

        void LateUpdate()
        {
            if (_cam == null)
            {
                _cam = Camera.main;
                if (_cam == null) return;
            }

            UpdateDistance();
            UpdateOrbit();

            var lookTarget = transform.position + Vector3.up * _lookHeight;
            var orbitRotation = Quaternion.Euler(_pitch, _yaw, 0f);
            var orbitDirection = orbitRotation * Vector3.back;
            var desiredDistance = _distance;

            var hitCount = Physics.SphereCastNonAlloc(
                lookTarget,
                _collisionRadius,
                orbitDirection,
                _collisionHits,
                desiredDistance,
                _collisionMask,
                QueryTriggerInteraction.Ignore);

            for (var i = 0; i < hitCount; i++)
            {
                var hit = _collisionHits[i];
                if (IsPlayerCollider(hit.collider)) continue;
                desiredDistance = Mathf.Min(
                    desiredDistance,
                    Mathf.Max(_collisionRadius, hit.distance - _collisionPadding));
            }

            // 벽에 닿을 때는 즉시 당겨 관통을 막고, 벽에서 벗어날 때만
            // 부드럽게 원래 거리로 돌아가 근접 건축물 사이의 떨림을 줄인다.
            _resolvedDistance = desiredDistance < _resolvedDistance
                ? desiredDistance
                : Mathf.Lerp(_resolvedDistance, desiredDistance, _collisionReturnLerp * Time.deltaTime);

            var targetPos = lookTarget + orbitDirection * _resolvedDistance;
            var obstructed = desiredDistance < _distance - 0.001f;
            _cam.transform.position = obstructed
                ? targetPos
                : Vector3.Lerp(_cam.transform.position, targetPos, _followLerp * Time.deltaTime);
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

        void UpdateOrbit()
        {
            var mouse = Mouse.current;
            if (mouse == null || !mouse.rightButton.isPressed) return;

            var delta = mouse.delta.ReadValue();
            _yaw += delta.x * _orbitSensitivity;
            _pitch = Mathf.Clamp(
                _pitch - delta.y * _orbitSensitivity,
                _minPitch,
                _maxPitch);
        }

        bool IsPlayerCollider(Collider collider)
        {
            if (collider == null) return false;
            if (collider.transform.IsChildOf(transform)) return true;

            // 접속 인원이 늘어나도 다른 플레이어의 CapsuleCollider 때문에
            // 카메라가 앞으로 튀지 않도록 모든 NetworkPlayer를 제외한다.
            return collider.GetComponentInParent<NetworkPlayer>() != null;
        }
    }
}
