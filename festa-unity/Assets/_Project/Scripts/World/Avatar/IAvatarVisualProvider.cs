using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 아바타 외형 생성 경계.
    /// NetworkPlayer는 이 인터페이스만 알고, Sidekick/캡슐/향후 다른 시스템을 구분하지 않는다.
    /// </summary>
    public interface IAvatarVisualProvider
    {
        /// <summary>
        /// appearance에 해당하는 외형을 parent 아래에 생성한다.
        /// 알 수 없는 프리셋은 기본 외형으로 폴백하며 null을 반환하지 않는다.
        /// </summary>
        GameObject CreateVisual(AvatarAppearance appearance, Transform parent);
    }
}
