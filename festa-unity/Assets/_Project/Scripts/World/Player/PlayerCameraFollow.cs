using System.Collections.Generic;
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
        // 수평(0) 아래로 내려가면 단면 바닥을 밑에서 보게 된다 — 지면 뚫림의 원천.
        [SerializeField] float _minPitch = 4f;
        [SerializeField] float _maxPitch = 65f;
        [SerializeField] float _collisionReturnLerp = 5f;
        // 당기는 쪽은 밀려나는 쪽보다 빨라야 한다 — 느리면 벽에 파묻힌다.
        // 다만 즉시(무한)로 두면 얇은 기물 뒤에서 화면이 튄다 (T-183).
        // 18 도 여전히 급하다는 피드백을 받아 8 로 내렸다. 관통은 아래 즉시-당김
        // 예외(근평면 반경 × 2)가 막으므로 이 값은 체감만 결정한다.
        [SerializeField] float _collisionPullLerp = 8f;

        // ── 시선 높이 ─────────────────────────────────────────────
        // 하나로 고정하면 줌인할 때 엉덩이를 들여다본다. 3인칭 게임은 가까워질수록
        // 시선을 **어깨 쪽으로 올린다** — 멀리서는 발밑까지 보여 주고, 가까이서는
        // 상체를 본다. 아바타 목표 높이가 17.9 unit 이라 어깨는 대략 14 다.
        [SerializeField] float _lookHeight = 11.5f;     // 최대 줌아웃에서의 높이 (가슴 — 바닥 쏠림 방지)
        [SerializeField] float _lookHeightNear = 14f;    // 최대 줌인에서의 높이

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
        readonly List<Renderer> _hiddenRenderers = new();
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

            // 줌 거리에 따라 시선 높이를 옮긴다 — 가까울수록 어깨 쪽으로 올린다.
            // 기준은 사용자가 고른 `_distance` 다. 가림 때문에 당겨진 거리를 쓰면
            // 기물 뒤를 지날 때 시선까지 위아래로 흔들린다.
            float zoomT = Mathf.InverseLerp(_minDistance, _maxDistance, _distance);
            float lookHeight = Mathf.Lerp(_lookHeightNear, _lookHeight, zoomT);
            var lookTarget = transform.position + Vector3.up * lookHeight;
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

            // 당길 때도 보간한다. 예전에는 즉시 당겼는데, 벽처럼 넓은 면은 궤도를 돌면서
            // 가림 정도가 서서히 바뀌어 괜찮았지만 **사람 모형·기둥처럼 얇은 기물**은
            // 캐스트가 맞았다/안 맞았다를 급히 오가며 화면이 튀었다 (T-183).
            //
            // 다만 근평면이 지오메트리에 닿을 만큼 가까우면 즉시 당긴다 — 거기서 보간하면
            // 한두 프레임 동안 벽을 뚫고 밖이 보인다. 그 경계만 즉시, 나머지는 부드럽게.
            if (desiredDistance < _resolvedDistance)
            {
                _resolvedDistance = desiredDistance <= radius * 2f
                    ? desiredDistance
                    : Mathf.Lerp(_resolvedDistance, desiredDistance, _collisionPullLerp * Time.deltaTime);
            }
            else
            {
                _resolvedDistance = Mathf.Lerp(
                    _resolvedDistance, desiredDistance, _collisionReturnLerp * Time.deltaTime);
            }

            var targetPos = lookTarget + orbitDirection * _resolvedDistance;

            // 바닥 클램프 — 지면은 단면 메시라 밑에서 보면 하늘이 뚫린다. 플레이어
            // 발밑 지면을 기준으로 최소 높이를 강제한다. 플레이어 레이어(8)는 제외.
            int groundMask = _collisionMask & ~(1 << 8);
            if (Physics.Raycast(transform.position + Vector3.up * 2f, Vector3.down,
                    out var groundHit, 80f, groundMask, QueryTriggerInteraction.Ignore))
            {
                float minY = groundHit.point.y + 2f;
                if (targetPos.y < minY) targetPos.y = minY;
            }

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
            if (shouldHide) HideSelf();
            else RestoreSelf();
        }

        /// <summary>
        /// **켜져 있던 렌더러만** 기억해서 끈다.
        ///
        /// 복귀할 때 전부 켜면 **의도적으로 꺼 둔 렌더러가 되살아난다.** 실제로 그렇게 됐다 —
        /// `PlayerAvatar` 프리팹 루트에는 POC 시절 캡슐 메시가 `enabled = false` 로 남아 있는데,
        /// 처음 구현이 무조건 `enabled = true` 로 켜서 발밑에 회색 봉이 따라다녔다 (T-183).
        /// </summary>
        void HideSelf()
        {
            _hiddenRenderers.Clear();
            foreach (var r in GetComponentsInChildren<Renderer>(true))
            {
                if (!r.enabled) continue;   // 원래 꺼져 있던 것은 건드리지 않는다
                r.enabled = false;
                _hiddenRenderers.Add(r);
            }
        }

        void RestoreSelf()
        {
            foreach (var r in _hiddenRenderers)
                if (r != null) r.enabled = true;
            _hiddenRenderers.Clear();
        }

        public override void OnNetworkDespawn()
        {
            // 숨긴 채로 사라지면 다음 스폰이나 다른 용도에서 안 보이는 채로 남는다.
            if (!_selfHidden) return;
            _selfHidden = false;
            RestoreSelf();
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
