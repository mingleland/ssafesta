using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 풍선 다발이 바람에 흔들리고, 사람이 지나가면 밀린다 (S15P21A604-355).
    ///
    /// <para><b>콜라이더 대신 이걸 쓴다.</b> 풍선에 콜라이더가 있으면 통로 한가운데서 몸이
    /// 막힌다 — 풍선은 밀치고 지나가는 물건이지 벽이 아니다. 그렇다고 콜라이더만 빼면
    /// 몸이 그냥 통과해서 종잇장처럼 보인다. 물리 대신 <b>가까이 온 만큼 기울이는</b> 방식으로
    /// 반응을 만든다 — 물리 바디가 없으니 네트워크로 동기화할 상태도 없고, 각자 화면에서
    /// 같은 규칙으로 같은 결과가 나온다.</para>
    ///
    /// <para>평소에는 아주 느리게 흔들린다. 완전히 정지한 소품은 배경으로 죽어 보이지만,
    /// 크게 흔들면 시선을 뺏는다 — 눈에 걸리지 않을 만큼만 움직인다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class BalloonSway : MonoBehaviour
    {
        [Tooltip("평상시 흔들림 각도(도)")]
        [SerializeField, Range(0f, 15f)] float _idleAngle = 3.5f;

        [Tooltip("평상시 흔들림 주기(초)")]
        [SerializeField, Min(0.2f)] float _idlePeriod = 3.4f;

        [Tooltip("이 거리 안으로 들어오면 밀린다 (월드 유닛)")]
        [SerializeField, Min(1f)] float _pushRadius = 26f;

        [Tooltip("바짝 붙었을 때 기울어지는 최대 각도(도)")]
        [SerializeField, Range(0f, 45f)] float _pushAngle = 22f;

        [Tooltip("밀림이 따라붙는 속도. 낮으면 흐느적거리고 높으면 딱딱하다.")]
        [SerializeField, Min(0.5f)] float _followRate = 3.5f;

        Quaternion _rest;
        Vector3 _pushDir;      // 현재 밀린 방향(수평)
        float _pushAmount;     // 0~1
        float _phase;

        void Awake()
        {
            _rest = transform.localRotation;
            // 다발마다 위상을 달리한다 — 전부 같은 박자로 흔들리면 기계처럼 보인다.
            _phase = Mathf.Abs(transform.position.x * 0.37f + transform.position.z * 0.71f) % (Mathf.PI * 2f);
        }

        void Update()
        {
            // 밀어내는 주체는 카메라가 아니라 **사람**이어야 한다. 로컬 플레이어가 없으면
            // (관전·로딩 중) 밀림 없이 평상시 흔들림만 남는다.
            float target = 0f;
            var player = LocalPlayer();
            if (player != null)
            {
                var to = transform.position - player.position;
                to.y = 0f;
                float d = to.magnitude;
                if (d < _pushRadius && d > 0.001f)
                {
                    target = 1f - d / _pushRadius;
                    _pushDir = to / d;          // 사람 반대편으로 기운다
                }
            }
            _pushAmount = Mathf.Lerp(_pushAmount, target, 1f - Mathf.Exp(-_followRate * Time.deltaTime));

            float idle = Mathf.Sin(Time.time * (Mathf.PI * 2f / _idlePeriod) + _phase) * _idleAngle;

            // 기운 방향은 밀린 쪽, 각도는 거리에 비례.
            var pushAxis = Vector3.Cross(Vector3.up, _pushDir.sqrMagnitude > 0.001f ? _pushDir : Vector3.forward);
            var pushRot = Quaternion.AngleAxis(_pushAngle * _pushAmount, pushAxis);
            var idleRot = Quaternion.AngleAxis(idle, Vector3.forward);

            transform.localRotation = pushRot * _rest * idleRot;
        }

        static Transform LocalPlayer()
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            var obj = nm != null && nm.IsClient ? nm.LocalClient?.PlayerObject : null;
            return obj != null ? obj.transform : null;
        }
    }
}
