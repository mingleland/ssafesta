using System.IO;
using Festa.Integration;
using NUnit.Framework;
using UnityEngine;

namespace Festa.Tests
{
    /// <summary>world-session endpoint 계약과 정식 접속 경계를 고정한다 (infra-003 T016).</summary>
    public class WorldSessionEndpointTests
    {
        static WorldSessionDto Session(string scheme, string host, int port) => new()
        {
            endpoint = new WorldEndpointDto { scheme = scheme, host = host, port = port }
        };

        [Test]
        public void 배포_wss_endpoint는_TLS와_443으로_매핑된다()
        {
            Assert.IsTrue(WorldSessionEndpoint.TryGetConnectionData(
                Session("wss", "world.ssafesta.world", 443), out var host, out var port, out var useTls));
            Assert.AreEqual("world.ssafesta.world", host);
            Assert.AreEqual(443, port);
            Assert.IsTrue(useTls);
        }

        [Test]
        public void 로컬_ws_endpoint는_평문으로_매핑된다()
        {
            Assert.IsTrue(WorldSessionEndpoint.TryGetConnectionData(
                Session("ws", "127.0.0.1", 7777), out _, out var port, out var useTls));
            Assert.AreEqual(7777, port);
            Assert.IsFalse(useTls);
        }

        [TestCase("wss", "127.0.0.1", 443)]
        [TestCase("wss", "world.ssafesta.world", 7777)]
        [TestCase("wss", "localhost", 443)]
        [TestCase("ftp", "world.ssafesta.world", 443)]
        [TestCase("wss", "bad host", 443)]
        [TestCase("wss", "world.ssafesta.world", 0)]
        public void 유효하지_않은_공개_endpoint는_거부한다(string scheme, string host, int port)
        {
            Assert.IsFalse(WorldSessionEndpoint.TryGetConnectionData(Session(scheme, host, port),
                out _, out _, out _));
        }

        [Test]
        public void 정식_session_접속_경로에는_직접_주소나_raw_포트가_없다()
        {
            var root = Directory.GetParent(Application.dataPath)!.FullName;
            var path = Path.Combine(root, "Assets", "_Project", "Scripts", "Network", "Connection", "ConnectionManager.cs");
            var source = File.ReadAllText(path);
            int start = source.IndexOf("public bool StartClient(WorldSessionDto session", System.StringComparison.Ordinal);
            int end = source.IndexOf("/// <summary>개발용 직접 접속", start, System.StringComparison.Ordinal);

            Assert.GreaterOrEqual(start, 0, "정식 world-session 접속 메서드를 찾지 못했다.");
            Assert.Greater(end, start, "개발용 직접 접속 경계를 찾지 못했다.");

            var productionPath = source.Substring(start, end - start);
            StringAssert.DoesNotContain("127.0.0.1", productionPath);
            StringAssert.DoesNotContain("7777", productionPath);
            StringAssert.Contains("WorldSessionEndpoint.TryGetConnectionData", productionPath);
        }
    }
}
