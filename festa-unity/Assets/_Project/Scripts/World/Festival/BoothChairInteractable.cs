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
        int _fitAtFrame = -1;
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
            _fitAtFrame = Time.frameCount + FitDelayFrames;   // 크로스페이드가 끝나야 진짜 자세다
            Debug.Log($"[BoothChair] {name} 착석 — pose={pose} seatTop={SeatTopY:F2}");
        }

        /// <summary>자세가 자리잡기를 기다리는 프레임 수. 크로스페이드가 섞이는 중에 재면 중간값이 나온다.</summary>
        const int FitDelayFrames = 24;

        /// <summary>
        /// <b>앉은 높이를 재서 맞춘다.</b> 상수로 역산하지 않는다 — 클립마다 앉은 높이가 다르고,
        /// 리타게팅·키 보정까지 곱해지면 표만으로는 맞지 않는다(사용자 지적 2026-09-18, 세 번째).
        ///
        /// <para>재는 것은 <b>엉덩이 살의 최저점</b>이다 — 골반 본에서 아래로 한 뼘 안쪽의 정점 중 가장 낮은 것.
        /// 발·정강이는 좌면 밖으로 나가므로 전체 최저점을 쓰면 다리에 맞춰져 엉덩이가 뜬다.</para>
        /// </summary>
        void FitToSeat()
        {
            if (_sitter == null || _sitterVisual == null) return;
            var anim = _sitterVisual.CurrentAnimator;
            var hips = anim != null ? anim.GetBoneTransform(HumanBodyBones.Hips) : null;
            if (hips == null) return;

            float band = Mathf.Abs(_sitter.transform.lossyScale.y) * 0.25f;   // 골반 아래 25 cm 안쪽
            float hipY = hips.position.y;
            float buttY = float.PositiveInfinity;
            foreach (var smr in _sitter.GetComponentsInChildren<SkinnedMeshRenderer>(true))
            {
                if (smr == null || !smr.enabled) continue;
                var mesh = new Mesh();
                smr.BakeMesh(mesh, true);
                var m = smr.transform.localToWorldMatrix;
                foreach (var v in mesh.vertices)
                {
                    var w = m.MultiplyPoint3x4(v);
                    if (w.y > hipY || w.y < hipY - band) continue;
                    if (w.y < buttY) buttY = w.y;
                }
                Destroy(mesh);
            }
            if (float.IsInfinity(buttY)) return;

            float lift = SeatTopY - buttY;
            _sitterVisual.SetSeatLift(lift);
            Debug.Log($"[BoothChair] {name} 앉은 높이 맞춤 — 엉덩이 {buttY:F2} → 좌면 {SeatTopY:F2} (보정 {lift:F2}u)");
        }

        void Update()
        {
            if (_sitter == null) return;
            var np = _sitter.GetComponent<NetworkPlayer>();
            // 이동·점프로 이모트가 풀리면 PlayerMovement 가 None 으로 되돌린다. 그 순간이 "일어났다" 다.
            if (np != null && SitPoseTable.IsSit(np.EmoteId.Value))
            {
                _sitConfirmed = true;
                if (_fitAtFrame > 0 && Time.frameCount >= _fitAtFrame) { _fitAtFrame = -1; FitToSeat(); }
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
            _fitAtFrame = -1;
            if (_sitterVisual != null) { _sitterVisual.SetSeatLift(0f); _sitterVisual = null; }
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
        /// 일어설 자리. 좌석 정면 바닥이 기본이고, 그 자리가 막혀 있으면 앉기 직전에 서 있던 자리로 돌아간다 —
        /// 벽을 등진 의자에서 정면으로 내보내면 벽 안에 박힌다.
        /// </summary>
        Vector3 ResolveExitPoint(Vector3 standingPosition, Bounds b)
        {
            var front = new Vector3(b.center.x, b.min.y, b.center.z) + SeatForward() * _exitDistance;
            float radius = 2.9f;                        // 플레이어 캡슐 반지름(선 자세)
            var probe = front + Vector3.up * (radius + 0.5f);
            if (!Physics.CheckSphere(probe, radius, ~0, QueryTriggerInteraction.Ignore)) return front;
            return new Vector3(standingPosition.x, b.min.y, standingPosition.z);
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

            foreach (var p in FindObjectsByType<NetworkPlayer>(FindObjectsInactive.Exclude, FindObjectsSortMode.None))
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
