using Festa.World;
using UnityEngine;

namespace Festa.Network
{
    /// <summary>
    /// 자세에 맞춰 충돌 몸을 줄인다 (S15P21A604-677).
    ///
    /// <para><b>무엇이 문제였나.</b> 소파에 누워도 충돌 범위가 선 키 그대로였다. 실측하면
    /// 누운 동안 캡슐 위끝이 <b>실제 몸보다 10.52 u 높다</b> — 남이 보이지 않는 기둥에 부딪힌다.</para>
    ///
    /// <para><b>같은 뿌리에서 두 번째 증상이 나온다.</b> <c>CharacterController</c> 는 캡슐 바닥이
    /// 곧 루트다(center.y = height/2). 그래서 루트를 좌면에 놓으면 <b>캡슐 바닥</b>이 좌면에 닿을 뿐,
    /// 몸은 자세가 루트에서 얼마나 내려가 있느냐에 따라 제각각 파묻힌다 — 실측으로 글자 좌면 7.87
    /// 기준 LieSofa −1.74, LieRight −0.79 로 <b>자세마다 0.95 u 편차</b>가 났다.</para>
    ///
    /// <para><b>그래서 캡슐을 몸에 맞춘다.</b> 캡슐 바닥을 몸 바닥에 맞춰 두면, 중력이 캡슐을 좌면에
    /// 앉히는 순간 몸 바닥도 좌면에 정확히 앉는다. 충돌 범위와 파묻힘이 한 번에 사라진다.</para>
    ///
    /// <para><b>값을 적어 두지 않는다.</b> 자세가 자리잡은 뒤 렌더러 bounds 로 직접 재서 쓴다 —
    /// 클립을 바꾸거나 아바타 배율이 달라져도 다시 맞출 일이 없다. 뼈가 아니라 <b>보이는 몸</b>이
    /// 기준인 이유는 발목을 바닥에 맞추면 신발이 파묻히기 때문이다(자는 직원에서 겪었다, T-266).</para>
    ///
    /// <para>owner 는 <c>CharacterController</c>, 원격은 <c>CapsuleCollider</c> 를 쓰므로 둘 다 맞춘다.
    /// <c>EmoteId</c> 는 NetworkVariable 이라 각 화면이 스스로 판단한다 — 계약을 늘리지 않는다.</para>
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class PlayerPostureCollider : MonoBehaviour
    {
        [Tooltip("자세를 바꾼 뒤 몸이 자리잡기를 기다리는 프레임 수. 0 프레임에 재면 이전 자세가 잡힌다")]
        [SerializeField] int _settleFrames = 3;

        [Tooltip("몇 프레임에 걸쳐 몸 범위를 모을지. 뒤척이는 자세는 루프로 움직여 한 프레임으로는 최저점을 놓친다")]
        [SerializeField] int _sampleFrames = 12;

        [Tooltip("누워 있는 동안 다시 맞추는 주기(프레임). 자세가 자리잡은 뒤에도 발 IK 로 몸이 더 내려앉는다")]
        [SerializeField] int _refitFrames = 60;

        NetworkPlayer _player;
        CharacterController _controller;
        CapsuleCollider _capsule;

        /// <summary>씬·프리팹에 저작된 선 자세 값. 일어나면 여기로 정확히 돌아간다.</summary>
        float _standHeight;
        float _standRadius;
        Vector3 _standCenter;

        /// <summary>표집 중 모으는 몸 범위(월드 y). 루프 한 바퀴의 최저·최고를 담는다.</summary>
        float _lowest, _highest;
        int _samplesLeft;
        int _pendingSampleFrame = -1;

        /// <summary>아직 한 번도 적용하지 않았음을 뜻한다 — 첫 LateUpdate 에서 반드시 한 번 맞춘다.</summary>
        bool _hasApplied;
        PlayerEmoteId _applied;
        int _applyAtFrame = -1;

        void Awake()
        {
            _player = GetComponent<NetworkPlayer>();
            _controller = GetComponent<CharacterController>();
            _capsule = GetComponent<CapsuleCollider>();

            if (_controller != null) { _standHeight = _controller.height; _standCenter = _controller.center; _standRadius = _controller.radius; }
            else if (_capsule != null) { _standHeight = _capsule.height; _standCenter = _capsule.center; _standRadius = _capsule.radius; }
        }

        void LateUpdate()
        {
            if (_player == null) return;

            var id = _player.EmoteId.Value;
            if (!_hasApplied || id != _applied)
            {
                _hasApplied = true;
                _applied = id;
                // 누운 자세는 몇 프레임 뒤에 잰다. 일어서기는 기다릴 이유가 없다 — 바로 되돌린다.
                if (LiePoseTable.IsLie(id))
                {
                    _applyAtFrame = Time.frameCount + Mathf.Max(1, _settleFrames);
                    _samplesLeft = 0;
                    _pendingSampleFrame = -1;
                }
                else
                {
                    _applyAtFrame = -1;
                    _samplesLeft = 0;
                    _pendingSampleFrame = -1;
                    SetBoundsAlwaysFresh(false);
                    Restore();
                }
            }

            // 표집 중이면 모은다. 다 모으면 맞추고, 다음 재측정을 예약한다.
            if (_samplesLeft > 0)
            {
                if (TryMeasureBody(out float bottom, out float top))
                {
                    if (bottom < _lowest) _lowest = bottom;
                    if (top > _highest) _highest = top;
                }
                if (--_samplesLeft == 0)
                {
                    FitToBody(_lowest, _highest);
                    SetBoundsAlwaysFresh(false);
                    // **한 번으로는 안 끝난다.** 자세가 자리잡은 뒤에도 발 IK 가 몸을 더 내려
                    // 루트 기준 위치가 1 u 넘게 바뀐다(2026-09-13 실측). 누워 있는 동안 다시 맞춘다.
                    if (LiePoseTable.IsLie(_applied)) _applyAtFrame = Time.frameCount + Mathf.Max(2, _refitFrames);
                }
                return;
            }

            // 예약 시각이 되면 bounds 갱신을 켜고, 두 프레임 뒤부터 모으기 시작한다.
            // 켜자마자 재면 첫 표본이 갱신 전 값이라 최저점이 터무니없이 낮게 잡힌다.
            if (_applyAtFrame > 0 && Time.frameCount >= _applyAtFrame)
            {
                _applyAtFrame = -1;
                SetBoundsAlwaysFresh(true);
                _pendingSampleFrame = Time.frameCount + 2;
            }

            if (_pendingSampleFrame > 0 && Time.frameCount >= _pendingSampleFrame)
            {
                _pendingSampleFrame = -1;
                _lowest = float.MaxValue;
                _highest = float.MinValue;
                _samplesLeft = Mathf.Max(1, _sampleFrames);
            }
        }

        /// <summary>모아 둔 몸 세로 범위에 캡슐을 맞춘다.</summary>
        void FitToBody(float bottom, float top)
        {
            if (top <= bottom) return;   // 한 번도 재지 못했다 — 선 자세를 그대로 둔다

            float scale = Mathf.Max(0.0001f, transform.lossyScale.y);

            // 콜라이더 값은 로컬 기준이다 — 루트 배율로 나눠 넣는다.
            float height = Mathf.Max(0.01f, (top - bottom) / scale);

            // **바닥은 루트에 그대로 둔다 (center.y = height/2).**
            //
            // 몸 중심에 맞추려고 center 를 내려 봤다가 발산했다 — 캡슐을 내리면 디페네트레이션이
            // 루트를 밀어 올리고, 다음 재측정이 그 변위를 다시 반영해 증폭된다.
            // 실측으로 루트가 9 → 28.5 까지 떠오르고 center 가 −17 까지 갔다(2026-09-13).
            // 캡슐 바닥이 곧 접지점이라 여기를 건드리면 접지 자체가 흔들린다.
            float centerY = height * 0.5f;

            // **반지름도 같이 줄인다.** 캡슐은 height < 2*radius 를 조용히 늘려 잡으므로,
            // 선 자세 반지름(2.9)을 그대로 두면 누운 몸 높이(약 5 u)가 5.8 로 부풀어 버린다.
            float radius = Mathf.Min(_standRadius, height * 0.5f);

            if (_controller != null)
            {
                _controller.radius = radius;
                _controller.height = height;
                _controller.center = new Vector3(_standCenter.x, centerY, _standCenter.z);
            }
            if (_capsule != null)
            {
                _capsule.radius = radius;
                _capsule.height = height;
                _capsule.center = new Vector3(_standCenter.x, centerY, _standCenter.z);
            }
        }

        /// <summary>
        /// 재는 동안만 <c>updateWhenOffscreen</c> 을 켠다.
        ///
        /// <para>스킨드 메시의 bounds 는 화면 밖이면 갱신되지 않는다. 멀리 있는 사람이 누우면
        /// <b>이전 자세의 bounds 로 재서</b> 캡슐이 엉뚱하게 잡힌다. 그렇다고 항상 켜 두면
        /// 사람 수만큼 매 프레임 비용이 붙으므로, 재는 몇 프레임만 켜고 되돌린다.</para>
        /// </summary>
        void SetBoundsAlwaysFresh(bool on)
        {
            var body = BodyRenderers();
            for (int i = 0; i < body.Length; i++) body[i].updateWhenOffscreen = on;
        }

        /// <summary>
        /// 몸을 이루는 렌더러. <b>매번 새로 찾는다 — 캐시하지 않는다.</b>
        ///
        /// <para>아바타는 스폰 뒤에 조립되므로 <c>Awake</c> 에서 담아 두면 프리팹 자리표시자 하나만
        /// 잡혀 측정이 통째로 실패한다(2026-09-13 플레이 모드에서 실제로 이렇게 조용히 넘어갔다).
        /// 자세가 바뀔 때만 부르므로 매번 찾아도 비용이 문제되지 않는다.</para>
        ///
        /// <para><c>SkinnedMeshRenderer</c> 만 센다. 이름표와 바닥 그림자는 <c>MeshRenderer</c> 인데
        /// 머리 위·발밑에 있어 같이 세면 몸 범위가 부풀려진다.</para>
        /// </summary>
        SkinnedMeshRenderer[] BodyRenderers() => GetComponentsInChildren<SkinnedMeshRenderer>(true);

        /// <summary>보이는 몸의 월드 세로 범위. 켜진 렌더러가 하나도 없으면 실패로 돌려준다.</summary>
        bool TryMeasureBody(out float bottom, out float top)
        {
            bottom = float.MaxValue;
            top = float.MinValue;
            var body = BodyRenderers();
            for (int i = 0; i < body.Length; i++)
            {
                var r = body[i];
                if (r == null || !r.enabled || !r.gameObject.activeInHierarchy) continue;
                var b = r.bounds;
                if (b.min.y < bottom) bottom = b.min.y;
                if (b.max.y > top) top = b.max.y;
            }
            return top > bottom;
        }

        /// <summary>저작된 선 자세로 되돌린다.</summary>
        void Restore()
        {
            if (_controller != null) { _controller.radius = _standRadius; _controller.height = _standHeight; _controller.center = _standCenter; }
            if (_capsule != null) { _capsule.radius = _standRadius; _capsule.height = _standHeight; _capsule.center = _standCenter; }
        }
    }
}
