using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 구역 BGM 크로스페이드. 두 트랙을 항상 함께 재생하고(2D, 루프)
    /// 로컬 플레이어 위치로 볼륨만 섞는다 — 복도를 걷는 동안
    /// 11층의 아침이 잦아들고 축제의 밤이 차오르는 "이세계 진입" 연출.
    ///
    /// 구역 판정 (월드 좌표, 1 m = 10 unit):
    ///   방(z &lt; -118)          Morning 100%
    ///   복도(z -110 → 100)     z 를 따라 선형 크로스페이드
    ///   축제(x &lt; -228)        Circus 100%
    ///   내부 부스(x &gt; 500)     Circus 35% — 홀 안까지 축제가 은은히 새어 든다
    ///
    /// 로컬 플레이어 위치를 쓰는 이유: 구역은 "사람이 어디에 서 있는가" 이고, 텔레포트 시
    /// 즉시 이동하므로 BGM 도 즉시 구역을 갈아탄다 (볼륨은 페이드). 스폰 전에는 위치가
    /// 존재하지 않아 잘못된 구역으로 판정될 수가 없다 — 그동안은 양쪽 다 침묵이다.
    /// 로컬 연출 전용 — NetworkObject 없음.
    /// </summary>
    public class WorldBgm : MonoBehaviour
    {
        [SerializeField] AudioClip _morning;    // 11층: Algorithmic Morning
        [SerializeField] AudioClip _circus;     // 축제: Midnight Circus
        [SerializeField] float _morningVolume = 0.5f;
        [SerializeField] float _circusVolume = 0.55f;
        [Tooltip("볼륨이 목표로 수렴하는 속도 (초당). 걸음 속도와 어울리는 완만한 값.")]
        [SerializeField] float _fadeSpeed = 1.4f;

        AudioSource _morningSrc;
        AudioSource _circusSrc;

        void Awake()
        {
            _morningSrc = MakeSource(_morning);
            _circusSrc = MakeSource(_circus);
        }

        AudioSource MakeSource(AudioClip clip)
        {
            var src = gameObject.AddComponent<AudioSource>();
            src.clip = clip;
            src.loop = true;
            src.playOnAwake = false;
            src.spatialBlend = 0f;   // 2D — 거리 감쇠는 우리가 볼륨으로 직접 제어한다
            src.volume = 0f;
            if (clip != null) src.Play();
            return src;
        }

        void Update()
        {
            // 사람이 어디에 서 있는지 모르는 동안에는 **양쪽 다 침묵**이다. 예전에는
            // Camera.main 을 바로 읽었는데, 플레이어가 스폰되기 전의 카메라는 원점에
            // 있고 원점은 구역 판정상 복도(z 0 → t 0.52)라 축제 트랙이 켜졌다.
            // 11F 진입 첫 0.3초에 축제 음악이 새어 나온 원인이다 (GitLab #140).
            float m = 0f, c = 0f;
            if (TryGetEarPosition(out var p)) (m, c) = ZoneWeights(p);

            float dt = _fadeSpeed * Time.deltaTime;
            _morningSrc.volume = Mathf.MoveTowards(_morningSrc.volume, m * _morningVolume, dt);
            _circusSrc.volume = Mathf.MoveTowards(_circusSrc.volume, c * _circusVolume, dt);
        }

        /// <summary>
        /// 구역 판정에 쓸 "귀"의 위치. 접속 중에는 로컬 플레이어 — 카메라와 달리 스폰 전에는
        /// 아예 없으므로 잘못된 좌표를 읽을 수가 없다. 네트워크가 없는 씬 미리보기(에디터)에서만
        /// 카메라로 물러난다.
        /// </summary>
        static bool TryGetEarPosition(out Vector3 p)
        {
            var nm = Unity.Netcode.NetworkManager.Singleton;
            if (nm != null && nm.IsClient)
            {
                var obj = nm.LocalClient?.PlayerObject;
                if (obj == null) { p = default; return false; }   // 접속했지만 아직 스폰 전
                p = obj.transform.position;
                return true;
            }

            var cam = Camera.main;
            if (cam == null) { p = default; return false; }
            p = cam.transform.position;
            return true;
        }

        static (float morning, float circus) ZoneWeights(Vector3 p)
        {
            if (p.x > 500f) return (0f, 0.35f);    // 내부 부스 홀
            if (p.x < -228f) return (0f, 1f);      // 축제 부지
            if (p.z < -118f) return (1f, 0f);      // 11층 방

            // 복도·개활 전실 — 계곡형 크로스페이드. 두 곡을 절반씩 섞으면 조성이
            // 달라 불협화음이 난다. 전반부에서 아침이 완전히 꺼지고, 짧은 고요를
            // 지나 후반부에서 서커스가 차오른다 — 겹침은 낮은 볼륨의 한 뼘뿐이다.
            float t = Mathf.InverseLerp(-110f, 100f, p.z);
            float morning = Mathf.Clamp01(1f - t * 1.9f);          // t 0.53 에서 소멸
            float circus = Mathf.Clamp01((t - 0.47f) * 1.9f);      // t 0.47 부터 상승
            return (morning, circus);
        }
    }
}
