using Festa.Network;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World
{
    /// <summary>
    /// Owner 플레이어의 포털 상호작용.
    ///
    /// - 부스 실물 경계 기준 거리로 가장 가까운 <see cref="BoothPortal"/> 을 찾고,
    /// - 대상 부스 위에 **키캡 스타일 프롬프트**([F] + 행동 문구)를 띄우고,
    /// - 대상 발밑에 **하이라이트 링**을 깐다 — 로컬 렌더링 전용이라 다른 접속자에게는
    ///   보이지 않는다 (네트워크로 나가는 상태 없음),
    /// - F 입력 시 목적지로 텔레포트한다 (스폰과 같은 절차 — T-177).
    /// </summary>
    public class PortalInteractor : NetworkBehaviour
    {
        [SerializeField] float _cooldown = 0.6f;   // 도착 직후 반대편 포털 즉시 재발동 방지

        PlayerMovement _movement;
        PlayerCameraFollow _camera;
        BoothPortal _nearest;
        float _lastTeleportTime = -10f;

        // 하이라이트 링·프롬프트는 InteractPromptUI / InteractRing 공용 구현을 쓴다 —
        // 부스 오브젝트(노트북·AI) 상호작용과 **같은 화면 언어**를 유지하기 위해서다
        // (S15P21A604-355). 여기서 따로 그리면 둘이 다시 어긋난다.
        readonly InteractRing _ring = new InteractRing();

        // 부스 구조물 외곽선 — 발밑 링만으로는 "이 부스가 지금 열린다" 가 잘 안 읽힌다는 사용자 지적(2026-09-10).
        // 부스 오브젝트(노트북·설문)와 **같은 표현**을 쓴다(OutlineHighlighter) — 색·두께만 부스 크기에 맞춘다.
        readonly OutlineHighlighter _outline = new OutlineHighlighter();

        [Tooltip("외곽선 색. 발밑 링(금색)과 같은 계열이라야 같은 기능으로 읽힌다.")]
        [SerializeField] Color _outlineColor = new Color(1f, 0.82f, 0.35f, 1f);

        void Awake() => useGUILayout = false;   // GUI.* 만 쓴다 — Layout 패스 제거로 OnGUI 호출·GC 절반 (QA #69)

        public override void OnNetworkSpawn()
        {
            enabled = IsOwner;
            if (!IsOwner) return;
            _movement = GetComponent<PlayerMovement>();
            _camera = GetComponent<PlayerCameraFollow>();
        }

        public override void OnNetworkDespawn()
        {
            _ring.Dispose();
            _outline.Dispose();
        }

        void Update()
        {
            // **화면이 열려 있으면 포털은 아무것도 하지 않는다.**
            //
            // 이 검사가 없어서, 부스 안 노트북·설문에 F 를 누르면 그 화면이 열리는 **동시에**
            // 같은 F 한 번이 포털에도 먹혀 축제장으로 튕겨 나갔다. 미니게임이 떠 있는 중에도
            // F 를 누르면 다른 부스로 순간이동했다 (2026-09-08 조사).
            // 프롬프트·하이라이트까지 같이 끈다 — 조작이 막힌 상태에서 [F] 알약만 떠 있으면
            // "눌러도 안 된다" 로 보인다 (S15P21A604-437 과 같은 이유).
            // 부스 오브젝트(노트북·슬롯머신 등)가 잡혀 있으면 포털은 이 프레임을 통째로 양보한다.
            // 전에는 프롬프트만 양보하고 F·하이라이트는 살아 있어서, 부스에 붙은 슬롯머신 앞에 서면
            // 옆 부스 외곽선이 같이 켜지고 F 한 번에 게임과 입장이 동시에 먹었다 (2026-09-10 사용자 지적).
            if (Festa.Integration.InputBridge.IsLocked || InteractionFocusCamera.IsFocused
                || Festa.Content.BoothInteractionInput.HasInteractTarget)
            {
                _nearest = null;
                UpdateHighlight();
                return;
            }

            _nearest = FindNearest();
            UpdateHighlight();
            if (_nearest == null) return;
            if (Time.time - _lastTeleportTime < _cooldown) return;

            var kb = Keyboard.current;
            if (kb == null || !kb.fKey.wasPressedThisFrame) return;
            var dest = _nearest.ResolveDestination();
            if (dest == null) return;

            // 미게시 부스는 들어가지 않는다 — 슬롯 2~12 처럼 게시본이 없으면 빈 셸만 있어 "고장" 으로 보인다
            // (2026-09-06 WebGL 실측, S15P21A604-453). 직원이 한 줄로 알려 주고 자리는 그대로.
            if (!IsEnterable(_nearest, dest, out var reason))
            {
                _toast = reason;
                _toastUntil = Time.unscaledTime + 2.5f;
                _lastTeleportTime = Time.time;   // 프롬프트를 잠깐 내려 연타를 막는다
                Debug.Log($"[PortalInteractor] booth {_nearest.boothId} 입장 차단 — {reason}");
                return;
            }

            _movement.TeleportTo(dest.position);
            // 목적지가 바라보는 방향으로 몸을 돌린다 — 부스 안 SpawnPoint 는 부스를(-z), ReturnPoint 는 축제를 향한다.
            // 전에는 들어오기 전 방향 그대로라 문 안에서 벽을 보고 서는 일이 있었다 (2026-09-06 v3 실측).
            _movement.transform.rotation = Quaternion.Euler(0f, dest.eulerAngles.y, 0f);
            if (_camera != null) _camera.SnapBehind(dest.eulerAngles.y);   // 카메라도 같은 방향 — 맵을 가로질러 날아오지 않게
            _lastTeleportTime = Time.time;

            // 방에 들어갈 때 그 슬롯만 다시 조회한다 — 게시본이 바뀌었으면 새로고침 없이 반영된다(QA #11).
            // 서명이 같으면 다시 짓지 않으므로 들어갈 때마다 깜빡이지 않는다. 출구 포털(Portal_Int_NN)은 대상이 아니다.
            if (_nearest.name.StartsWith("Portal_Ext"))
                Festa.Booth.WorldBoothPublishedBootstrap.RequestReload(_nearest.boothId);
        }

        string _toast;
        float _toastUntil;

        /// <summary>
        /// 이 포털로 들어갈 수 있는가. 외부 포털의 목적지(<c>Interior_NN/SpawnPoint</c>)와 같은 방에 있는
        /// <see cref="Festa.Booth.BoothRuntime"/> 이 게시본을 그리지 못했으면(IsLoaded false) 막는다.
        /// 게시본 일괄 조회(<see cref="Festa.Booth.WorldBoothPublishedBootstrap"/>)가 끝나기 전에는 판정하지 않고,
        /// 방에 BoothRuntime 이 없는 목적지(내부 출구 → ReturnPoint)는 항상 통과다.
        /// </summary>
        public static bool IsEnterable(BoothPortal portal, Transform dest, out string reason)
        {
            reason = null;
            if (portal == null || dest == null) return true;
            if (!Festa.Booth.WorldBoothPublishedBootstrap.Completed) return true;

            var room = dest.parent;
            var runtime = room != null ? room.GetComponentInChildren<Festa.Booth.BoothRuntime>(true) : null;
            if (runtime == null || runtime.IsLoaded) return true;

            reason = "이 부스는 아직 준비 중이에요 — 게시된 부스만 들어갈 수 있어요";
            return false;
        }

        BoothPortal FindNearest()
        {
            BoothPortal best = null;
            float bestDist = float.MaxValue;
            var pos = transform.position;
            foreach (var p in BoothPortal.All)
            {
                float d = p.DistanceFrom(pos);
                if (d > p.interactRadius || d >= bestDist) continue;
                // 상대가 있는 포털(직원)은 그 사람 시야 안에서만 열린다 — 등 뒤에서 말이
                // 걸리면 어색하다 (S15P21A604-355).
                if (!p.IsInFacingArc(pos)) continue;
                bestDist = d;
                best = p;
            }
            return best;
        }

        // ── 하이라이트: 대상 발밑의 부드러운 링. 로컬 오브젝트라 본인 화면에만 보인다 ──

        void UpdateHighlight()
        {
            bool show = _nearest != null && Time.time - _lastTeleportTime >= _cooldown;
            if (!show) { _ring.Hide(); _outline.Hide(); return; }
            var (pos, radius) = _nearest.HighlightFootprint();
            _ring.Show(pos, radius);
            // 외곽선 대상이 없는 포털(내부 출구의 DoorMat 등)은 링만 — Show(null) 은 Hide 와 같다.
            _outline.Show(_nearest.ResolveHighlightRoot(), _outlineColor, _nearest.outlineWidth);
        }


        void OnGUI()
        {
            if (_toast != null && Time.unscaledTime <= _toastUntil)
                InteractPromptUI.DrawToast(_toast);
            if (_nearest == null || Time.time - _lastTeleportTime < _cooldown) return;
            // 부스 오브젝트 프롬프트가 떠 있으면 양보한다 — 같은 자리에 알약 둘이 겹치면 둘 다 못 읽는다
            // (QA 2026-09-08 #26). F 자체는 양쪽 다 살아 있으므로 입장은 그대로 된다.
            if (Festa.Content.BoothInteractionInput.PromptShowing) return;
            InteractPromptUI.DrawPrompt(_nearest.promptText);
        }
    }
}
