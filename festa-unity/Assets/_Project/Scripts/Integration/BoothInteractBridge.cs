using System.Runtime.InteropServices;
using System.Text;
using UnityEngine;

namespace Festa.Integration
{
    /// <summary>
    /// Unity 부스 상호작용을 FE의 기존 window.FestaUnity.onBoothInteract(json) 계약으로 전달한다.
    /// WebGL 플레이어에서만 브라우저 콜백을 호출하며 에디터와 Dedicated Server에서는 호출하지 않는다.
    /// </summary>
    public static class BoothInteractBridge
    {
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
        [DllImport("__Internal")]
        static extern void FestaNotifyBoothInteract(string json);
#endif

        public static void SendLaptopInteract(int boothId, string objectId, string url = null)
        {
            if (string.IsNullOrEmpty(objectId))
            {
                Debug.LogWarning("[BoothInteractBridge] objectId가 없어 LAPTOP 이벤트를 건너뜁니다.");
                return;
            }

            var json = BuildLaptopJson(boothId, objectId, url);
#if UNITY_WEBGL && !UNITY_EDITOR && !UNITY_SERVER
            try
            {
                FestaNotifyBoothInteract(json);
            }
            catch (System.Exception ex)
            {
                // 브리지 하나의 실패가 다른 부스 오브젝트 상호작용을 막지 않는다.
                Debug.LogError($"[BoothInteractBridge] onBoothInteract 송신 실패: {ex.Message}");
            }
#elif !UNITY_SERVER
            Debug.Log($"[BoothInteractBridge] onBoothInteract → {json}");
#endif
        }

        static string BuildLaptopJson(int boothId, string objectId, string url)
        {
            var json = new StringBuilder(128)
                .Append("{\"type\":\"BOOTH_LAPTOP_INTERACT\",\"boothId\":")
                .Append(boothId)
                .Append(",\"objectId\":\"")
                .Append(EscapeJson(objectId))
                .Append('"');

            if (!string.IsNullOrWhiteSpace(url))
                json.Append(",\"url\":\"").Append(EscapeJson(url)).Append('"');

            return json.Append('}').ToString();
        }

        static string EscapeJson(string value) => value
            .Replace("\\", "\\\\")
            .Replace("\"", "\\\"")
            .Replace("\r", "\\r")
            .Replace("\n", "\\n")
            .Replace("\t", "\\t");
    }
}
