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

        // ── 자기 몸 가리기 ────────────────────────────────────────
        // 뒤에 벽·기물이 있으면 카메라가 앞으로 당겨지고, 그러다 아바타 안으로 들어가
        // 화면이 몸통으로 가득 찬다. 3인칭 게임의 표준 처리는 **가까워지면 자기 캐릭터를
        // 감추는 것**이다 (카메라를 억지로 밀어내면 벽을 뚫고 밖이 보인다).
        //
        // 히스테리시스를 둔다 — 임계값 하나면 경계에서 깜빡인다.
        //
        // 임계값은 최소 줌 거리(`_minDistance` 9)보다 **낮아야** 한다. 같거나 높으면
        // 사용자가 의도적으로 최대 줌인만 해도 자기 아바타가 사라진다 — 그건 버그로 보인다.
        // 여기 걸리는 것은 벽에 밀려 강제로 당겨진 경우뿐이다 (거리가 캐스트 반경까지 내려간다).
        // 1 m = 10 unit 이므로 6 = 0.6 m, 8 = 0.8 m 다.
        [SerializeField] float _selfHideDistance = 6f;   // 이보다 가까우면 숨긴다
        [SerializeField] float _selfShowDistance = 8f;   // 이보다 멀어지면 다시 보인다

        Camera _cam;
        float _distance;
        float _yaw;
        float _pitch = 27f;
        float _resolvedDistance;
        bool _selfHidden;
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

            // 근평면이 지오메트리에 들어가면 벽을 뚫고 밖이 보인다. 그러지 않도록
            // 캐스트 반경을 **근평면 모서리까지의 거리** 이상으로 잡는다 —
            // 상수로 두면 FOV·해상도·near 를 바꿀 때 조용히 어긋난다.
            float radius = Mathf.Max(_collisionRadius, NearPlaneRadius());

            var hitCount = Physics.SphereCastNonAlloc(
                lookTarget,
                radius,
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
                    Mathf.Max(radius, hit.distance - _collisionPadding));
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

            UpdateSelfVisibility();
        }

        /// <summary>근평면 모서리까지의 거리. 이보다 작은 반경으로 캐스트하면 벽이 뚫린다.</summary>
        float NearPlaneRadius()
        {
            float h = _cam.nearClipPlane * Mathf.Tan(_cam.fieldOfView * 0.5f * Mathf.Deg2Rad);
            float w = h * Mathf.Max(0.01f, _cam.aspect);
            return new Vector3(w, h, _cam.nearClipPlane).magnitude;
        }

        /// <summary>
        /// 카메라가 가까워지면 자기 아바타를 감춘다. **로컬 렌더링만 끄는 것이므로
        /// 다른 접속자에게는 그대로 보인다** — 네트워크로 나가는 상태가 아니다.
        ///
        /// 렌더러 목록을 캐시하지 않는다. `PlayerAvatarVisual` 이 외형 변경마다 아바타를
        /// 다시 조립하므로 캐시는 곧 낡는다. 전환 순간에만 훑으므로 비용이 없다
        /// (매 프레임이 아니라 임계값을 넘을 때 한 번).
        /// </summary>
        void UpdateSelfVisibility()
        {
            bool shouldHide = _selfHidden
                ? _resolvedDistance < _selfShowDistance   // 숨은 상태면 더 멀어져야 다시 보인다
                : _resolvedDistance < _selfHideDistance;
            if (shouldHide == _selfHidden) return;

            _selfHidden = shouldHide;
            foreach (var r in GetComponentsInChildren<Renderer>(true))
                r.enabled = !shouldHide;
        }

        public override void OnNetworkDespawn()
        {
            // 숨긴 채로 사라지면 다음 스폰이나 다른 용도에서 안 보이는 채로 남는다.
            if (!_selfHidden) return;
            _selfHidden = false;
            foreach (var r in GetComponentsInChildren<Renderer>(true)) r.enabled = true;
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
