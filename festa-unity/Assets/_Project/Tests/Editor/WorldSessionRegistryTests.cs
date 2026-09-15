using Festa.Network;
using NUnit.Framework;

namespace Festa.Tests
{
    /// <summary>
    /// 한 신원당 접속 하나 (S15P21A604-231, docs/12 §9 "Player 중복 Spawn 방지").
    ///
    /// 여기서 틀리면 **한 사람이 둘로 스폰되거나**, 반대로 **밀려난 연결이 끊기면서 새 접속의
    /// 등록을 지워버려** 이후 중복이 감지되지 않는다. 두 번째 쪽은 눈에 안 띄어서 더 나쁘다.
    /// </summary>
    public class WorldSessionRegistryTests
    {
        [SetUp]
        public void Reset() => WorldSessionRegistry.Clear();

        [Test]
        public void 서로_다른_신원은_함께_접속한다()
        {
            Assert.IsFalse(WorldSessionRegistry.TryRegister("user-1", 1, out _));
            Assert.IsFalse(WorldSessionRegistry.TryRegister("user-2", 2, out _));
            Assert.AreEqual(2, WorldSessionRegistry.Count);
        }

        [Test]
        public void 같은_신원이_다시_접속하면_이전_접속을_알려준다()
        {
            WorldSessionRegistry.TryRegister("user-1", 1, out _);

            Assert.IsTrue(WorldSessionRegistry.TryRegister("user-1", 2, out var stale));
            Assert.AreEqual(1UL, stale, "끊어야 할 이전 clientId 를 돌려줘야 한다");
        }

        [Test]
        public void 나중_접속이_이긴다()
        {
            // 반대로 하면 클라이언트가 죽은 뒤 전송 타임아웃 전까지 본인이 자기 계정에
            // 못 들어온다 — 사용자 눈에는 그냥 "접속이 안 된다" 다.
            WorldSessionRegistry.TryRegister("user-1", 1, out _);
            WorldSessionRegistry.TryRegister("user-1", 2, out _);

            Assert.AreEqual("user-1", WorldSessionRegistry.SubjectOf(2), "새 접속이 등록돼 있어야 한다");
            Assert.IsNull(WorldSessionRegistry.SubjectOf(1), "밀려난 접속은 빠져 있어야 한다");
            Assert.AreEqual(1, WorldSessionRegistry.Count);
        }

        [Test]
        public void 밀려난_접속이_뒤늦게_끊겨도_새_접속의_등록을_지우지_않는다()
        {
            // 실제 순서가 이렇다: 새 접속 승인 → 이전 연결에 Disconnect 요청 → **그 뒤에**
            // 이전 연결의 ClientDisconnected 이벤트가 온다. 그때 새 접속의 등록이 지워지면
            // 이후 중복이 감지되지 않는다 — 눈에 안 띄어서 더 나쁜 고장이다.
            //
            // 이 보호는 `Remove` 가 아니라 `TryRegister` 에 있다. 거기서 밀려난 clientId 를
            // 이미 지우므로 뒤늦은 Remove 가 아무것도 건드리지 못한다. 여기서는 **관찰 가능한
            // 결과**를 고정한다 — 어느 쪽이 지키든 이 결과는 유지돼야 한다.
            WorldSessionRegistry.TryRegister("user-1", 1, out _);
            WorldSessionRegistry.TryRegister("user-1", 2, out _);

            WorldSessionRegistry.Remove(1);   // 뒤늦게 도착한 이전 연결의 종료

            Assert.AreEqual("user-1", WorldSessionRegistry.SubjectOf(2), "새 접속이 살아 있어야 한다");
            Assert.IsTrue(WorldSessionRegistry.TryRegister("user-1", 3, out var stale),
                          "중복 감지가 계속 동작해야 한다");
            Assert.AreEqual(2UL, stale);
        }

        [Test]
        public void 끊긴_뒤_같은_신원이_다시_붙으면_중복이_아니다()
        {
            WorldSessionRegistry.TryRegister("user-1", 1, out _);
            WorldSessionRegistry.Remove(1);

            Assert.IsFalse(WorldSessionRegistry.TryRegister("user-1", 2, out _),
                           "정상 재접속을 중복으로 보면 안 된다");
            Assert.AreEqual(1, WorldSessionRegistry.Count);
        }

        [Test]
        public void 같은_clientId_로_다시_등록해도_자기_자신을_끊지_않는다()
        {
            WorldSessionRegistry.TryRegister("user-1", 1, out _);

            Assert.IsFalse(WorldSessionRegistry.TryRegister("user-1", 1, out _),
                           "자기 자신을 stale 로 돌려주면 방금 승인한 접속을 끊게 된다");
        }

        [Test]
        public void 신원이_비어_있으면_등록하지_않는다()
        {
            // 게스트라도 Backend 가 sub 를 채워 보낸다. 비어 있다는 것은 검증을 통과하지
            // 못했다는 뜻이므로, 빈 문자열끼리 묶어 서로를 끊게 두면 안 된다.
            Assert.IsFalse(WorldSessionRegistry.TryRegister("", 1, out _));
            Assert.IsFalse(WorldSessionRegistry.TryRegister(null, 2, out _));
            Assert.AreEqual(0, WorldSessionRegistry.Count);
        }

        [Test]
        public void 모르는_clientId_를_지워도_안전하다()
        {
            WorldSessionRegistry.TryRegister("user-1", 1, out _);
            Assert.DoesNotThrow(() => WorldSessionRegistry.Remove(999));
            Assert.AreEqual(1, WorldSessionRegistry.Count);
        }

        // ---------- 종료 사유 안내 ----------

        [Test]
        public void 알려진_종료_사유는_사람이_읽을_문구로_바뀐다()
        {
            StringAssert.Contains("만료",
                WorldDisconnectReporter.Explain(WorldDisconnectReason.InvalidToken));
            StringAssert.Contains("정원",
                WorldDisconnectReporter.Explain(WorldDisconnectReason.ServerFull));
            StringAssert.Contains("다른 곳",
                WorldDisconnectReporter.Explain(WorldDisconnectReason.ReplacedBySameUser));
        }

        [Test]
        public void 모르는_종료_사유는_뭉개지_않고_그대로_보여준다()
        {
            // "알 수 없는 오류" 로 덮으면 서버가 새 사유를 추가했을 때 그 사실이 사라진다.
            Assert.AreEqual("SOME_NEW_REASON", WorldDisconnectReporter.Explain("SOME_NEW_REASON"));
        }
    }
}
