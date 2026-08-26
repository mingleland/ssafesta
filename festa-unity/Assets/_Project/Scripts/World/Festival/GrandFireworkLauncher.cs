using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 인터랙션 예약용 대형 불꽃. 자동 루프에서 빠져 있고 <see cref="Fire"/> 를 불러야
    /// 한 발 쏜다. 나중에 부스 상호작용(BoothInteractionInput 디스패처)이 이 메서드를
    /// 부르면 된다. 셸 오브젝트는 고정이고 파티클 버퍼를 재사용하므로 몇 번을 쏴도
    /// 생성/삭제 비용이 없다.
    /// </summary>
    public class GrandFireworkLauncher : MonoBehaviour
    {
        ParticleSystem _rocket;

        void Awake() => _rocket = GetComponentInChildren<ParticleSystem>();

        /// <summary>대형 불꽃 한 발. 이미 상승 중이면 무시한다.</summary>
        [ContextMenu("발사 (테스트)")]
        public void Fire()
        {
            if (_rocket == null) _rocket = GetComponentInChildren<ParticleSystem>();
            if (_rocket == null || _rocket.particleCount > 0) return;
            _rocket.Play(true);
        }
    }
}
