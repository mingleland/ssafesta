using System.Collections.Generic;
using System.Reflection;
using Festa.Booth;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;

namespace Festa.Tests
{
    /// <summary>
    /// 타입 기본 자산 정책 (T-148, S15P21A604-104).
    ///
    /// 예전에는 같은 타입의 엔트리를 last-wins 로 덮어써서 **배열 끝에 있는 것**이 기본이 됐다.
    /// 카탈로그가 타입당 1개인 동안은 발현하지 않지만, 자산이 2개로 늘고 오타 fallback(T-145)과
    /// 겹치면 **오타 하나로 예측 불가능한 자산이 경고 없이** 나온다.
    ///
    /// 여기서 고정하는 것은 두 가지다 — ① 순서에 맡기지 않는다 ② 정할 수 없으면 침묵하지 않는다.
    /// </summary>
    public class BoothObjectRegistryTests
    {
        static BoothObjectRegistry Make(params (BoothObjectType type, string code, string name)[] rows)
        {
            var registry = ScriptableObject.CreateInstance<BoothObjectRegistry>();
            var entries = new List<BoothObjectRegistry.Entry>();
            foreach (var r in rows)
            {
                entries.Add(new BoothObjectRegistry.Entry
                {
                    type = r.type,
                    assetCode = r.code,
                    // 프리팹 대신 이름만 다른 빈 GameObject 로 어느 것이 골렸는지 구분한다.
                    prefab = new GameObject(r.name),
                });
            }
            typeof(BoothObjectRegistry)
                .GetField("_entries", BindingFlags.NonPublic | BindingFlags.Instance)
                .SetValue(registry, entries);
            registry.Invalidate();
            return registry;
        }

        [Test]
        public void assetCode_가_빈_엔트리가_타입_기본이다()
        {
            var r = Make(
                (BoothObjectType.Furniture, "CHAIR", "coded"),
                (BoothObjectType.Furniture, "", "default"),
                (BoothObjectType.Furniture, "DESK", "coded2"));

            // 배열 순서상 마지막은 coded2 다. 예전 구현이라면 그게 나왔다.
            Assert.AreEqual("default", r.GetPrefab(BoothObjectType.Furniture).name);
        }

        [Test]
        public void 후보가_하나면_코드가_붙어_있어도_기본으로_쓴다()
        {
            // 실제 레지스트리의 Furniture·Decoration 이 이 모양이다 —
            // assetCode 는 있는데 그 타입의 엔트리가 하나뿐이다. 여기서 null 을 돌려주면
            // 가구·장식이 placeholder 로 떨어지는 회귀가 난다.
            var r = Make((BoothObjectType.Furniture, "FURNITURE_DEFAULT", "only"));

            Assert.AreEqual("only", r.GetPrefab(BoothObjectType.Furniture).name);
        }

        [Test]
        public void 기본이_없고_후보가_둘_이상이면_경고한다()
        {
            var r = Make(
                (BoothObjectType.Furniture, "CHAIR", "a"),
                (BoothObjectType.Furniture, "DESK", "b"));

            // 조용히 순서에 맡기지 않는다 — 이 경고가 없으면 T-148 이 그대로 재발한다.
            LogAssert.Expect(LogType.Warning, new System.Text.RegularExpressions.Regex(
                "타입 기본 자산이 정해지지 않았다"));

            Assert.IsNotNull(r.GetPrefab(BoothObjectType.Furniture));
        }

        [Test]
        public void 기본_엔트리가_둘이면_경고한다()
        {
            var r = Make(
                (BoothObjectType.Furniture, "", "first"),
                (BoothObjectType.Furniture, "", "second"));

            LogAssert.Expect(LogType.Warning, new System.Text.RegularExpressions.Regex(
                "기본 자산이 2개 선언됐다"));

            Assert.AreEqual("first", r.GetPrefab(BoothObjectType.Furniture).name);
        }

        [Test]
        public void assetCode_로_고르면_그_자산이_나온다()
        {
            var r = Make(
                (BoothObjectType.Furniture, "", "default"),
                (BoothObjectType.Furniture, "CHAIR", "chair"));

            Assert.AreEqual("chair", r.GetPrefab(BoothObjectType.Furniture, "CHAIR").name);
        }

        [Test]
        public void 모르는_assetCode_는_경고하고_기본으로_대체한다()
        {
            var r = Make(
                (BoothObjectType.Furniture, "", "default"),
                (BoothObjectType.Furniture, "CHAIR", "chair"));

            // T-145 — 오타가 조용히 fallback 되면 안 된다.
            LogAssert.Expect(LogType.Warning, new System.Text.RegularExpressions.Regex(
                "Unknown assetCode"));

            Assert.AreEqual("default", r.GetPrefab(BoothObjectType.Furniture, "CHIAR").name);
        }

        [Test]
        public void 캐시는_Invalidate_로_버려진다()
        {
            // T-148 증상 2 — 예전에는 `??=` 로 최초 1회만 만들고 무효화 수단이 없어,
            // 엔트리를 추가해도 옛 결과가 나왔다.
            var r = Make((BoothObjectType.Furniture, "", "before"));
            Assert.AreEqual("before", r.GetPrefab(BoothObjectType.Furniture).name);

            var field = typeof(BoothObjectRegistry)
                .GetField("_entries", BindingFlags.NonPublic | BindingFlags.Instance);
            var entries = (List<BoothObjectRegistry.Entry>)field.GetValue(r);
            entries.Clear();
            entries.Add(new BoothObjectRegistry.Entry
            {
                type = BoothObjectType.Furniture,
                assetCode = "",
                prefab = new GameObject("after"),
            });

            r.Invalidate();
            Assert.AreEqual("after", r.GetPrefab(BoothObjectType.Furniture).name);
        }

        [Test]
        public void 매핑이_없는_타입은_null_이다()
        {
            // Factory 가 placeholder 로 대체하는 경로다. 여기서 아무거나 돌려주면 안 된다.
            var r = Make((BoothObjectType.Furniture, "", "furniture"));

            Assert.IsNull(r.GetPrefab(BoothObjectType.Laptop));
        }
    }
}
