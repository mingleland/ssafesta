using UnityEngine;

namespace Festa.Booth
{
    /// <summary>
    /// 런타임 생성된 Booth Object가 자기 출처(레이아웃 정보)를 보관한다.
    /// 콘텐츠 컴포넌트(AiNpcInteractable 등)는 이걸 통해 configId에 접근한다.
    /// </summary>
    public class BoothRuntimeObject : MonoBehaviour
    {
        public int BoothId { get; private set; }
        public string ObjectId { get; private set; }
        public string AssetCode { get; private set; }
        public BoothObjectType Type { get; private set; }
        public int ConfigId { get; private set; }

        /// <summary>
        /// 콘텐츠가 실제로 연결돼 있는가.
        ///
        /// 서버는 미연결을 `configId: null` 로 보내지만 `BoothLayoutDto.configId` 가 `int` 라
        /// `JsonUtility` 가 필드 부재를 **0 으로 읽는다.** 그래서 Unity 에서 미연결은 0 이다.
        /// 0 은 서버가 발급하는 유효 ID 범위에 없으므로 판정이 성립한다.
        ///
        /// FURNITURE·DECORATION 처럼 콘텐츠가 필요 없는 타입은 정상적으로 0 이다 —
        /// 서버도 `type.requiresConfig()` 로 그 타입들은 경고에서 제외한다.
        /// 이 속성은 "연결됐는가"만 답하고 "연결돼야 하는가"는 판단하지 않는다.
        /// </summary>
        public bool HasConfig => ConfigId > 0;

        public void Init(int boothId, BoothObjectDto dto, BoothObjectType type)
        {
            BoothId = boothId;
            ObjectId = dto.ResolvedObjectId;
            AssetCode = dto.assetCode;
            Type = type;
            ConfigId = dto.configId;
        }
    }
}
