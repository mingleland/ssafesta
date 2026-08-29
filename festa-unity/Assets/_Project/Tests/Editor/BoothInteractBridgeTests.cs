using System.Reflection;
using Festa.Integration;
using NUnit.Framework;

namespace Festa.Tests
{
    /// <summary>
    /// FE 가 받는 payload 를 고정한다 (S15P21A604-303·-304·-77).
    ///
    /// 이 JSON 은 **계약**이다. `AI_AGENT_INTERACT { boothId, objectId, configId }` 는
    /// 2026-08-20 에 FE·Unity·AI 3파트가 확정했다 (Issue #2). 필드 이름 하나만 바뀌어도
    /// 받는 쪽이 조용히 undefined 를 읽는다 — **Unity 쪽에서는 아무 오류도 나지 않는다.**
    /// 그래서 문자열 자체를 테스트로 박아 둔다.
    ///
    /// `BuildJson` 은 비공개다. 공개 메서드는 WebGL 이 아니면 로그만 남기고 문자열을
    /// 돌려주지 않아 검증할 수 없어서, 여기서는 리플렉션으로 직접 겨눈다.
    /// </summary>
    public class BoothInteractBridgeTests
    {
        static string BuildJson(string type, int boothId, string objectId,
                                string url = null, int? configId = null)
        {
            var method = typeof(BoothInteractBridge).GetMethod(
                "BuildJson", BindingFlags.NonPublic | BindingFlags.Static);
            Assert.IsNotNull(method, "BuildJson 을 찾지 못했다 — 이름이 바뀌었으면 테스트도 갱신해라.");
            return (string)method.Invoke(null, new object[] { type, boothId, objectId, url, configId });
        }

        [Test]
        public void AI_직원_payload_는_계약대로_boothId_objectId_configId_다()
        {
            var json = BuildJson(BoothInteractBridge.AiAgentInteract, 7, "obj-1", configId: 42);

            Assert.AreEqual(
                "{\"type\":\"AI_AGENT_INTERACT\",\"boothId\":7,\"objectId\":\"obj-1\",\"configId\":42}",
                json);
        }

        [Test]
        public void 노트북_payload_는_url_이_있을_때만_url_을_넣는다()
        {
            Assert.AreEqual(
                "{\"type\":\"BOOTH_LAPTOP_INTERACT\",\"boothId\":3,\"objectId\":\"lap\"}",
                BuildJson(BoothInteractBridge.LaptopInteract, 3, "lap"));

            Assert.AreEqual(
                "{\"type\":\"BOOTH_LAPTOP_INTERACT\",\"boothId\":3,\"objectId\":\"lap\",\"url\":\"https://a.b\"}",
                BuildJson(BoothInteractBridge.LaptopInteract, 3, "lap", url: "https://a.b"));
        }

        [Test]
        public void 값이_없는_선택_필드는_키_자체를_넣지_않는다()
        {
            // 빈 문자열이나 0 을 보내면 받는 쪽이 "값이 있다" 로 읽는다.
            var json = BuildJson(BoothInteractBridge.LaptopInteract, 1, "o", url: "   ");
            StringAssert.DoesNotContain("url", json);
            StringAssert.DoesNotContain("configId", json);
        }

        [Test]
        public void 이벤트_종류가_payload_에_그대로_들어간다()
        {
            // 이 상수들이 FE events.ts 와 어긋나면 계약이 깨진다.
            Assert.AreEqual("BOOTH_LAPTOP_INTERACT", BoothInteractBridge.LaptopInteract);
            Assert.AreEqual("AI_AGENT_INTERACT", BoothInteractBridge.AiAgentInteract);
        }

        [Test]
        public void objectId_의_따옴표와_역슬래시가_JSON_을_깨뜨리지_않는다()
        {
            var json = BuildJson(BoothInteractBridge.AiAgentInteract, 1, "a\"b\\c", configId: 5);

            Assert.AreEqual(
                "{\"type\":\"AI_AGENT_INTERACT\",\"boothId\":1,\"objectId\":\"a\\\"b\\\\c\",\"configId\":5}",
                json);
        }

        [Test]
        public void 줄바꿈이_섞여도_이스케이프된다()
        {
            // 부스 이름·URL 이 붙여넣기로 들어오면 개행이 섞일 수 있다.
            var json = BuildJson(BoothInteractBridge.LaptopInteract, 1, "o", url: "a\nb\tc");
            StringAssert.Contains("\\n", json);
            StringAssert.Contains("\\t", json);
            Assert.IsFalse(json.Contains("\n"), "생 개행이 JSON 에 남으면 파싱이 깨진다");
        }
    }
}
