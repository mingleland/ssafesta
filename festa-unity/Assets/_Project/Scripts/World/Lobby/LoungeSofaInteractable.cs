// 11층 라운지의 SSAFY 글자 소파 — F 로 올라가 앉는다/눕는다 (사용자 지시 2026-09-09, 5번 사진).
// 왜 있는가: 파란 글자 소파(S·S·A·F·Y, ssafy-11th-room/Group 44·46·50·52·54)는 지금 걸어 다니는 장애물일 뿐이다.
// F 를 누르면 소파 윗면 중앙으로 올라가 방 안쪽을 보고 루프 이모트를 취한다. 이모트는 이미 NetworkVariable(EmoteId) 로
// 동기화되므로 다른 사람에게도 같은 자세가 보이고, WASD 를 누르면 PlayerMovement 가 EmoteId 를 None 으로 되돌려 자연히 일어난다.
// 눕기 클립은 아직 프로젝트에 없다(에셋 요청) — 도착 전까지는 SitGround(앉기) 로 대신한다. 클립이 오면 PlayerEmoteId.Lie 로 바꾼다.
using Festa.Booth;
using Festa.Content;
using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    [DisallowMultipleComponent]
    public sealed class LoungeSofaInteractable : MonoBehaviour, IBoothInteractable
    {
        [Tooltip("올라간 뒤 바라볼 방향(월드). 라운지 소파는 창가(x -166)에 있어 방 안쪽 +X 를 본다")]
        [SerializeField] Vector3 _facing = Vector3.right;
        [Tooltip("소파 윗면에서 캡슐 발바닥을 띄우는 높이(u). CC 가 소파 콜라이더에 끼지 않게")]
        [SerializeField] float _seatLift = 0.6f;
        [Tooltip("소파 위에서 취할 이모트. 눕기 클립이 오면 Lie 로 교체")]
        [SerializeField] PlayerEmoteId _pose = PlayerEmoteId.SitGround;

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0) gameObject.AddComponent<BoxCollider>();
            if (GetComponent<BoothInteractionTarget>() == null) gameObject.AddComponent<BoothInteractionTarget>();
            BoothInteractionInput.Ensure();
        }

        public void Interact()
        {
            var nm = NetworkManager.Singleton;
            var po = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            if (po == null) { Debug.LogWarning("[LoungeSofa] 로컬 플레이어가 없다 — 접속 전"); return; }
            var move = po.GetComponent<PlayerMovement>();
            var player = po.GetComponent<NetworkPlayer>();
            if (move == null || player == null) { Debug.LogWarning("[LoungeSofa] PlayerMovement/NetworkPlayer 가 없다"); return; }

            var rs = GetComponentsInChildren<Renderer>(true);
            if (rs.Length == 0) return;
            var b = rs[0].bounds; foreach (var r in rs) b.Encapsulate(r.bounds);
            var seat = new Vector3(b.center.x, b.max.y + _seatLift, b.center.z);

            move.TeleportTo(seat);
            if (_facing.sqrMagnitude > 0.001f) po.transform.rotation = Quaternion.LookRotation(_facing.normalized, Vector3.up);
            player.EmoteId.Value = _pose;
            Debug.Log($"[LoungeSofa] {name} 위로 — seat={seat:F1} pose={_pose}");
        }
    }
}
