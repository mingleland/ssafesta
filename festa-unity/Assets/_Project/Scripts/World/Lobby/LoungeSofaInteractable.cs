// 11층 라운지의 SSAFY 글자 소파 — F 로 올라가 눕는다 (사용자 지시 2026-09-09 5번 사진, 09-10 눕기 클립 도착).
// 왜 있는가: 파란 글자 소파(S·S·A·F·Y, ssafy-11th-room/Group 44·46·50·52·54)는 걸어 다니는 장애물일 뿐이었다.
// F 를 누르면 소파 윗면으로 올라가 Sleep Anim Pack 의 눕기 클립 5종(LiePoseTable.SofaPoses) 중 하나를 무작위로 취한다.
// 이모트는 NetworkVariable(EmoteId) 로 동기화되므로 남에게도 같은 자세가 보이고, WASD 를 누르면 PlayerMovement 가
// EmoteId 를 None 으로 되돌려 자연히 일어난다.
//
// 배치 실측(2026-09-10): 글자 윗면 y=7.87, 글자 한 개는 x 로 14.5~20.5u · z 로 9~13u. 눕기 클립의 몸 길이는
// 1.44~1.64 m(≈20~23u) 이고 몸 길이 방향은 **루트의 오른쪽 축**이다. 그래서 루트 오른쪽을 글자의 긴 축(x)에 맞추고,
// 클립마다 몸 중심이 오른쪽으로 치우친 양(LiePoseTable.CenterX, ±0.13 m ≈ 1.8u)만큼 루트를 반대로 옮겨 몸을 글자 중앙에 둔다.
// 높이는 PlayerAvatarVisual 이 맡는다 — 이모트 시작 때 실측표만큼 내리고, 섞임이 끝나면 최하단을 소파 윗면에 다시 붙인다.
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
        [Tooltip("몸 길이 방향(월드). 글자 소파는 x 로 길다. 루트의 오른쪽 축을 여기에 맞춘다")]
        [SerializeField] Vector3 _lieAxis = Vector3.right;
        [Tooltip("소파 윗면에서 캡슐 발바닥을 띄우는 높이(u). CC 가 소파 콜라이더에 끼지 않게 — 떨어져서 정착한다")]
        [SerializeField] float _seatLift = 0.6f;
        [Tooltip("외형 배율(F_FullBody m → 월드 u) 을 못 읽을 때의 대체값. 조립된 아바타가 있으면 실제 localScale 을 쓴다")]
        [SerializeField] float _fallbackVisualScale = 14.2f;

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

            // 이미 누워 있으면 다른 자세로 바꾼다(같은 것을 다시 뽑지 않는다)
            var poses = LiePoseTable.SofaPoses;
            var pose = poses[Random.Range(0, poses.Length)];
            if (pose == player.EmoteId.Value) pose = poses[(System.Array.IndexOf(poses, pose) + 1) % poses.Length];

            var right = _lieAxis.sqrMagnitude > 0.001f ? _lieAxis.normalized : Vector3.right;
            var forward = Vector3.Cross(right, Vector3.up);          // right × up = forward (왼손 좌표계)
            var rot = Quaternion.LookRotation(forward, Vector3.up);

            float scale = VisualScale(po, _fallbackVisualScale);
            var seat = new Vector3(b.center.x, b.max.y + _seatLift, b.center.z) - right * (LiePoseTable.CenterX(pose) * scale);

            move.TeleportTo(seat);
            po.transform.rotation = rot;
            player.EmoteId.Value = pose;
            Debug.Log($"[LoungeSofa] {name} 위로 — seat={seat:F1} pose={pose} scale={scale:F2}");
        }

        /// <summary>
        /// 조립된 외형의 배율(플레이어 루트 기준). PlayerAvatarVisual 이 목표 키에 맞춰 'AvatarVisual_Modular' 노드의
        /// localScale 에 넣는데(실측 13.74), Animator 는 그 아래 노드라 localScale 이 1 이다 — lossyScale 비로 읽는다.
        /// </summary>
        static float VisualScale(NetworkObject po, float fallback)
        {
            var visual = po.GetComponentInChildren<PlayerAvatarVisual>();
            var anim = visual != null ? visual.CurrentAnimator : null;
            if (anim == null) return fallback;
            float rootScale = po.transform.lossyScale.y;
            float s = rootScale > 0.0001f ? anim.transform.lossyScale.y / rootScale : anim.transform.lossyScale.y;
            return s > 0.01f ? s : fallback;
        }
    }
}
