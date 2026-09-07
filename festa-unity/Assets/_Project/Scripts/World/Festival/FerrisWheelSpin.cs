using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 관람차를 천천히 돌린다 — 배경이 멈춰 있으면 축제가 아니라 세트장으로 보인다
    /// (S15P21A604-355).
    ///
    /// <para><b>캐빈은 같이 돌지 않는다.</b> 바퀴에 캐빈을 그냥 붙여 돌리면 캐빈이 뒤집혀
    /// 올라간다. 실제 관람차는 캐빈이 축에 매달려 늘 수평을 유지하므로, 바퀴가 돈 만큼
    /// 캐빈을 반대로 돌려 상쇄한다. 계산은 회전값 하나를 되돌리는 것뿐이라 비용이 없다.</para>
    ///
    /// <para><b>배경 전용이다.</b> 플레이어가 탈 수 없고 콜라이더도 붙이지 않는다 —
    /// 벽 바깥에 두는 원경이라 물리가 필요 없고, 돌아가는 물체에 콜라이더를 달면
    /// 스치는 것만으로 캐릭터가 튕긴다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class FerrisWheelSpin : MonoBehaviour
    {
        [Tooltip("한 바퀴 도는 데 걸리는 시간(초). 실제 관람차는 10~20분이지만, 배경에서는 "
               + "그 속도면 멈춰 보인다. 눈에 띄되 어지럽지 않은 값으로 둔다.")]
        [SerializeField, Min(1f)] float _secondsPerTurn = 55f;

        [Tooltip("바퀴(회전체). 비우면 이름이 'rotation wheel' 인 자식을 찾는다.")]
        [SerializeField] Transform _wheel;

        [Tooltip("수평을 유지할 캐빈들. 비우면 이름이 'cabin' 으로 시작하는 자식을 모은다.")]
        [SerializeField] Transform[] _cabins;

        Quaternion[] _cabinStartRotations;
        Vector3[] _cabinStartOffsets;      // 축 기준 처음 위치 — 매 프레임 여기서 다시 계산한다
        Quaternion _wheelStartRotation;
        float _angle;                      // 누적 회전각(도)

        void Start()
        {
            if (_wheel == null)
                foreach (Transform t in transform)
                    if (t.name.ToLower().Contains("rotation wheel")) { _wheel = t; break; }

            if (_cabins == null || _cabins.Length == 0)
            {
                var found = new System.Collections.Generic.List<Transform>();
                foreach (Transform t in transform)
                    if (t.name.ToLower().StartsWith("cabin")) found.Add(t);
                _cabins = found.ToArray();
            }

            // 처음 자세와 **축 기준 처음 위치**를 기억한다. 매 프레임 여기서 절대각으로
            // 다시 계산한다 — 증분 회전을 누적하면 부동소수 오차가 쌓여 캐빈이 서서히
            // 축 쪽으로 빨려 들어간다 (실측: 몇 분 만에 궤도 반경 359 → 319).
            _cabinStartRotations = new Quaternion[_cabins.Length];
            _cabinStartOffsets = new Vector3[_cabins.Length];
            var hub0 = _wheel != null ? _wheel.position : transform.position;
            for (int i = 0; i < _cabins.Length; i++)
            {
                if (_cabins[i] == null) continue;
                _cabinStartRotations[i] = _cabins[i].rotation;
                _cabinStartOffsets[i] = _cabins[i].position - hub0;
            }
            if (_wheel != null) _wheelStartRotation = _wheel.localRotation;

            if (_wheel == null)
                Debug.LogWarning($"[FerrisWheelSpin] {name}: 바퀴를 찾지 못했다 — 회전하지 않는다.");
        }

        void Update()
        {
            if (_wheel == null) return;
            _angle = Mathf.Repeat(_angle + 360f / _secondsPerTurn * Time.deltaTime, 360f);

            // 축은 이 오브젝트의 로컬 Z — 관람차 원판이 놓인 평면의 법선이다.
            // 절대각으로 놓는다(증분 누적 금지 — 위 Start 주석 참조).
            _wheel.localRotation = _wheelStartRotation * Quaternion.AngleAxis(_angle, Vector3.forward);

            // **중심은 루트가 아니라 바퀴 축이다.** 루트는 지면(y=0)에 있고 축은 그보다
            // 한참 위에 있어서, 루트를 중심으로 돌리면 캐빈이 관람차만 한 원을 따로 그린다
            // (S15P21A604-355 — 처음에 이렇게 짜서 캐빈이 엉뚱하게 돌았다).
            var hub = _wheel.position;
            var spin = Quaternion.AngleAxis(_angle, transform.forward);

            for (int i = 0; i < _cabins.Length; i++)
            {
                var cabin = _cabins[i];
                if (cabin == null) continue;
                cabin.position = hub + spin * _cabinStartOffsets[i];
                // 궤도만 돌고 자세는 그대로 — 실제 관람차의 캐빈은 축에 매달려 수평을 지킨다.
                cabin.rotation = _cabinStartRotations[i];
            }
        }
    }
}
