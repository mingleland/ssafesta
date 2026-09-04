using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 부스 안을 "영업 중" 으로 밝히는 조명. **프리팹에 함께 들어간다** — 슬롯의 부스가
    /// 교체돼도 조명이 따라오게 하기 위해서다.
    ///
    /// <para><b>왜 값을 런타임에 계산하나.</b> 포인트 라이트의 <c>range</c> 는 **부모 트랜스폼
    /// 스케일을 따라가지 않는다**(실측). 프리팹은 미터 단위로 저작돼 있고 슬롯이 ×13 쯤으로
    /// 키우는데, 프리팹 로컬 기준으로 range 를 박으면 월드에서는 0.2~0.9 m 짜리가 되어
    /// **사실상 꺼진 것과 같다** — 실제로 그렇게 나갔다가 "조명이 없어 보인다" 는 보고를 받았다
    /// (S15P21A604-355).</para>
    ///
    /// <para>그래서 상수를 박지 않고, 켜질 때 **자기 부스의 월드 바운즈를 재서** range·세기를
    /// 정한다. 부스 크기가 제각각이고 슬롯 스케일도 바뀔 수 있으므로 이쪽이 유일하게 안 깨진다.</para>
    /// </summary>
    [RequireComponent(typeof(Light))]
    [DisallowMultipleComponent]
    public sealed class BoothInteriorLight : MonoBehaviour
    {
        [Tooltip("부스 최대 치수 대비 range 배율")]
        [SerializeField] float _rangeFactor = 1.15f;

        [Tooltip("range 1 unit 당 세기 — 넓은 부스일수록 더 밝아야 같은 밝기로 보인다")]
        [SerializeField] float _intensityPerUnit = 14f;

        [Tooltip("부스 높이 대비 조명을 매다는 위치 (0=바닥, 1=지붕)")]
        [SerializeField, Range(0.1f, 1f)] float _heightRatio = 0.62f;

        [Tooltip("직원 머리에 닿는 세기 상한(intensity/거리²). 넘으면 세기를 낮춘다.")]
        [SerializeField] float _maxStaffExposure = 1.0f;

        [Tooltip("직원 머리와 이 거리 안으로 붙으면 조명을 위로 띄운다.")]
        [SerializeField] float _minStaffDistance = 9f;

        void Start() => Fit();

        /// <summary>
        /// 직원 얼굴이 하얗게 타지 않게 노출을 제한한다.
        ///
        /// <para><b>왜 런타임에 해야 하나.</b> <see cref="Fit"/> 는 켜질 때마다 부스 크기로
        /// 세기와 위치를 다시 계산한다. 그래서 에디터에서 값을 아무리 낮춰 놔도 플레이를
        /// 누르는 순간 되돌아간다 — 실제로 세 번을 고쳤는데 화면은 그대로였고, 원인이
        /// 이 덮어쓰기였다 (S15P21A604-355). 제한도 같은 자리에서 걸어야 살아남는다.</para>
        ///
        /// <para>부스마다 크기가 달라 같은 세기라도 사람에게 닿는 양이 수십 배 벌어진다.
        /// 작은 매대는 조명이 사람 코앞에 앉기 때문이다. 그래서 세기가 아니라
        /// <b>사람 머리에 닿는 양</b>(세기/거리²)을 기준으로 상한을 건다.</para>
        /// </summary>
        void LimitExposureOnStaff(Light light)
        {
            var staff = FindStaff();
            if (staff == null) return;

            var head = staff.position + Vector3.up * 19f;   // 사람 키 22.4 기준 얼굴 높이

            // 코앞이면 세기를 낮춰도 얼굴만 탄다 — 먼저 띄운다.
            float d = Vector3.Distance(transform.position, head);
            if (d < _minStaffDistance)
            {
                var away = transform.position - head; away.y = 0f;
                if (away.sqrMagnitude < 1f) away = staff.forward;
                transform.position = head + Vector3.up * (_minStaffDistance * 0.8f)
                                          + away.normalized * (_minStaffDistance * 0.6f);
                d = Vector3.Distance(transform.position, head);
            }

            float cap = _maxStaffExposure * d * d;
            if (light.intensity > cap) light.intensity = Mathf.Max(90f, cap);
        }

        /// <summary>
        /// 이 부스의 직원. <c>FestivalSlot_07</c> ↔ <c>Staff_07</c> 처럼 번호로 짝을 짓는다.
        /// 짝이 없으면(직원 없는 부스) 제한하지 않는다.
        /// </summary>
        Transform FindStaff()
        {
            var host = transform.parent != null ? transform.parent : transform;
            // 직원은 **부스 슬롯의 자식**이다 — 부스가 사용자별로 바뀌어도 직원이 따라가야
            // 하므로 별도 묶음에 두지 않는다. 같은 슬롯 아래 Staff_* 를 찾는다.
            foreach (Transform c in host)
                if (c.name.StartsWith("Staff_")) return c;
            return null;
        }

        /// <summary>부스(부모) 렌더러 월드 바운즈에 맞춰 위치·range·세기를 잡는다.</summary>
        public void Fit()
        {
            var light = GetComponent<Light>();
            var host = transform.parent != null ? transform.parent : transform;

            var renderers = host.GetComponentsInChildren<Renderer>(true);
            if (renderers.Length == 0)
            {
                Debug.LogWarning($"[BoothInteriorLight] {host.name}: 렌더러가 없어 조명 크기를 정할 수 없다.");
                return;
            }

            var bounds = renderers[0].bounds;
            for (int i = 1; i < renderers.Length; i++) bounds.Encapsulate(renderers[i].bounds);

            float span = Mathf.Max(bounds.size.x, bounds.size.z, bounds.size.y);
            light.type = LightType.Point;
            light.range = span * _rangeFactor;
            light.intensity = light.range * _intensityPerUnit;
            // 광원이 부스마다 하나씩 늘어난다 — 그림자는 비용만 크고 좁은 부스에선 이득이 없다.
            light.shadows = LightShadows.None;

            transform.position = new Vector3(
                bounds.center.x,
                bounds.min.y + bounds.size.y * _heightRatio,
                bounds.center.z);

            LimitExposureOnStaff(light);
        }
    }
}
