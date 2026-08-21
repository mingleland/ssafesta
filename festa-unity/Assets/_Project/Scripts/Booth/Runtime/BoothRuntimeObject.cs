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
        public BoothObjectType Type { get; private set; }
        public int ConfigId { get; private set; }

        public void Init(int boothId, BoothObjectDto dto, BoothObjectType type)
        {
            BoothId = boothId;
            ObjectId = dto.id;
            Type = type;
            ConfigId = dto.configId;
        }
    }
}
