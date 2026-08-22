using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 축제 부지의 동적 연출 묶음 — 정적인 밤거리에 움직임을 준다.
    ///
    /// - 전구 반짝임: 발광 세기를 렌더러마다 위상이 다른 사인파로 흔든다.
    ///   MaterialPropertyBlock 이라 재질 에셋을 건드리지 않고(런타임 전용),
    ///   광원 수도 늘지 않는다.
    /// - 서치라이트 빔: 가짜 빔(가산 쿼드)을 천천히 회전 — 실광원 없음.
    /// - 풍선 부유: 지정된 트랜스폼을 위상 다른 사인으로 두둥실.
    ///
    /// 정적 씬 오브젝트에 붙는 로컬 연출이다 — NetworkObject 없음.
    /// </summary>
    public class FestivalAmbience : MonoBehaviour
    {
        [Header("전구 반짝임")]
        [SerializeField] Renderer[] _twinkleRenderers;
        [SerializeField] float _twinkleSpeed = 2.1f;
        [SerializeField] float _emissionBase = 4.2f;
        [SerializeField] float _emissionSwing = 2.6f;

        [Header("서치라이트 빔")]
        [SerializeField] Transform[] _beams;
        [SerializeField] float _beamYawSpeed = 16f;

        [Header("풍선 부유")]
        [SerializeField] Transform[] _bobbers;
        [SerializeField] float _bobAmplitude = 4f;
        [SerializeField] float _bobSpeed = 0.9f;

        static readonly int EmissionColor = Shader.PropertyToID("_EmissionColor");

        MaterialPropertyBlock _mpb;
        Color[] _baseColors;
        Vector3[] _bobOrigins;

        void Start()
        {
            _mpb = new MaterialPropertyBlock();
            _baseColors = new Color[_twinkleRenderers?.Length ?? 0];
            for (int i = 0; i < _baseColors.Length; i++)
            {
                var m = _twinkleRenderers[i] != null ? _twinkleRenderers[i].sharedMaterial : null;
                _baseColors[i] = m != null ? m.color : Color.white;
            }
            _bobOrigins = new Vector3[_bobbers?.Length ?? 0];
            for (int i = 0; i < _bobOrigins.Length; i++)
                if (_bobbers[i] != null) _bobOrigins[i] = _bobbers[i].position;
        }

        void Update()
        {
            float t = Time.time;

            if (_twinkleRenderers != null)
                for (int i = 0; i < _twinkleRenderers.Length; i++)
                {
                    var r = _twinkleRenderers[i];
                    if (r == null) continue;
                    float k = _emissionBase + _emissionSwing * Mathf.Sin(t * _twinkleSpeed + i * 1.71f);
                    _mpb.SetColor(EmissionColor, _baseColors[i] * k);
                    r.SetPropertyBlock(_mpb);
                }

            if (_beams != null)
                for (int i = 0; i < _beams.Length; i++)
                {
                    if (_beams[i] == null) continue;
                    // 빔마다 반대 방향·다른 속도로 하늘을 쓸어간다
                    float dir = (i % 2 == 0) ? 1f : -1f;
                    _beams[i].Rotate(0f, dir * _beamYawSpeed * (1f + i * 0.15f) * Time.deltaTime, 0f, Space.World);
                }

            if (_bobbers != null)
                for (int i = 0; i < _bobbers.Length; i++)
                {
                    if (_bobbers[i] == null) continue;
                    var p = _bobOrigins[i];
                    p.y += Mathf.Sin(t * _bobSpeed + i * 2.3f) * _bobAmplitude;
                    p.x += Mathf.Sin(t * _bobSpeed * 0.6f + i * 1.1f) * (_bobAmplitude * 0.4f);
                    _bobbers[i].position = p;
                }
        }
    }
}
