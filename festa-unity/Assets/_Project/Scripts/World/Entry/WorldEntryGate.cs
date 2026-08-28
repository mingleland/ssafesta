using System.Runtime.InteropServices;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 입장 게이트 (spec 002 FR-013·014, 폐기된 018 에서 이전).
    ///
    /// 월드 씬을 열고 접속·스폰이 끝나기까지의 대기를 **11층 엘리베이터 내부**가 가린다.
    /// 흰 화면이나 텅 빈 월드를 보여주지 않는다.
    ///
    /// **갇히지 않는다(FR-014).** 준비가 끝나지 않아도 30초면 강제로 연다. 그때 원인을
    /// 조용히 삼키지 않고 에러로 남긴다 — 조용한 폴백이 T-24 의 원인이었다.
    ///
    /// 씬을 고치지 않는다. 자동 등록이라 프리팹·씬 편집 없이 붙고,
    /// 메인 카메라를 끄지 않고 **더 높은 depth 로 위에 덮어** Camera.main 이 null 이 되는 일이 없다
    /// (PlayerCameraFollow 가 Camera.main 을 참조한다).
    /// </summary>
    public class WorldEntryGate : MonoBehaviour
    {
        /// <summary>준비 실패해도 이 시간이면 무조건 연다 (FR-014).</summary>
        [SerializeField] float _forceOpenSeconds = 30f;

        /// <summary>이 시간 안에 접속 시도가 안 보이면 개발자가 월드 씬을 단독 실행한 것으로 본다.</summary>
        [SerializeField] float _standaloneGraceSeconds = 3f;

        const float DoorSlideSeconds = 1.1f;
        const float DoorHoldSeconds = 2.5f;   // 열린 채 두는 시간 — 플레이어가 내릴 틈

#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern void FestaNotifyWorldGateReady();
#endif

        Camera _gateCam;
        Light _gateLight;
        Transform _doorLeft, _doorRight;
        Vector3 _doorLeftClosed, _doorRightClosed;
        float _doorTravel;

        float _elapsed;
        bool _opening;
        bool _handedOver;           // 화면을 월드로 넘겼다 (카메라·조명 철거 완료)
        float _holdUntil;           // 이 시각까지 문을 열어 둔다
        float _closeProgress;
        float _openProgress;

        GateFloorIndicator _floorIndicator;
        float _floor = StartFloor;  // 표시 중인 층 (실수 — 부드럽게 올라간다)
        bool _arrivalRequested;     // 도착 예약 — 표시가 11 에 닿으면 연다
        string _openReason = "";
        const int TopFloor = 11;    // 도착층. 준비되기 전에는 절대 여기 닿지 않는다.
        const int StartFloor = 5;   // 1층부터 세면 숫자가 정신없이 굴러간다. 중간에서 시작해 몇 층만 올린다.

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void AutoStart()
        {
#if UNITY_SERVER
            return;   // Dedicated Server 는 가릴 화면이 없다.
#else
            if (UnityEngine.SceneManagement.SceneManager.GetActiveScene().name != AvatarSceneHandoff.WorldSceneName)
                return;
            if (FindFirstObjectByType<WorldEntryGate>() != null) return;

            var go = new GameObject("@WorldEntryGate");
            go.AddComponent<WorldEntryGate>();
#endif
        }

        void Start()
        {
            var car = FindGateElevator();
            if (car == null)
            {
                // 엘리베이터를 못 찾으면 가릴 방법이 없다. 조용히 막지 말고 알린 뒤 통과시킨다.
                Debug.LogError("[WorldEntryGate] 엘리베이터를 찾지 못해 입장 게이트를 건너뛴다");
                Destroy(gameObject);
                return;
            }

            BuildGateView(car);
            CacheDoors(car);
        }

        void Update()
        {
            _elapsed += Time.deltaTime;

            if (_opening)
            {
                if (_handedOver) AdvanceClosing();
                else AdvanceOpening();
                return;
            }

            AdvanceFloorDisplay();

            // 준비가 끝났다고 바로 열지 않는다. **11층 표시에 도착해야 연다** —
            // 5층에서 문이 열리면 엘리베이터라는 연출 자체가 깨진다.
            if (!_arrivalRequested && IsPlayerReady())
                RequestArrival("준비 완료");

            // 접속 시도조차 없으면 개발자가 월드 씬을 단독 실행한 것 — 가릴 이유가 없다.
            if (!_arrivalRequested && _elapsed >= _standaloneGraceSeconds && !IsConnectingOrConnected())
                RequestArrival("접속 시도 없음(단독 실행)");

            if (_arrivalRequested && _floor >= TopFloor)
            {
                BeginOpen(_openReason);
                return;
            }

            if (_elapsed >= _forceOpenSeconds)
            {
                // 여기까지 왔다는 것은 접속·스폰이 실패했다는 뜻이다. 반드시 드러낸다.
                var nm = NetworkManager.Singleton;
                Debug.LogError($"[WorldEntryGate] {_forceOpenSeconds}초 안에 월드 준비가 끝나지 않아 강제로 연다. " +
                               $"IsClient={(nm != null && nm.IsClient)} " +
                               $"PlayerObject={(nm != null && nm.LocalClient != null && nm.LocalClient.PlayerObject != null)}");
                // 도착 표시를 맞춰 두고 연다 — 문이 열리는데 5층이 떠 있으면 더 이상하다.
                _floor = TopFloor;
                if (_floorIndicator != null) _floorIndicator.SetNumber(TopFloor);
                BeginOpen("타임아웃 강제 개방");
            }
        }

        /// <summary>도착을 예약한다. 실제 개방은 층수 표시가 11 에 닿은 뒤다.</summary>
        void RequestArrival(string reason)
        {
            _arrivalRequested = true;
            _openReason = reason;
        }

        // ── 진행 표시 (FR-013) ────────────────────────────────────

        /// <summary>
        /// 접속 단계를 층수로 보여준다. 단계별 목표 층까지 올라가되,
        /// **실제로 준비되기 전에는 도착층(11)에 닿지 않는다** — 다 온 것처럼 속이지 않는다.
        /// </summary>
        void AdvanceFloorDisplay()
        {
            if (_floorIndicator == null) return;

            float target = TargetFloor();
            // 초당 약 3층씩 — 멈춰 보이지도, 순간이동하지도 않는 속도.
            // 도착이 예약되면 빠르게 올린다 — 대기 시간을 늘리려고 만든 연출이 아니다.
            float speed = _arrivalRequested ? 6f : 2.5f;
            _floor = Mathf.MoveTowards(_floor, target, speed * Time.deltaTime);
            _floorIndicator.SetNumber(Mathf.Clamp(Mathf.FloorToInt(_floor), 1, TopFloor));
        }

        float TargetFloor()
        {
            if (_opening || _arrivalRequested) return TopFloor;

            var nm = NetworkManager.Singleton;
            if (nm == null || !nm.IsClient) return StartFloor + 1f;   // 아직 접속 시작 전
            if (!nm.IsConnectedClient) return StartFloor + 3f;       // 전송 계층 연결·승인 대기
            return TopFloor - 1f;                             // 승인됨 — 스폰만 남았다
        }

        // ── 준비 판정 ─────────────────────────────────────────────

        /// <summary>씬 로드·접속 승인·오너 플레이어 스폰이 모두 끝난 상태.</summary>
        static bool IsPlayerReady()
        {
            var nm = NetworkManager.Singleton;
            return nm != null && nm.IsClient && nm.LocalClient != null && nm.LocalClient.PlayerObject != null;
        }

        static bool IsConnectingOrConnected()
        {
            var nm = NetworkManager.Singleton;
            return nm != null && (nm.IsClient || nm.IsListening);
        }

        // ── 게이트 화면 ───────────────────────────────────────────

        /// <summary>로비에 붙은 엘리베이터 칸 하나를 고른다.</summary>
        static Transform FindGateElevator()
        {
            var root = GameObject.Find("@Elevators");
            if (root == null || root.transform.childCount == 0) return null;
            // 가운데 칸이 가장 무난하다 — 좌우가 대칭이라 화면이 안정적이다.
            return root.transform.GetChild(root.transform.childCount / 2);
        }

        void BuildGateView(Transform car)
        {
            var bounds = CalculateBounds(car);

            // 문 쪽(-x)을 바라보는 시점. 카메라는 칸 안쪽에 두고 문에서 조금 떨어뜨린다.
            // 층수판이 문 위 높이(≈y56)에 있고 칸이 6m 높이라, 뒤쪽에서 올려다봐야 표시가 화면에 든다.
            var eye = new Vector3(bounds.max.x - 2.5f, bounds.min.y + 26f, bounds.center.z);

            _gateCam = new GameObject("GateCamera").AddComponent<Camera>();
            _gateCam.transform.SetParent(transform, false);
            _gateCam.transform.SetPositionAndRotation(eye, Quaternion.Euler(-44f, 270f, 0f));
            _gateCam.fieldOfView = 52f;   // 좁은 칸에 70도는 어안처럼 왜곡된다
            _gateCam.nearClipPlane = 0.3f;
            _gateCam.farClipPlane = 3000f;
            // 메인 카메라를 끄지 않고 위에 덮는다 — Camera.main 이 살아 있어야 PlayerCameraFollow 가 안 깨진다.
            _gateCam.depth = 100f;

            // 칸 안은 조명이 없어 캄캄하다. 게이트 동안만 쓰는 광원이라 열 때 같이 지운다.
            // (WebGL 화면당 광원 상한 32 — 현재 24 라 1개 추가는 안전하다. T-216)
            _gateLight = new GameObject("GateLight").AddComponent<Light>();
            _gateLight.transform.SetParent(transform, false);
            // 칸 중앙에 두면 문에 흰 스페큘러가 박힌다. 카메라 쪽으로 6 물리면 과노출 0% (실측).
            _gateLight.transform.position = new Vector3(bounds.center.x + 6f, bounds.min.y + 57f, bounds.center.z);
            _gateLight.transform.rotation = Quaternion.Euler(90f, 0f, 0f);   // 천장에서 아래로
            _gateLight.type = LightType.Spot;      // 포인트광은 문에 흰 핫스팟을 만든다
            _gateLight.spotAngle = 100f;
            _gateLight.range = 90f;
            _gateLight.intensity = 480f;
            _gateLight.color = new Color(1f, 0.94f, 0.86f);
            _gateLight.shadows = LightShadows.None;

            // 층수 표시기 — 문 위 벽(실측 x≈18.5)과 천장(y≈59) 사이.
            _floorIndicator = GateFloorIndicator.Build(
                transform,
                new Vector3(bounds.min.x + 3.6f, bounds.min.y + 56f, bounds.center.z),
                4f);   // 문 상단(y≈53)과 천장(y≈59) 사이 6유닛에 들어가는 크기
            if (_floorIndicator != null) _floorIndicator.SetNumber(1);
        }

        void CacheDoors(Transform car)
        {
            _doorLeft = car.Find("elevator-door-left");
            _doorRight = car.Find("elevator-door-right");
            if (_doorLeft == null || _doorRight == null)
            {
                Debug.LogWarning("[WorldEntryGate] 문 오브젝트를 찾지 못해 개방 연출 없이 전환한다");
                return;
            }

            _doorLeftClosed = _doorLeft.position;
            _doorRightClosed = _doorRight.position;

            // 문은 z 축으로 갈라진다. 각 문의 z 폭만큼 물러나면 통로가 열린다.
            var leftRenderer = _doorLeft.GetComponentInChildren<Renderer>();
            _doorTravel = leftRenderer != null ? leftRenderer.bounds.size.z : 9f;
        }

        // ── 개방 ──────────────────────────────────────────────────

        void BeginOpen(string reason)
        {
            _opening = true;
            _openProgress = 0f;
            Debug.Log($"[WorldEntryGate] 개방 — {reason} ({_elapsed:F1}s)");
            NotifyGateReady();
        }

        void AdvanceOpening()
        {
            _openProgress += Time.deltaTime / DoorSlideSeconds;
            var t = Mathf.Clamp01(_openProgress);
            SetDoorOpenAmount(Smoothstep(t));

            if (t < 1f) return;

            // 문이 다 열린 순간 게이트 화면을 걷는다. 여기서부터 플레이어가 월드를 본다.
            HandOverToWorld();
        }

        /// <summary>
        /// 카메라·조명·표시기를 걷어 월드를 드러낸다. 컴포넌트는 남겨 둔다 —
        /// 플레이어가 내린 뒤 **문을 다시 닫아야** 11층에 문 열린 엘리베이터가 방치되지 않는다.
        /// </summary>
        void HandOverToWorld()
        {
            if (_handedOver) return;
            _handedOver = true;
            _holdUntil = Time.time + DoorHoldSeconds;

            if (_gateCam != null) Destroy(_gateCam.gameObject);
            if (_gateLight != null) Destroy(_gateLight.gameObject);
            if (_floorIndicator != null) Destroy(_floorIndicator.gameObject);
        }

        void AdvanceClosing()
        {
            if (Time.time < _holdUntil) return;

            _closeProgress += Time.deltaTime / DoorSlideSeconds;
            var t = Mathf.Clamp01(_closeProgress);
            SetDoorOpenAmount(1f - Smoothstep(t));

            if (t >= 1f)
            {
                SetDoorOpenAmount(0f);   // 정확히 닫힌 자리로 스냅 — 실틈이 남지 않게
                Destroy(gameObject);
            }
        }

        void SetDoorOpenAmount(float amount)
        {
            if (_doorLeft == null || _doorRight == null) return;
            _doorLeft.position = _doorLeftClosed + new Vector3(0f, 0f, _doorTravel * amount);
            _doorRight.position = _doorRightClosed - new Vector3(0f, 0f, _doorTravel * amount);
        }

        static float Smoothstep(float t) => t * t * (3f - 2f * t);   // 문이 급출발하지 않는다

        static void NotifyGateReady()
        {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try
            {
                FestaNotifyWorldGateReady();
            }
            catch (System.Exception ex)
            {
                // 호스트 알림 실패가 게이트 개방을 막으면 안 된다 (FR-014).
                Debug.LogError($"[WorldEntryGate] onWorldGateReady 송신 실패: {ex.Message}");
            }
#else
            Debug.Log("[WorldEntryGate] onWorldGateReady → (에디터: 송신 생략)");
#endif
        }

        static Bounds CalculateBounds(Transform root)
        {
            var renderers = root.GetComponentsInChildren<Renderer>();
            if (renderers.Length == 0) return new Bounds(root.position, Vector3.one);
            var b = renderers[0].bounds;
            foreach (var r in renderers) b.Encapsulate(r.bounds);
            return b;
        }
    }
}
