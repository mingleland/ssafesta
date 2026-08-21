using System;
using System.Threading.Tasks;

namespace Festa.Integration
{
    /// <summary>
    /// FastAPI AI 경계 (POC D).
    /// 스트리밍 계약은 doc 16 §6 (start/token/source/done/error)을 따른다.
    /// 실제 구현은 SSE지만 Unity 쪽 소비자는 이 인터페이스만 알면 된다 —
    /// 나중에 React 오버레이로 UI가 이동해도 이 경계 정의는 계약 문서 역할을 유지한다.
    /// </summary>
    public interface IAiAgentClient
    {
        Task<string> CreateConversationAsync(int boothId, int agentId);

        /// <summary>onToken은 delta 단위로 호출된다. 완료 시 Task가 종료된다.</summary>
        Task SendMessageStreamingAsync(string conversationId, string message,
            Action<string> onToken, Action<string> onError = null);
    }
}
