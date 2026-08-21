using System;
using System.Threading.Tasks;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// POC D용 Mock. 토큰 단위 스트리밍을 흉내 낸다 (doc 16 §6 token event).
    /// 지연 시뮬레이션은 Awaitable 사용 — Task.Delay는 WebGL에서 완료되지 않는다.
    /// </summary>
    public class MockAiAgentClient : IAiAgentClient
    {
        public async Task<string> CreateConversationAsync(int boothId, int agentId)
        {
            await Awaitable.WaitForSecondsAsync(0.1f);
            return $"conv_mock_{boothId}_{agentId}";
        }

        public async Task SendMessageStreamingAsync(string conversationId, string message,
            Action<string> onToken, Action<string> onError = null)
        {
            string[] tokens =
            {
                "이 ", "부스는 ", "SSAFY ", "FESTA ", "프로젝트를 ", "전시하는 ",
                "공간입니다. ", "자세한 ", "내용은 ", "등록된 ", "문서를 ", "기반으로 ", "답변합니다."
            };

            foreach (var token in tokens)
            {
                await Awaitable.WaitForSecondsAsync(0.06f); // 토큰 간 지연 흉내
                onToken?.Invoke(token);
            }
        }
    }
}
