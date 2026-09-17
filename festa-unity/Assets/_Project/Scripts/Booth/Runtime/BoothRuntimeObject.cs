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

        /// <summary>
        /// 레이아웃 밖에서 온 콘텐츠 연결을 뒤늦게 물린다.
        ///
        /// <para>씬에 고정으로 놓인 AI 직원이 이 경로를 쓴다 — 어느 AI 인지가 레이아웃이 아니라
        /// 부스 자체에 붙어 있고, 그 조회가 비동기라 <see cref="Init"/> 시점에는 알 수 없다.</para>
        ///
        /// <para>이미 연결돼 있으면 덮지 않는다. 레이아웃이 명시한 값이 부스 기본값보다 우선이다 —
        /// 배치로 지정한 것을 나중 조회가 뒤엎으면 무엇이 이겼는지 추적할 수 없게 된다.</para>
        /// </summary>
        public bool BindConfigIfEmpty(int configId)
        {
            if (configId <= 0 || ConfigId > 0) return false;
            ConfigId = configId;
            return true;
        }
    }
}
