using System.Collections.Generic;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 부스 출입 포털. 외부 부스와 내부 공간 출구에 하나씩 놓이며,
    /// <see cref="PortalInteractor"/> 가 근접 시 키캡 프롬프트·하이라이트를 띄우고
    /// F 로 이동시킨다.
    ///
    /// 거리 판정은 비콘 지점이 아니라 **부스 실물 경계(boundsSource 렌더러)의
    /// 최근접점**을 기준으로 한다 — 부스 어느 면으로 다가가도 같은 거리에서 열린다.
    /// boundsSource 가 없으면(내부 출구 등) 포털 위치 기준으로 폴백한다.
    ///
    /// 정적 씬 오브젝트 — NetworkObject 없음. boothId 는 외부 슬롯 ↔ 내부 공간 ↔
    /// 백엔드 부스 식별자를 잇는 번호다 (1~12).
    /// </summary>
    public class BoothPortal : MonoBehaviour
    {
        public static readonly List<BoothPortal> All = new();

        [Tooltip("외부 슬롯·내부 공간 공통 번호 (1~12). 백엔드 부스 식별자와 1:1.")]
        public int boothId;

        [Tooltip("F 를 눌렀을 때 이동할 지점")]
        public Transform destination;

        /// <summary>
        /// 이벤트 지점인가 (GitLab #170). 켜면 <b>입장하지 않는다</b> — F 는 그 자리에서
        /// <c>WORLD_EVENT_INTERACT</c> 를 프런트로 쏘고, 화면(경품 상점·이벤트 설문)은 FE 가 그린다.
        ///
        /// <para><b>왜 씬 설정인가.</b> 슬롯 1 은 임대가 없어 <b>boothId 가 아예 존재하지 않는다</b> —
        /// 서버에 물어볼 대상이 없다. BE 가 <c>booth_slots.slot_type = EVENT</c> 를 추가하기로 했고
        /// (S15P21A604-615), <c>GET /api/v1/booth-slots</c> 의 <c>type</c> 으로 토큰 없이 나온다.
        /// 그것이 develop 에 오면 이 체크박스 대신 목록 1회 조회로 갈아탄다.</para>
        /// </summary>
        [Tooltip("이벤트 지점 — 입장하지 않고 프런트로 WORLD_EVENT_INTERACT 를 보낸다 (#170)")]
        public bool eventBooth;

        [Tooltip("프롬프트에 표시할 행동 문구 (예: '3번 부스 입장')")]
        public string promptText;

        [Tooltip("상호작용 가능 거리 (world unit, 1 m = 13.26). boundsSource 가 있으면 표면 기준.")]
        public float interactRadius = 12f;

        [Tooltip("거리 판정·하이라이트 대상인 부스 실물 렌더러. 비우면 포털 위치 기준.")]
        public Renderer boundsSource;

        [Tooltip("정면을 가진 상대(직원 등). requireFacing 이 켜져 있을 때만 시야 판정에 쓴다.")]
        public Transform facingSource;

        [Tooltip("정면 기준 좌우 허용 각도(도). 70 이면 앞쪽 140도 부채꼴.")]
        [Range(15f, 180f)] public float facingHalfAngle = 70f;

        [Tooltip("직원 시야 안에서만 열리게 할지. 2026-09-10 사용자 지시로 부스 입장은 사람이 아니라 부스에 붙었을 때가 됐다 — 기본 꺼짐.")]
        public bool requireFacing = false;

        [Tooltip("외곽선을 걸 트랜스폼. 비우면 boundsSource 의 트랜스폼.")]
        public Transform highlightRoot;

        [Tooltip("외곽선 두께(월드 유닛). 부스는 크므로 부스 오브젝트(0.35)보다 굵게 준다.")]
        [Range(0.05f, 3f)] public float outlineWidth = 0.9f;

        /// <summary>외곽선을 걸 트랜스폼 — 없으면 null(하이라이트 없음).</summary>
        public Transform ResolveHighlightRoot()
            => highlightRoot != null ? highlightRoot : (boundsSource != null ? boundsSource.transform : null);

        void OnEnable() => All.Add(this);
        void OnDisable() => All.Remove(this);

        Transform _resolved;
        bool _resolveFailed;

        /// <summary>
        /// 이동 목적지. 직렬화된 <see cref="destination"/> 가 있으면 그것, 없으면 <b>이름 규약으로 런타임 해결</b>한다 (S15P21A604-330).
        ///
        /// <para>왜 필요한가 — 외부 포털의 목적지는 <c>@BoothInteriors/Interior_NN/SpawnPoint</c> 로 <c>@Festival</c> 서브트리 밖이라,
        /// <c>@Festival</c> 을 프리팹으로 빼면 프리팹 에셋 안에서는 그 참조가 null 이 된다(씬 인스턴스 오버라이드로만 살아 있고,
        /// Revert·재인스턴스화 한 번에 12개가 조용히 끊긴다). 규약: 외부 포털(<c>Portal_Ext_NN</c>) → <c>Interior_NN/SpawnPoint</c>,
        /// 내부 출구(<c>Portal_Int_NN</c>) → <c>ReturnPoint_NN</c>. 실패하면 로그로 드러낸다 (T-24 원칙).</para>
        /// </summary>
        public Transform ResolveDestination()
        {
            if (destination != null) return destination;
            if (_resolved != null) return _resolved;
            if (_resolveFailed) return null;

            string nn = boothId.ToString("00");
            bool isInterior = transform.root.name == "@BoothInteriors";
            if (!isInterior)
            {
                var interior = GameObject.Find("Interior_" + nn);
                if (interior != null) { var sp = interior.transform.Find("SpawnPoint"); if (sp != null) _resolved = sp; }
            }
            else
            {
                var rp = GameObject.Find("ReturnPoint_" + nn);
                if (rp != null) _resolved = rp.transform;
            }

            if (_resolved == null)
            {
                _resolveFailed = true;
                Debug.LogError($"[BoothPortal] {name}(booth {boothId}) 목적지를 해결하지 못했다 — " +
                               (isInterior ? $"ReturnPoint_{nn}" : $"Interior_{nn}/SpawnPoint") + " 가 씬에 없다. 포털이 동작하지 않는다.", this);
            }
            return _resolved;
        }

        /// <summary>
        /// 상대의 <b>시야 안</b>에 있는가.
        ///
        /// <para><b>왜 방향을 보는가.</b> 거리만 보면 직원 등 뒤나 부스 안쪽에서도 말이 걸린다 —
        /// 사람에게 말을 거는 행동인데 상대가 나를 보고 있지 않아도 되는 셈이라 어색하다
        /// (S15P21A604-355 사용자 지적). 앞쪽 부채꼴로 좁히면 "마주 서야 대화가 열린다" 가
        /// 되어 직원이 서 있는 이유도 분명해진다.</para>
        ///
        /// <para>수평면에서만 잰다 — 위아래 각도까지 따지면 계단·경사에서 이유 없이 끊긴다.
        /// <see cref="facingSource"/> 가 없으면(내부 출구 등) 전방향 그대로다.</para>
        /// </summary>
        public bool IsInFacingArc(Vector3 pos)
        {
            // 2026-09-10: 부스 입장은 직원이 아니라 부스에 붙는 행동이 됐다 — 기본은 전방향이고,
            // 시야 판정이 필요한 대상만 requireFacing 을 켠다.
            if (!requireFacing || facingSource == null) return true;

            var forward = facingSource.forward; forward.y = 0f;
            var toPlayer = pos - facingSource.position; toPlayer.y = 0f;
            if (forward.sqrMagnitude < 1e-4f || toPlayer.sqrMagnitude < 1e-4f) return true;

            return Vector3.Angle(forward.normalized, toPlayer.normalized) <= facingHalfAngle;
        }

        /// <summary>
        /// 플레이어 위치에서 이 포털까지의 거리.
        ///
        /// <para><b>상대가 있으면 그 사람과의 거리를 잰다.</b> 부스 표면 기준으로 재면
        /// 직원에게서 멀찍이 떨어져 부스 모서리에 다가가도 프롬프트가 뜬다 — 사람이 아니라
        /// 구조물에 말을 거는 것처럼 보인다는 지적을 받았다 (S15P21A604-355).
        /// 대화는 사람과 하는 것이므로 사람이 기준이어야 한다.</para>
        ///
        /// <para>수평 거리만 쓴다 — 직원이 카운터 뒤 단 위에 서 있으면 높이 차 때문에
        /// 바로 앞에 서도 멀게 잡힌다.</para>
        ///
        /// <para>상대가 없는 포털(내부 출구 등)은 종전대로 부스 실물 표면 기준이다.</para>
        /// </summary>
        public float DistanceFrom(Vector3 pos)
        {
            // **부스 실물 표면이 1순위다** (2026-09-10 사용자 지시 — "npc 가 아니라 부스별로 딱 붙었을 때").
            // 직원 거리는 그가 서 있는 자리에만 판정을 몰아 줘서, 부스 정면에 서 있어도 직원과 어긋나면 안 열렸다.
            if (boundsSource != null)
                return Vector3.Distance(pos, boundsSource.bounds.ClosestPoint(pos));
            if (facingSource != null)
            {
                var flat = pos - facingSource.position;
                flat.y = 0f;
                return flat.magnitude;
            }
            return Vector3.Distance(pos, transform.position);
        }

        /// <summary>프롬프트를 띄울 월드 지점 — 부스 실물 상단, 없으면 포털 위 2 m.</summary>
        public Vector3 PromptAnchor()
        {
            if (boundsSource != null)
            {
                var b = boundsSource.bounds;
                return new Vector3(b.center.x, b.max.y + 6f, b.center.z);
            }
            return transform.position + Vector3.up * 20f;
        }

        /// <summary>하이라이트 링을 놓을 바닥 지점과 반경.</summary>
        public (Vector3 pos, float radius) HighlightFootprint()
        {
            if (boundsSource != null)
            {
                var b = boundsSource.bounds;
                return (new Vector3(b.center.x, 0.6f, b.center.z),
                        Mathf.Max(b.extents.x, b.extents.z) * 1.25f);
            }
            return (new Vector3(transform.position.x, 0.6f, transform.position.z), 14f);
        }
    }
}
