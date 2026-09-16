// 월드 공간 TMP 텍스트(이름표·지붕 라벨·말풍선)의 벽 차폐 판정과 "벽 위에 그리기" 재질 설정을 모아 둔 공용 장치.
using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 월드 텍스트의 벽 차폐 판정과 표시 방식.
    ///
    /// <para><b>문제.</b> 글자 바닥 중앙 한 점만 재면 (1) 중앙은 보이는 채로 빌보드가 회전해 <b>한쪽 끝만
    /// 벽에 박힌</b> 경우 박힌 쪽 글자만 ZTest 로 잘려 "부스 관리" 가 "ㅂ스 관리" 로 보이고 (2) 줄전구 하나가
    /// 중앙을 스치면 라벨 전체가 꺼진다 (2026-09-16 실측, S15P21A604-703 잔여).</para>
    ///
    /// <para><b>방식.</b> 글자 사각형의 중심과 네 모서리 다섯 점을 재서 <b>과반(3점 이상)</b> 이 막히면 감춘다.
    /// 감추지 않는 텍스트는 <b>ZTest Always(Overlay 셰이더)</b> 로 그려 일부가 벽 뒤에 있어도 잘리지 않는다 —
    /// 지붕 위 간판이 벽 모서리 위에 통째로 읽히는 쪽이 반 토막보다 낫고, 복도 끝(300u)에서 라벨이 보여야 한다는
    /// 사용자 요구와도 맞는다. "일부라도 막히면 숨김" 은 그 거리에서 라벨을 못 보이게 해 버려서 버렸다.</para>
    ///
    /// <para>끝점을 0.1u 만 안으로 당긴다. 더 빼면 텍스트가 벽 바로 앞에 있을 때 그 벽이 레이 범위에서
    /// 빠져 글자가 벽을 뚫고 남는다(같은 이슈 1차 수정에서 확인).</para>
    /// </summary>
    public static class WorldTextOcclusion
    {
        const float EndpointInset = 0.1f;
        const int SampleCount = 5;
        /// <summary>이 수 이상의 표본점이 막히면 "가려졌다". 5점 중 3점 = 과반.</summary>
        const int HiddenThreshold = 3;
        /// <summary>
        /// Overlay 셰이더를 물고 있는 재질(Resources). <c>Shader.Find</c> 만 쓰면 빌드에 그 셰이더가 포함된다는
        /// 보장이 없다 — Resources 재질은 의존성까지 항상 빌드에 들어간다.
        /// </summary>
        const string OverlayMaterialResourcePath = "Fonts/WorldTextOverlay";
        static readonly RaycastHit[] Hits = new RaycastHit[32];
        static readonly Vector3[] Samples = new Vector3[SampleCount];
        static Shader _overlayShader;
        static bool _overlayLookedUp;

        /// <summary>
        /// 텍스트 사각형(중심·네 모서리) 중 과반이 카메라와의 사이에 단단한 것으로 막히면 true.
        /// <paramref name="owner"/> 의 자식 콜라이더(자기 몸·자기 구조물)는 가림으로 치지 않는다.
        /// </summary>
        public static bool IsMostlyOccluded(Camera cam, TMP_Text text, Transform owner)
        {
            if (cam == null || text == null) return false;
            var origin = cam.transform.position;
            int count = CollectSamplePoints(text, Samples);
            int blocked = 0;
            for (int i = 0; i < count; i++)
            {
                if (IsPointOccluded(origin, Samples[i], owner)) blocked++;
                if (blocked >= HiddenThreshold) return true;
                if (blocked + (count - 1 - i) < HiddenThreshold) return false;   // 남은 점을 다 더해도 과반이 안 된다
            }
            return false;
        }

        /// <summary>
        /// 텍스트 전용 재질 인스턴스를 <b>벽 위에 그리는</b> Overlay 셰이더로 바꾼다. 속성 이름이 같은 셰이더라
        /// 외곽선·두께 설정은 그대로 유지된다. 셰이더를 못 찾으면 조용히 넘기지 않고 에러를 남긴다.
        /// </summary>
        public static void ApplyOverlayShader(Material material, string ownerLabel)
        {
            if (material == null) return;
            if (!_overlayLookedUp)
            {
                var carrier = Resources.Load<Material>(OverlayMaterialResourcePath);
                _overlayShader = carrier != null ? carrier.shader : null;
                _overlayLookedUp = true;
            }
            if (_overlayShader == null)
            {
                Debug.LogError($"[WorldTextOcclusion] Resources/{OverlayMaterialResourcePath}.mat 이 없거나 셰이더가 비었다 — '{ownerLabel}' 글자가 벽에 반쯤 잘릴 수 있다.");
                return;
            }
            if (material.shader != _overlayShader) material.shader = _overlayShader;
        }

        /// <summary>
        /// 실제 글자가 차지하는 범위(<see cref="TMP_Text.textBounds"/>)의 중심과 네 모서리를 월드 좌표로 모은다.
        /// 아직 메시가 안 만들어졌으면 RectTransform 사각형으로 대신한다.
        /// </summary>
        static int CollectSamplePoints(TMP_Text text, Vector3[] buffer)
        {
            var tr = text.transform;
            var b = text.textBounds;
            if (b.size.sqrMagnitude < 1e-6f)
            {
                var rect = text.rectTransform.rect;
                b = new Bounds(rect.center, rect.size);
            }
            var c = b.center;
            var e = b.extents;
            buffer[0] = tr.TransformPoint(c);
            buffer[1] = tr.TransformPoint(new Vector3(c.x - e.x, c.y - e.y, c.z));
            buffer[2] = tr.TransformPoint(new Vector3(c.x + e.x, c.y - e.y, c.z));
            buffer[3] = tr.TransformPoint(new Vector3(c.x - e.x, c.y + e.y, c.z));
            buffer[4] = tr.TransformPoint(new Vector3(c.x + e.x, c.y + e.y, c.z));
            return 5;
        }

        /// <summary>카메라에서 한 점까지 선분을 쏴 단단한 것(트리거 제외)에 막히는지 본다.</summary>
        public static bool IsPointOccluded(Vector3 origin, Vector3 target, Transform owner)
        {
            var delta = target - origin;
            float d = delta.magnitude;
            if (d < 0.05f) return false;

            float rayDistance = Mathf.Max(0.05f, d - EndpointInset);
            int n = Physics.RaycastNonAlloc(origin, delta / d, Hits, rayDistance, ~0, QueryTriggerInteraction.Ignore);
            for (int i = 0; i < n; i++)
            {
                var t = Hits[i].transform;
                if (t == null) continue;
                if (owner != null && (t.IsChildOf(owner) || owner.IsChildOf(t))) continue;   // 자기 몸은 가림이 아니다
                return true;
            }
            return false;
        }
    }
}
