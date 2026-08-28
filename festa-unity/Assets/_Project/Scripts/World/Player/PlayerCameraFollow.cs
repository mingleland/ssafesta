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
        // 거리·높이류 기본값은 아바타 스케일과 한 몸이다 — 2026-08-24 스케일업(×1.25)
        // 에 맞춰 프리팹 직렬화 값과 함께 올렸다 (구값: min 9, max 36, look 11.5/14 등).
        [SerializeField] Vector3 _offset = new(0f, 5f, -22.5f);
        [SerializeField] float _followLerp = 8f;
        [SerializeField] float _collisionRadius = 0.8f;
        [SerializeField] float _collisionPadding = 2.5f;   // 0.25 m — 벽면과의 최소 이격
        [SerializeField] LayerMask _collisionMask = ~0;
        [SerializeField] float _minDistance = 11.25f;
        [SerializeField] float _maxDistance = 45f;
        [SerializeField] float _zoomStep = 1.5f;
        [SerializeField] float _orbitSensitivity = 0.12f;
        // 밤하늘을 올려다볼 수 있게 수평 아래로 조금 연다. 지면 뚫림은 아래의
        // 바닥 클램프가 별도로 막으므로 안전하다 (T-190 이후 구조).
        [SerializeField] float _minPitch = -12f;
        [SerializeField] float _maxPitch = 65f;
        [SerializeField] float _collisionReturnLerp = 5f;
        // (구) 당김 보간 계수 — 더 이상 쓰지 않는다. 보간 당김은 전환하는 동안
        // 카메라가 장애물 너머에 머물게 하는데, 오클루전 컬링이 베이크된 뒤로는
        // 그 한 순간에 실내 전체가 컬링돼 "바깥 세상이 번쩍" 하는 최악의 화면이
        // 된다 (T-190). 당김은 즉시(하드 클램프), 복귀만 부드럽게 — 업계 표준.
        [SerializeField] float _collisionPullLerp = 8f;

        // ── 시선 높이 ─────────────────────────────────────────────
        // 하나로 고정하면 줌인할 때 엉덩이를 들여다본다. 3인칭 게임은 가까워질수록
        // 시선을 **어깨 쪽으로 올린다** — 멀리서는 발밑까지 보여 주고, 가까이서는
        // 상체를 본다. 아바타 목표 높이가 22.375 unit 이라 어깨는 대략 17.5 다.
        [SerializeField] float _lookHeight = 14.4f;     // 최대 줌아웃에서의 높이 (가슴 — 바닥 쏠림 방지)
        [SerializeField] float _lookHeightNear = 17.5f;    // 최대 줌인에서의 높이

        // ── 자기 몸 가리기 ────────────────────────────────────────
        // 뒤에 벽·기물이 있으면 카메라가 앞으로 당겨지고, 그러다 아바타 안으로 들어가
        // 화면이 몸통으로 가득 찬다. 3인칭 게임의 표준 처리는 **가까워지면 자기 캐릭터를
        // 감추는 것**이다 (카메라를 억지로 밀어내면 벽을 뚫고 밖이 보인다).
        //
        // 히스테리시스를 둔다 — 임계값 하나면 경계에서 깜빡인다.
        //
        // 임계값은 최소 줌 거리(`_minDistance` 11.25)보다 **낮아야** 한다. 같거나 높으면
        // 사용자가 의도적으로 최대 줌인만 해도 자기 아바타가 사라진다 — 그건 버그로 보인다.
        // 여기 걸리는 것은 벽에 밀려 강제로 당겨진 경우뿐이다 (거리가 캐스트 반경까지 내려간다).
        // 1 m = 10 unit 이므로 7.5 = 0.75 m, 10 = 1.0 m 다.
        [SerializeField] float _selfHideDistance = 7.5f;   // 이보다 가까우면 숨긴다
        [SerializeField] float _selfShowDistance = 10f;   // 이보다 멀어지면 다시 보인다

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
        /// <summary>현재 궤도 피치. 아바타 시선(고개 상하)이 카메라를 따라가는 데 쓴다.</summary>
        public float CurrentPitch => _pitch;

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

        /// <summary>
        /// 텔레포트 직후 카메라를 플레이어 뒤로 즉시 스냅한다. 보간에 맡기면
        /// 카메라가 맵을 가로질러 날아오며 오클루전이 셀마다 번쩍인다.
        /// </summary>
        public void SnapBehind()
        {
            if (_cam == null) return;
            var lookTarget = transform.position + Vector3.up * _lookHeight;
            var dir = Quaternion.Euler(_pitch, _yaw, 0f) * Vector3.back;
            _resolvedDistance = _distance;
            _cam.transform.position = lookTarget + dir * _distance;
            _cam.transform.LookAt(lookTarget);
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

            // 당김은 **즉시**다. 보간하면 전환 프레임 동안 카메라가 장애물 너머에
            // 남는데, 단면 벽 + 베이크된 오클루전에서는 그 순간 실내가 통째로 컬링돼
            // 바깥 하늘이 번쩍인다 (T-190 — "외부환경이 보이고 아바타가 사라진다").
            // 얇은 기물 뒤의 튐(T-183)은 근접 자기 숨김과 시선 높이가 흡수한다.
            // 복귀(밀려남)만 부드럽게 푼다.
            if (desiredDistance < _resolvedDistance)
                _resolvedDistance = desiredDistance;
            else
                _resolvedDistance = Mathf.Lerp(
                    _resolvedDistance, desiredDistance, _collisionReturnLerp * Time.deltaTime);

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
            var candidate = obstructed
                ? targetPos
                : Vector3.Lerp(_cam.transform.position, targetPos, _followLerp * Time.deltaTime);

            // 최종 위치에도 시야선 클램프를 건다 (T-217). 위의 SphereCast 는 "목표 위치"의
            // 방향만 검사하는데, 실제 카메라는 보간(_followLerp) 경로 위에 있다 — 벽에 붙어
            // 회전하거나 급히 방향을 바꾸면 보간 경로가 벽을 가로질러, 단면 벽 밖에서
            // 실내가 통째로 컬링된 화면(하늘+바닥 판)이 나온다. 목표가 아니라
            // **오늘 프레임에 실제로 놓을 위치**가 검사 대상이어야 한다.
            _cam.transform.position = ClampLineOfSight(lookTarget, candidate, radius);
            _cam.transform.LookAt(lookTarget);

            UpdateSelfVisibility(Vector3.Distance(_cam.transform.position, lookTarget));
        }

        /// <summary>
        /// lookTarget 에서 pos 까지 시야선이 막혀 있으면 장애물 앞으로 당긴 위치를 반환한다.
        /// SphereCast 는 시작 구가 이미 콜라이더와 겹치면 그 콜라이더를 보고하지 않으므로
        /// (벽에 딱 붙은 경우), 점 Linecast 를 백스톱으로 함께 건다.
        /// </summary>
        Vector3 ClampLineOfSight(Vector3 lookTarget, Vector3 pos, float radius)
        {
            var offset = pos - lookTarget;
            float dist = offset.magnitude;
            if (dist < 0.001f) return pos;
            var dir = offset / dist;

            float clamped = dist;
            var hitCount = Physics.SphereCastNonAlloc(
                lookTarget, radius, dir, _collisionHits, dist,
                _collisionMask, QueryTriggerInteraction.Ignore);
            for (var i = 0; i < hitCount; i++)
            {
                var hit = _collisionHits[i];
                if (IsPlayerCollider(hit.collider)) continue;
                clamped = Mathf.Min(clamped, Mathf.Max(radius, hit.distance - _collisionPadding));
            }

            if (Physics.Linecast(lookTarget, pos, out var lineHit, _collisionMask, QueryTriggerInteraction.Ignore)
                && !IsPlayerCollider(lineHit.collider))
                clamped = Mathf.Min(clamped, Mathf.Max(radius, lineHit.distance - _collisionPadding));

            return lookTarget + dir * clamped;
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
        void UpdateSelfVisibility(float actualDistance)
        {
            // 판정 기준은 궤도 축의 _resolvedDistance 가 아니라 **실제 카메라-시선 거리**다.
            // 시야선 클램프(T-217)가 카메라를 더 당겼을 수 있다 — 그때도 몸통이 화면을
            // 채우면 숨겨야 한다.
            bool shouldHide = _selfHidden
                ? actualDistance < _selfShowDistance   // 숨은 상태면 더 멀어져야 다시 보인다
                : actualDistance < _selfHideDistance;
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
