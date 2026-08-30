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
        static string BuildJson(string type, int boothId, string objectId, int? configId = null)
        {
            var method = typeof(BoothInteractBridge).GetMethod(
                "BuildJson", BindingFlags.NonPublic | BindingFlags.Static);
            Assert.IsNotNull(method, "BuildJson 을 찾지 못했다 — 이름이 바뀌었으면 테스트도 갱신해라.");
            return (string)method.Invoke(null, new object[] { type, boothId, objectId, configId });
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
        public void 노트북_payload_는_boothId_와_objectId_뿐이다()
        {
            Assert.AreEqual(
                "{\"type\":\"BOOTH_LAPTOP_INTERACT\",\"boothId\":3,\"objectId\":\"lap\"}",
                BuildJson(BoothInteractBridge.LaptopInteract, 3, "lap"));
        }

        [Test]
        public void 노트북_payload_에_URL_은_절대_들어가지_않는다()
        {
            // 홈페이지 주소는 `booths.homepage_url` 에 있고 Layout 에는 없다 — Unity 는 그 값을
            // 알 수 없다 (헌법 25조). 예전에 있던 `url?` 선택 필드는 채울 출처가 없어
            // **한 번도 값이 실린 적이 없었고**, 남겨 두면 "Unity 가 URL 을 보낼 수도 있다" 로
            // 읽힌다. 되살아나면 이 테스트가 잡는다 (S15P21A604-297).
            StringAssert.DoesNotContain("url",
                BuildJson(BoothInteractBridge.LaptopInteract, 3, "lap"));
        }

        [Test]
        public void 값이_없는_선택_필드는_키_자체를_넣지_않는다()
        {
            // 0 을 보내면 받는 쪽이 "값이 있다" 로 읽는다.
            StringAssert.DoesNotContain("configId",
                BuildJson(BoothInteractBridge.LaptopInteract, 1, "o"));
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
            // objectId 는 Layout JSON 에서 오는 문자열이다. 스튜디오에서 붙여넣기로 들어오면
            // 개행·탭이 섞일 수 있고, 생으로 나가면 받는 쪽 JSON 파싱이 깨진다.
            var json = BuildJson(BoothInteractBridge.LaptopInteract, 1, "a\nb\tc");
            StringAssert.Contains("\\n", json);
            StringAssert.Contains("\\t", json);
            Assert.IsFalse(json.Contains("\n"), "생 개행이 JSON 에 남으면 파싱이 깨진다");
        }
    }
}
