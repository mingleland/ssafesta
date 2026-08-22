using Festa.Network;
using Unity.Netcode;
using UnityEngine;
using UnityEngine.InputSystem;

namespace Festa.World
{
    /// <summary>
    /// Owner 플레이어의 포털 상호작용. 가장 가까운 <see cref="BoothPortal"/> 이
    /// 반경 안이면 화면 하단에 "[F] …" 프롬프트를 띄우고, F 입력 시 목적지로
    /// 텔레포트한다 (PlayerMovement 의 스폰과 같은 절차 재사용 — T-177).
    ///
    /// 프롬프트는 POC 관례대로 OnGUI 다 (DevConnectionHud 와 동일). 표시 전용이라
    /// React 오버레이 규정(텍스트 입력 UI)과 충돌하지 않는다.
    /// </summary>
    public class PortalInteractor : NetworkBehaviour
    {
        [SerializeField] float _cooldown = 0.6f;   // 도착 직후 반대편 포털 즉시 재발동 방지

        PlayerMovement _movement;
        PlayerCameraFollow _camera;
        BoothPortal _nearest;
        float _lastTeleportTime = -10f;

        public override void OnNetworkSpawn()
        {
            enabled = IsOwner;
            if (!IsOwner) return;
            _movement = GetComponent<PlayerMovement>();
            _camera = GetComponent<PlayerCameraFollow>();
        }

        void Update()
        {
            _nearest = FindNearest();
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
            float bestSq = float.MaxValue;
            var pos = transform.position;
            foreach (var p in BoothPortal.All)
            {
                float sq = (p.transform.position - pos).sqrMagnitude;
                if (sq > p.interactRadius * p.interactRadius || sq >= bestSq) continue;
                bestSq = sq;
                best = p;
            }
            return best;
        }

        void OnGUI()
        {
            if (_nearest == null || Time.time - _lastTeleportTime < _cooldown) return;

            float w = 380f, h = 44f;
            var rect = new Rect((Screen.width - w) * 0.5f, Screen.height * 0.72f, w, h);
            GUI.Box(rect, GUIContent.none);
            var style = new GUIStyle(GUI.skin.label)
            {
                alignment = TextAnchor.MiddleCenter,
                fontSize = 17,
                fontStyle = FontStyle.Bold,
            };
            GUI.Label(rect, $"[F]  {_nearest.promptText}", style);
        }
    }
}
