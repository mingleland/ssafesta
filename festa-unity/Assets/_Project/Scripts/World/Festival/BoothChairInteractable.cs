// 부스 안 의자·스툴에 앉는다 — F 로 앉고 WASD 로 일어난다 (사용자 지시 2026-09-18).
// 이 파일이 있는 이유: 부스 인테리어의 좌석들은 걸어 다니는 장애물일 뿐이었다. 상담 부스에 앉아
// 이야기하는 그림이 되려면 앉을 수 있어야 한다.
using Festa.Booth;
using Festa.Content;
using Festa.Network;
using Unity.Netcode;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 좌석 하나. 앉는 자리·방향·점유 판정을 이 좌석의 실물 크기에서 뽑는다.
    ///
    /// <para><b>사거리는 일부러 짧다.</b> 부스 안은 좁아 기본 사거리(10u)면 책상·노트북·의자가 한꺼번에
    /// 잡혀 무엇이 잡힐지 예측할 수 없다. "딱 붙었을 때만" 이라는 요구대로 표면 기준 4u(0.30 m)로 둔다.</para>
    ///
    /// <para><b>앉는 동안 이 좌석과의 충돌을 끈다.</b> 이것이 없으면 세 증상이 한꺼번에 난다 —
    /// 앉는 순간 캡슐이 의자 상자 안으로 들어가 <see cref="CharacterController"/> 가 밖으로 밀어내므로
    /// ① 몸이 좌석 옆으로 밀려 앉고 ② 일어날 때 의자에 껴서 못 움직이고 ③ 뛰어 들어가면 크게 튕긴다
    /// (사용자 보고 2026-09-18). 로컬 물리라 남의 화면에는 영향이 없다.</para>
    ///
    /// <para><b>한 좌석에 한 사람.</b> 판정은 이미 복제되는 값만으로 한다 — 착석 이모트(<c>EmoteId</c>)는
    /// NetworkVariable 이고 위치는 NetworkTransform 이라, 좌석에 새 네트워크 상태를 달지 않아도 모든
    /// 화면에서 같은 답이 나온다. 라운지 소파가 쓰는 방식과 같다.</para>
    ///
    /// <para>점유 중이면 <b>프롬프트도 뜨지 않는다</b> — 눌러도 안 되는 버튼을 띄우면 고장으로 읽힌다
    /// (<c>BoothInteractionInput.TemporarilyBlocked</c>).</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class BoothChairInteractable : MonoBehaviour, IBoothInteractable
    {
        /// <summary>좌석 표면 기준 사거리(u). 1 m = 13.26u — 4u 는 0.30 m 다.</summary>
        public const float InteractDistance = 4f;

        [Tooltip("좌면 높이를 좌석 전체 높이에서 추정하는 비율. 등받이 없는 스툴은 1, 등받이 의자는 그보다 낮다")]
        [SerializeField] float _seatHeightRatio = 1f;
        [Tooltip("외형 배율(모델 m → 월드 u) 을 못 읽을 때의 대체값")]
        [SerializeField] float _fallbackVisualScale = 13.26f;
        [Tooltip("일어날 때 내보낼 거리(u). 좌석 앞쪽으로 이만큼 떨어뜨린다")]
        [SerializeField] float _exitDistance = 8f;

        Bounds _bounds;
        bool _boundsReady;

        // 앉아 있는 로컬 플레이어. 일어나는 것을 여기서 지켜본다 — 좌석이 자기 충돌 해제를 되돌릴
        // 책임을 지는 편이 안전하다. 플레이어 쪽에 두면 어느 의자였는지 따로 기억해야 한다.
        NetworkObject _sitter;
        Collider[] _ownColliders;
        Collider[] _sitterColliders;
        Vector3 _exitPoint;
        bool _sitConfirmed;
        int _sitFrame = -1;
        int _fitIndex;
        Mesh _bakeBuffer;
        PlayerAvatarVisual _sitterVisual;

        /// <summary>좌면 비율을 붙이는 쪽이 정한다 — 스툴과 등받이 의자가 다르다.</summary>
        public void SetSeatHeightRatio(float ratio) => _seatHeightRatio = Mathf.Clamp01(ratio);

        void Awake()
        {
            if (GetComponentsInChildren<Collider>(true).Length == 0) gameObject.AddComponent<BoxCollider>();
            var target = GetComponent<BoothInteractionTarget>();
            if (target == null) target = gameObject.AddComponent<BoothInteractionTarget>();
            target.Configure(InteractDistance, true);
            BoothInteractionInput.Ensure();
        }

        void OnDisable() => ReleaseSitter(false);

        void OnDestroy()
        {
            if (_bakeBuffer != null) { Destroy(_bakeBuffer); _bakeBuffer = null; }
        }

        /// <summary>좌면 윗면의 월드 높이. 등받이 의자는 전체 높이의 일부만 좌면이라 비율로 자른다.</summary>
        public float SeatTopY => Bounds().min.y + Bounds().size.y * Mathf.Clamp01(_seatHeightRatio);

        public Bounds Bounds()
        {
            if (_boundsReady) return _bounds;
            var rs = GetComponentsInChildren<Renderer>(true);
            if (rs.Length == 0) { _bounds = new Bounds(transform.position, Vector3.one); _boundsReady = true; return _bounds; }
            var b = rs[0].bounds;
            foreach (var r in rs) b.Encapsulate(r.bounds);
            _bounds = b; _boundsReady = true;
            return _bounds;
        }

        public void Interact()
        {
            var nm = NetworkManager.Singleton;
            var po = nm != null && nm.LocalClient != null ? nm.LocalClient.PlayerObject : null;
            if (po == null) { Debug.LogWarning("[BoothChair] 로컬 플레이어가 없다 — 접속 전"); return; }
            var move = po.GetComponent<PlayerMovement>();
            var player = po.GetComponent<NetworkPlayer>();
            if (move == null || player == null) { Debug.LogWarning("[BoothChair] PlayerMovement/NetworkPlayer 가 없다"); return; }
            if (SitPoseTable.IsSit(player.EmoteId.Value)) return;   // 앉은 채 다시 F — 재텔레포트하지 않는다

            if (IsOccupied(po, out var occupantName))
            {
                BoothInteractionInput.Toast($"{occupantName} 님이 앉아 있어요");
                return;
            }

            var pose = SitPoseTable.ChairPoses[Random.Range(0, SitPoseTable.ChairPoses.Length)];
            // **루트는 바닥에 둔다.** 좌면 기준으로 내려 봤자 중력이 캡슐을 바닥까지 끌어내려
            // 계산이 무의미하다 — 실측 2026-09-18: 의도한 −1.45 가 아니라 0.22 에 있었다.
            // 앉은 높이는 자세가 자리잡은 뒤 **직접 재서 외형을 올린다**(FitToSeat).
            var b = Bounds();
            var seat = new Vector3(b.center.x, b.min.y, b.center.z);

            // 일어날 자리는 앉기 **전에** 정해 둔다. 좌석 정면 바닥이고, 막혀 있으면 원래 서 있던 자리로.
            _exitPoint = ResolveExitPoint(po.transform.position, b);

            BindSitter(po);
            move.TeleportTo(seat);
            po.transform.rotation = Quaternion.LookRotation(SeatForward(), Vector3.up);
            player.EmoteId.Value = pose;
            _sitterVisual = po.GetComponentInChildren<PlayerAvatarVisual>();
            _sitFrame = Time.frameCount;
            _fitIndex = 0;
            Debug.Log($"[BoothChair] {name} 착석 — pose={pose} seatTop={SeatTopY:F2}");
        }

        /// <summary>
        /// 착석 후 다시 재는 시점(프레임, 착석 기준). <b>한 번만 재지 않는다.</b> 크로스페이드가 끝난 뒤
        /// 한 번에 재서 적용하면 그 프레임에 몸이 통째로 움직여 순간이동처럼 보였다
        /// (사용자 지적 2026-09-18). 자세가 자리잡는 동안 몇 번 더 재면 목표가 조금씩 옮겨가고,
        /// 외형은 그 목표를 부드럽게 따라간다.
        ///
        /// <para>첫 항목이 0 인 것은 "되는 대로 최대한 빨리" 라는 뜻이다. 실제 시작 시점은
        /// 프레임 수가 아니라 <b>애니메이션 전환이 끝났는가</b>로 정한다 — 전환 중에 재면 아직 선
        /// 자세인 골반을 기준으로 삼아 보정이 10u 넘게 빗나간다.</para>
        /// </summary>
        static readonly int[] FitFrames = { 0, 6, 14, 26, 42 };

        /// <summary>
        /// <b>앉은 높이를 재서 맞춘다.</b> 상수로 역산하지 않는다 — 클립마다 앉은 높이가 다르고,
        /// 리타게팅·키 보정까지 곱해지면 표만으로는 맞지 않는다(사용자 지적 2026-09-18, 세 번째).
        ///
        /// <para>재는 것은 <b>골반·허벅지 본에 실제로 가중된 엉덩이 정점</b>이다. 머리카락·상의·신발은
        /// 골반 주변을 지나도 제외한다. 또 애니메이션의 루트 모션이 골반을 좌석 중심에서 밀어내므로
        /// 높이뿐 아니라 골반의 수평 위치도 좌석 중심에 맞춘다.</para>
        ///
        /// <para><b>재는 몸은 이미 보정이 들어간 몸이다.</b> 그래서 측정값에서 적용 중인 보정
        /// (<see cref="PlayerAvatarVisual.SeatOffsetWorld"/>)을 빼 보정 전 자세로 환산한 뒤 목표를 낸다.
        /// 빼지 않으면 두 번째 측정이 "이미 좌면에 닿아 있다" 로 읽어 보정을 스스로 되돌리고, 필요한
        /// 양의 절반에 수렴한다.</para>
        /// </summary>
        /// <returns>실제로 재서 목표를 갱신했으면 <c>true</c>. 아직 전환 중이거나 잴 수 없으면 <c>false</c>.</returns>
        bool FitToSeat()
        {
            if (_sitter == null || _sitterVisual == null) return false;
            var anim = _sitterVisual.CurrentAnimator;
            var hips = anim != null ? anim.GetBoneTransform(HumanBodyBones.Hips) : null;
            if (hips == null) return false;

            // **착석 클립이 실제로 재생 중일 때만 잰다.** 전환 여부만 보면 부족하다 — 이모트는
            // NetworkVariable 을 거쳐 한두 프레임 뒤에 CrossFade 로 들어가므로, 앉은 직후에는 아직
            // 선 자세 상태이면서 `IsInTransition` 도 false 다. 그때 재면 골반이 한참 위에 있어
            // 보정이 −4.4u 로 나오고, 몸이 27 cm 내려갔다 되돌아온다 — 프레임별 실측 2026-09-18.
            if (anim.IsInTransition(0)) return false;
            int state = anim.GetCurrentAnimatorStateInfo(0).shortNameHash;
            if (state != s_sitChair1State && state != s_sitChair2State) return false;

            var leftThigh = anim.GetBoneTransform(HumanBodyBones.LeftUpperLeg);
            var rightThigh = anim.GetBoneTransform(HumanBodyBones.RightUpperLeg);
            float visualScale = Mathf.Abs(anim.transform.lossyScale.y);
            float band = visualScale * 0.35f;        // 모델 35 cm — 엉덩이와 허벅지 윗부분
            float radius = visualScale * 0.14f;      // 골반 중심 14 cm — 무릎 쪽 허벅지는 제외
            float hipY = hips.position.y;
            float buttY = float.PositiveInfinity;
            if (_bakeBuffer == null) _bakeBuffer = new Mesh { name = "BoothChairSeatProbe" };
            foreach (var smr in _sitter.GetComponentsInChildren<SkinnedMeshRenderer>(true))
            {
                if (smr == null || !smr.enabled || smr.sharedMesh == null) continue;
                var mesh = _bakeBuffer;
                smr.BakeMesh(mesh, true);
                var m = smr.transform.localToWorldMatrix;
                var vertices = mesh.vertices;
                var weights = smr.sharedMesh.boneWeights;
                var bones = smr.bones;
                bool weighted = weights != null && weights.Length == vertices.Length;
                for (int i = 0; i < vertices.Length; i++)
                {
                    if (weighted && !IsSeatContactWeight(weights[i], bones, hips, leftThigh, rightThigh)) continue;
                    var w = m.MultiplyPoint3x4(vertices[i]);
                    if (w.y > hipY || w.y < hipY - band) continue;
                    var planar = w - hips.position; planar.y = 0f;
                    if (planar.sqrMagnitude > radius * radius) continue;
                    if (w.y < buttY) buttY = w.y;
                }
            }
            if (float.IsInfinity(buttY)) return false;

            // 보정 전 자세로 환산한다 — 이 값은 easing 이 얼마나 진행됐든 같다.
            var applied = _sitterVisual.SeatOffsetWorld;
            float rawButtY = buttY - applied.y;
            var rawHip = hips.position - applied;
            var center = Bounds().center;
            var offset = new Vector3(center.x - rawHip.x, SeatTopY - rawButtY, center.z - rawHip.z);
            _sitterVisual.SetSeatOffset(offset);
            if (_fitIndex >= FitFrames.Length - 1)
                Debug.Log($"[BoothChair] {name} 좌석 맞춤 — 엉덩이 {buttY:F2}(보정전 {rawButtY:F2}) → 좌면 {SeatTopY:F2}, " +
                          $"골반 수평 ({hips.position.x:F2},{hips.position.z:F2}) → ({center.x:F2},{center.z:F2}), 보정={offset}");
            return true;
        }

        static bool IsSeatContactWeight(BoneWeight weight, Transform[] bones, Transform hips, Transform leftThigh, Transform rightThigh)
        {
            return IsTargetBone(weight.boneIndex0, weight.weight0, bones, hips, leftThigh, rightThigh)
                || IsTargetBone(weight.boneIndex1, weight.weight1, bones, hips, leftThigh, rightThigh)
                || IsTargetBone(weight.boneIndex2, weight.weight2, bones, hips, leftThigh, rightThigh)
                || IsTargetBone(weight.boneIndex3, weight.weight3, bones, hips, leftThigh, rightThigh);
        }

        static bool IsTargetBone(int index, float weight, Transform[] bones, Transform hips, Transform leftThigh, Transform rightThigh)
        {
            if (weight < 0.2f || bones == null || index < 0 || index >= bones.Length) return false;
            var bone = bones[index];
            return bone == hips || bone == leftThigh || bone == rightThigh;
        }

        // 애니메이터 상태 이름은 PlayerEmoteId 와 짝을 이룬다 (Emote_SitChair1 / Emote_SitChair2).
        static readonly int s_sitChair1State = Animator.StringToHash("Emote_" + PlayerEmoteId.SitChair1);
        static readonly int s_sitChair2State = Animator.StringToHash("Emote_" + PlayerEmoteId.SitChair2);

        void Update()
        {
            if (_sitter == null) return;
            var np = _sitter.GetComponent<NetworkPlayer>();
            // 이동·점프로 이모트가 풀리면 PlayerMovement 가 None 으로 되돌린다. 그 순간이 "일어났다" 다.
            if (np != null && SitPoseTable.IsSit(np.EmoteId.Value))
            {
                _sitConfirmed = true;
                if (_sitFrame > 0 && _fitIndex < FitFrames.Length
                    && Time.frameCount - _sitFrame >= FitFrames[_fitIndex])
                {
                    if (FitToSeat()) _fitIndex++;
                }
                return;
            }

            // **앉은 것을 한 번이라도 본 뒤에만 내보낸다.** 이 확인이 없으면, 앉기가 성립하지 않았거나
            // 다른 자세(소파 눕기 등)로 덮인 경우에도 "일어났다" 로 읽고 사람을 의자 앞으로 끌어온다 —
            // 실제로 라운지에 누운 플레이어가 부스로 순간이동했다(2026-09-18 실측).
            // 이미 멀리 갔으면 자리만 정리하고 건드리지 않는다.
            bool nearby = _sitter != null
                && Vector3.Distance(_sitter.transform.position, Bounds().center) <= OccupantHeight;
            ReleaseSitter(_sitConfirmed && nearby);
        }

        /// <summary>앉는 동안 이 좌석과의 충돌을 끈다. 끈 대상을 기억해 그대로 되돌린다.</summary>
        void BindSitter(NetworkObject po)
        {
            ReleaseSitter(false);
            _ownColliders = GetComponentsInChildren<Collider>(true);
            _sitterColliders = po.GetComponentsInChildren<Collider>(true);
            SetIgnore(true);
            _sitter = po;
            _sitConfirmed = false;
        }

        /// <summary>충돌을 되돌리고, 필요하면 좌석 밖으로 내보낸다.</summary>
        void ReleaseSitter(bool teleportOut)
        {
            if (_sitter == null) { SetIgnore(false); _ownColliders = null; _sitterColliders = null; return; }

            if (teleportOut)
            {
                // **충돌을 되돌리기 전에** 내보낸다. 순서를 바꾸면 되살아난 충돌이 의자 안의 캡슐을
                // 밀어내며 튕긴다 — 사용자가 본 "일어나면 껴서 안 움직인다" 가 이것이다.
                var move = _sitter.GetComponent<PlayerMovement>();
                if (move != null) move.TeleportTo(_exitPoint);
                else _sitter.transform.position = _exitPoint;
            }
            SetIgnore(false);
            _sitter = null;
            _sitConfirmed = false;
            _sitFrame = -1;
            _fitIndex = 0;
            if (_sitterVisual != null) { _sitterVisual.ClearSeatOffsetImmediate(); _sitterVisual = null; }
            _ownColliders = null;
            _sitterColliders = null;
        }

        void SetIgnore(bool ignore)
        {
            if (_ownColliders == null || _sitterColliders == null) return;
            foreach (var a in _ownColliders)
            {
                if (a == null) continue;
                foreach (var c in _sitterColliders)
                {
                    if (c == null || c.isTrigger) continue;
                    Physics.IgnoreCollision(a, c, ignore);
                }
            }
        }

        /// <summary>
        /// 일어설 자리. 테이블을 바라보는 좌석에서 정면으로 내보내면 테이블 안·위에 서게 된다.
        /// 좌석의 좌우 중 앉기 전에 서 있던 쪽과 가까운 후보를 먼저 보고, 플레이어 캡슐 전체가 비는
        /// 바닥 위치만 고른다. 양쪽이 막혔을 때만 원래 서 있던 자리로 돌아간다.
        /// </summary>
        Vector3 ResolveExitPoint(Vector3 standingPosition, Bounds b)
        {
            var center = new Vector3(b.center.x, b.min.y, b.center.z);
            var side = transform.right; side.y = 0f;
            if (side.sqrMagnitude < 0.0001f) side = Vector3.right;
            side.Normalize();

            float sign = Vector3.Dot(standingPosition - center, side) >= 0f ? 1f : -1f;
            var first = center + side * (_exitDistance * sign);
            var second = center - side * (_exitDistance * sign);
            if (CanStandAt(first, b.min.y)) return first;
            if (CanStandAt(second, b.min.y)) return second;

            var fallback = new Vector3(standingPosition.x, b.min.y, standingPosition.z);
            return CanStandAt(fallback, b.min.y) ? fallback : center + side * (_exitDistance * sign);
        }

        /// <summary>
        /// 선 자세의 플레이어 캡슐이 그 자리에 들어가는가. <b>온몸 높이로 본다</b> — 예전에는 바닥
        /// 근처 구(球) 하나만 검사해서 허리 높이의 테이블을 놓쳤고, 그래서 일어설 때 테이블 위에 섰다
        /// (사용자 보고 2026-09-18).
        ///
        /// <para>사람 레이어는 뺀다. 이 검사는 앉기 <b>전에</b> 돌아서 그때 내가 서 있는 자리가 곧 후보
        /// 지점 옆인데, 내 캡슐이 나를 막아 반대편으로 넘겨 버렸다 — 일어설 때 좌석을 가로질러 튄다.
        /// 사람끼리 겹치는 것은 <c>PlayerMovement</c> 의 부드러운 분리가 따로 푼다.</para>
        /// </summary>
        static bool CanStandAt(Vector3 point, float floorY)
        {
            const float radius = 2.9f;
            const float height = 22.4f;
            const int playerLayer = 8;
            var bottom = new Vector3(point.x, floorY + radius + 0.25f, point.z);
            var top = new Vector3(point.x, floorY + height - radius, point.z);
            return !Physics.CheckCapsule(bottom, top, radius, ~(1 << playerLayer), QueryTriggerInteraction.Ignore);
        }

        /// <summary>앉은 사람이 바라볼 방향. 배치된 좌석은 앉는 쪽을 정면으로 두고 세워져 있다.</summary>
        Vector3 SeatForward()
        {
            var f = transform.forward; f.y = 0f;
            return f.sqrMagnitude > 0.0001f ? f.normalized : Vector3.forward;
        }

        /// <summary>
        /// 이 좌석에 <b>다른 사람</b>이 앉아 있는가. 착석 이모트를 켠 채 좌석 위 상자 안에 있으면 점유로 본다.
        /// 이모트만 보면 다른 의자에 앉은 사람까지 잡히고, 위치만 보면 앞에 선 사람이 자리를 막는다.
        /// </summary>
        public bool IsOccupied(NetworkObject self, out string occupantName)
        {
            occupantName = null;
            if (NetworkManager.Singleton == null) return false;

            var b = Bounds();
            var area = new Bounds(
                new Vector3(b.center.x, b.min.y + OccupantHeight * 0.5f, b.center.z),
                new Vector3(Mathf.Max(b.size.x, 4f), OccupantHeight, Mathf.Max(b.size.z, 4f)));

            // 사람 목록은 프레임 단위로 공유한다 — 의자마다 씬을 다시 훑으면 수십 번이 된다 (S15P21A604-970).
            foreach (var p in NetworkPlayerScan.All())
            {
                if (p == null || (self != null && p.gameObject == self.gameObject)) continue;
                if (!SitPoseTable.IsSit(p.EmoteId.Value)) continue;
                if (!area.Contains(p.transform.position)) continue;
                var nick = p.Nickname.Value.ToString();
                occupantName = string.IsNullOrWhiteSpace(nick) ? "다른 사용자" : nick;
                return true;
            }
            return false;
        }

        /// <summary>점유 판정 상자의 높이(u). 앉은 몸은 낮게 깔리므로 한 사람 키면 충분하다.</summary>
        const float OccupantHeight = 22.5f;

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
