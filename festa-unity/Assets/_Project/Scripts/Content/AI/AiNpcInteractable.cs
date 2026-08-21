using System.Text;
using Festa.Booth;
using Festa.Integration;
using UnityEngine;

namespace Festa.Content
{
    /// <summary>
    /// AI NPC 상호작용 POC. 클릭하면 Mock AI에 질문을 보내고 스트리밍 응답을 로그로 받는다.
    /// 정식 구현에서는 상호작용 시 React 오버레이(AI 채팅 UI)로 이벤트를 넘긴다 —
    /// 한글 IME 문제로 텍스트 입력 UI는 Unity 내부에 만들지 않는다 (ADR 결정 4).
    /// </summary>
    [RequireComponent(typeof(BoothRuntimeObject))]
    public class AiNpcInteractable : MonoBehaviour
    {
        BoothRuntimeObject _runtimeObject;
        bool _busy;

        void Awake() => _runtimeObject = GetComponent<BoothRuntimeObject>();

        void OnMouseDown() => Interact("이 부스의 프로젝트에 대해 알려줘");

        public async void Interact(string message)
        {
            if (_busy) return;
            _busy = true;

            var conversationId = await ApiServices.Ai.CreateConversationAsync(
                _runtimeObject.BoothId, _runtimeObject.ConfigId);

            var sb = new StringBuilder();
            await ApiServices.Ai.SendMessageStreamingAsync(
                conversationId,
                message,
                onToken: token => sb.Append(token),
                onError: err => Debug.LogError($"[AiNpc] stream error: {err}"));

            Debug.Log($"[AiNpc] booth={_runtimeObject.BoothId} agent={_runtimeObject.ConfigId} answer: {sb}");
            _busy = false;
        }
    }
}
