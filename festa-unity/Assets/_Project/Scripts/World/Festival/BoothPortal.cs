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

        [Tooltip("프롬프트에 표시할 행동 문구 (예: '3번 부스 입장')")]
        public string promptText;

        [Tooltip("상호작용 가능 거리 (world unit, 1 m = 10). boundsSource 가 있으면 표면 기준.")]
        public float interactRadius = 30f;

        [Tooltip("거리 판정·하이라이트 대상인 부스 실물 렌더러. 비우면 포털 위치 기준.")]
        public Renderer boundsSource;

        [Tooltip("정면을 가진 상대(직원 등). 지정하면 그 시야 안에서만 상호작용된다. 비우면 전방향.")]
        public Transform facingSource;

        [Tooltip("정면 기준 좌우 허용 각도(도). 70 이면 앞쪽 140도 부채꼴.")]
        [Range(15f, 180f)] public float facingHalfAngle = 70f;

        void OnEnable() => All.Add(this);
        void OnDisable() => All.Remove(this);

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
            if (facingSource == null) return true;

            var forward = facingSource.forward; forward.y = 0f;
            var toPlayer = pos - facingSource.position; toPlayer.y = 0f;
            if (forward.sqrMagnitude < 1e-4f || toPlayer.sqrMagnitude < 1e-4f) return true;

            return Vector3.Angle(forward.normalized, toPlayer.normalized) <= facingHalfAngle;
        }

        /// <summary>플레이어 위치에서 이 포털(부스 실물 우선)까지의 거리.</summary>
        public float DistanceFrom(Vector3 pos)
        {
            if (boundsSource != null)
                return Vector3.Distance(pos, boundsSource.bounds.ClosestPoint(pos));
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
