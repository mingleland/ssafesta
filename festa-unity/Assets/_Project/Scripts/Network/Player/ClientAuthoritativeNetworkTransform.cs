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
        protected override bool OnIsServerAuthoritative() => false;
    }
}
