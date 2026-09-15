namespace Festa.Content
{
    /// <summary>
    /// F 키로 실행되는 월드 오브젝트 (S15P21A604-303).
    ///
    /// 이전에는 <see cref="BoothInteractionInput"/> 이 구현 타입을 하나씩 나열해 분기했다.
    ///
    /// <code>
    /// var laptop = collider.GetComponentInParent&lt;LaptopInteractable&gt;();
    /// if (laptop != null) { laptop.Interact(); return; }
    /// var ai = collider.GetComponentInParent&lt;AiNpcInteractable&gt;();
    /// ...
    /// </code>
    ///
    /// 상호작용 종류가 늘 때마다 **디스패처를 고쳐야 하고, 고치는 걸 잊으면 조용히 반응이
    /// 없다** — 컴포넌트는 붙었는데 아무 일도 안 일어나므로 원인을 찾기 어렵다.
    /// 실제로 미니게임을 붙일 때 그 분기를 추가해야 했고, 코드에 "타입 분기 일반화는
    /// S15P21A604-303 의 몫" 이라는 메모가 남아 있었다.
    ///
    /// 이제 이 인터페이스만 구현하면 디스패처가 자동으로 찾는다.
    ///
    /// <para><b>한 오브젝트에 둘 이상 붙이지 마라.</b> 디스패처는 먼저 찾은 하나만 실행한다 —
    /// 어느 쪽이 걸릴지는 컴포넌트 순서에 달려 있어 예측이 어렵다.</para>
    /// </summary>
    public interface IBoothInteractable
    {
        /// <summary>사거리·조준을 통과해 F 가 눌렸을 때 호출된다.</summary>
        void Interact();
    }
}
