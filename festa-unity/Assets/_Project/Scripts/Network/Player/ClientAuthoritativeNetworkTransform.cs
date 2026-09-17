using Unity.Netcode.Components;

namespace Festa.Network
{
    /// <summary>
    /// Owner(Client) 권위 이동 동기화. doc 12 §10의 "A안: Client owner movement + server relay".
    /// 순간이동 등 비정상 값 서버 검증은 P1에서 추가한다.
    /// Player Prefab의 NetworkTransform 대신 이 컴포넌트를 사용한다.
    /// </summary>
    public class ClientAuthoritativeNetworkTransform : NetworkTransform
    {
        /// <summary>
        /// 플레이어 이동 델타는 최신 상태가 중요한 실시간 데이터다. Reliable 전송은 한 패킷이
        /// 유실되면 뒤 상태까지 재전송을 기다려 멈췄다가 몰아서 따라가는 현상을 만든다.
        /// 일반 이동은 유실 허용 델타로 보내고, 새 Lerp 보간기가 일정한 시간 간격으로 버퍼를
        /// 소비하게 한다. 초기 상태와 Teleport는 NGO가 계속 Reliable로 전송한다.
        /// </summary>
        protected override void Awake()
        {
            UseUnreliableDeltas = true;
            PositionInterpolationType = InterpolationTypes.Lerp;
            RotationInterpolationType = InterpolationTypes.Lerp;

            // 프리팹에 남아 있는 기본 임계값(위치 0.05u / 회전 1도)은 걷는 동안 작은 델타를
            // 여러 틱 건너뛰게 한다. 월드가 1m=10u라 위치 0.01u도 충분히 큰 노이즈 필터이고,
            // 30 tick 기준으로는 입력 후 첫 패킷이 한 틱 안에 나가도록 더 낮춘다.
            PositionThreshold = 0.01f;
            RotAngleThreshold = 0.25f;
            base.Awake();
        }

        protected override bool OnIsServerAuthoritative() => false;
    }
}
