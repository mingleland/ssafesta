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
        }

        void Update()
        {
            _nearest = FindNearest();
            UpdateHighlight();
            if (_nearest == null) return;
            if (Time.time - _lastTeleportTime < _cooldown) return;

            var kb = Keyboard.current;
            if (kb == null || !kb.fKey.wasPressedThisFrame) return;
            if (_nearest.destination == null) return;

            _movement.TeleportTo(_nearest.destination.position);
            if (_camera != null) _camera.SnapBehind();   // 카메라가 맵을 가로질러 날아오지 않게
            _lastTeleportTime = Time.time;
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
                bestDist = d;
                best = p;
            }
            return best;
        }

        // ── 하이라이트: 대상 발밑의 부드러운 링. 로컬 오브젝트라 본인 화면에만 보인다 ──

        void UpdateHighlight()
        {
            bool show = _nearest != null && Time.time - _lastTeleportTime >= _cooldown;
            if (!show) { _ring.Hide(); return; }
            var (pos, radius) = _nearest.HighlightFootprint();
            _ring.Show(pos, radius);
        }


        void OnGUI()
        {
            if (_nearest == null || Time.time - _lastTeleportTime < _cooldown) return;
            InteractPromptUI.DrawPrompt(_nearest.promptText);
        }
    }
}
