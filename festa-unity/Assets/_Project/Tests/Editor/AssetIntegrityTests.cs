using System.Collections.Generic;
using System.Reflection;
using System.IO;
using System.Linq;
using NUnit.Framework;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.SceneManagement;

namespace Festa.Tests
{
    /// <summary>
    /// 조용히 깨지는 에셋 결함을 CI 에서 잡는다.
    ///
    /// 여기 있는 검사는 전부 **실제로 터졌던 사고**에서 나왔다. 둘 다 유니티가 에러를
    /// 내지 않아서 사람 눈으로만 발견됐고, 그 사이 잘못된 상태가 develop 에 머지됐다.
    /// </summary>
    public class AssetIntegrityTests
    {
        const string MainScenePath = "Assets/_Project/Scenes/main.unity";

        /// <summary>
        /// T-223 회귀 방지.
        ///
        /// S15P21A604-281 데시메이션이 서브메시 5개를 1개로 뭉갰다. 렌더러의 재질 슬롯은
        /// 5개 그대로였으므로 슬롯 1~4(거울·알루미늄·doorlock-gray·black)는 그릴 인덱스가
        /// 없어 통째로 미출력됐다 — 사물함 자물쇠가 화면에서 사라졌다.
        /// 유니티는 이걸 경고조차 하지 않는다. 정점 수만 보면 성공으로 보인다.
        /// </summary>
        [Test]
        public void 씬의_모든_렌더러는_서브메시보다_많은_재질슬롯을_갖지_않는다()
        {
            OpenMainScene();

            var offenders = new List<string>();
            foreach (var renderer in Object.FindObjectsByType<MeshRenderer>(
                         FindObjectsInactive.Exclude, FindObjectsSortMode.None))
            {
                var filter = renderer.GetComponent<MeshFilter>();
                if (filter == null || filter.sharedMesh == null) continue;

                int subMeshes = filter.sharedMesh.subMeshCount;
                int slots = renderer.sharedMaterials.Length;
                if (slots <= subMeshes) continue;

                var lost = renderer.sharedMaterials
                    .Skip(subMeshes)
                    .Where(m => m != null)
                    .Select(m => m.name);

                offenders.Add($"{HierarchyPath(renderer.transform)} " +
                              $"— 서브메시 {subMeshes} < 재질슬롯 {slots}, " +
                              $"미출력: {string.Join(", ", lost)}");
            }

            Assert.IsEmpty(offenders,
                "재질 슬롯이 서브메시보다 많은 렌더러가 있다. 해당 재질은 화면에 그려지지 않는다.\n" +
                string.Join("\n", offenders));
        }

        /// <summary>
        /// T-221 회귀 방지.
        ///
        /// `.cs` 는 커밋됐는데 짝이 되는 `.meta` 가 빠진 적이 있다. 유니티는 `.meta` 가
        /// 없으면 새 GUID 를 발급하므로, 그 스크립트를 GUID 로 참조하던 씬·프리팹이
        /// 받는 쪽에서 Missing Script 가 된다. 내 머신에는 `.meta` 가 있어서
        /// 에디터·빌드·플레이가 전부 정상으로 보였다 —
        /// **로컬이 멀쩡한 것은 저장소가 멀쩡하다는 증거가 아니다.**
        /// </summary>
        [Test]
        public void 모든_스크립트는_짝이_되는_meta_파일을_갖는다()
        {
            var projectRoot = Directory.GetParent(Application.dataPath)!.FullName;
            var scriptsRoot = Path.Combine(Application.dataPath, "_Project");

            var missing = Directory
                .EnumerateFiles(scriptsRoot, "*.cs", SearchOption.AllDirectories)
                .Where(cs => !File.Exists(cs + ".meta"))
                .Select(cs => Path.GetRelativePath(projectRoot, cs))
                .ToList();

            Assert.IsEmpty(missing,
                ".meta 가 없는 스크립트가 있다. 받는 쪽에서 새 GUID 가 발급되어 참조가 끊긴다.\n" +
                string.Join("\n", missing));
        }

        /// <summary>
        /// 씬이 참조하는 스크립트가 실제로 존재하는지 본다.
        /// Missing Script 는 플레이하기 전까지 조용하다.
        /// </summary>
        [Test]
        public void 씬에_Missing_Script_컴포넌트가_없다()
        {
            OpenMainScene();

            var offenders = new List<string>();
            foreach (var go in Object.FindObjectsByType<GameObject>(
                         FindObjectsInactive.Include, FindObjectsSortMode.None))
            {
                var components = go.GetComponents<Component>();
                for (int i = 0; i < components.Length; i++)
                {
                    if (components[i] != null) continue;
                    offenders.Add($"{HierarchyPath(go.transform)} — 컴포넌트 슬롯 {i}");
                }
            }

            Assert.IsEmpty(offenders,
                "씬에 Missing Script 가 있다. 참조가 끊긴 컴포넌트다.\n" +
                string.Join("\n", offenders));
        }

        /// <summary>
        /// T-148 회귀 방지 — **실제 배포되는 레지스트리**가 모호하지 않은지 본다.
        ///
        /// `BoothObjectRegistryTests` 는 정책을 고정하고, 이 검사는 **데이터**를 본다.
        /// 지금은 타입당 자산이 하나뿐이라 모호함이 없지만, FE 가 `assetCode` 목록을 주면
        /// 타입당 2개 이상이 되는 순간 기본이 배열 순서로 갈린다. 그때 여기서 걸린다.
        /// </summary>
        [Test]
        public void 부스_오브젝트_레지스트리에_모호한_타입_기본이_없다()
        {
            var guids = AssetDatabase.FindAssets("t:" + nameof(Festa.Booth.BoothObjectRegistry));
            Assert.IsNotEmpty(guids, "BoothObjectRegistry 에셋을 찾지 못했다.");

            var offenders = new List<string>();
            foreach (var guid in guids)
            {
                var path = AssetDatabase.GUIDToAssetPath(guid);
                var registry = AssetDatabase.LoadAssetAtPath<Festa.Booth.BoothObjectRegistry>(path);
                if (registry == null) continue;

                var entries = (List<Festa.Booth.BoothObjectRegistry.Entry>)typeof(Festa.Booth.BoothObjectRegistry)
                    .GetField("_entries", BindingFlags.NonPublic | BindingFlags.Instance)
                    .GetValue(registry);

                foreach (var group in entries.Where(e => e?.prefab != null).GroupBy(e => e.type))
                {
                    int blanks = group.Count(e => string.IsNullOrEmpty(e.assetCode));
                    if (blanks > 1)
                        offenders.Add($"{path} — {group.Key}: 기본(빈 assetCode) 엔트리가 {blanks}개");
                    else if (blanks == 0 && group.Count() > 1)
                        offenders.Add($"{path} — {group.Key}: 기본 미선언인데 후보가 {group.Count()}개 " +
                                      "(배열 순서로 갈린다)");
                }
            }

            Assert.IsEmpty(offenders,
                "타입 기본 자산이 배열 순서에 좌우되는 항목이 있다. 기본으로 쓸 엔트리의 " +
                "assetCode 를 비워라 (T-148).\n" + string.Join("\n", offenders));
        }

        /// <summary>
        /// T-269 회귀 방지 — <b>테스트 코드가 배포본에 섞여 들어가는 것</b>을 막는다.
        ///
        /// <para>2026-09-13 릴리스 빌드가 이렇게 죽었다.</para>
        /// <code>
        /// Mono.Cecil.AssemblyResolutionException: Failed to resolve assembly:
        ///   'nunit.framework, Version=3.5.0.0, Culture=neutral, PublicKeyToken=null'
        /// </code>
        /// <para><c>Tests/EditMode/</c> 에 asmdef 가 없어 그 안의 테스트가 <b>기본 어셈블리
        /// (Assembly-CSharp)</b> 로 들어갔다. 기본 어셈블리는 플레이어 빌드에 포함되므로 NUnit 이
        /// 통째로 끌려오고, 관리 코드 스트리핑 단계에서 해석에 실패한다.</para>
        ///
        /// <para><b>에디터에서는 아무 문제가 없다.</b> 에디터에는 NUnit 이 있으니 컴파일도 되고
        /// 테스트도 돈다 — <b>플레이어 빌드에 가서야</b> 드러난다. 그래서 "컴파일 + EditMode" 게이트로는
        /// 이 부류가 잡히지 않는다. 이 검사가 그 자리를 메운다(몇 초면 끝난다).</para>
        /// </summary>
        [Test]
        public void 테스트_전용_코드는_테스트_어셈블리_안에만_있다()
        {
            const string root = "Assets/_Project";
            var offenders = new List<string>();

            foreach (var file in Directory.GetFiles(root, "*.cs", SearchOption.AllDirectories))
            {
                var text = File.ReadAllText(file);
                if (!text.Contains("NUnit.Framework") && !text.Contains("UnityEngine.TestTools")) continue;

                var asmdef = NearestAsmdef(Path.GetDirectoryName(file));
                if (asmdef == null)
                {
                    offenders.Add($"{Rel(file)} — asmdef 밖이다 (기본 어셈블리로 들어가 배포본에 실린다)");
                    continue;
                }
                // 테스트 어셈블리는 에디터 전용이고 UNITY_INCLUDE_TESTS 로 플레이어에서 빠진다.
                var json = File.ReadAllText(asmdef);
                if (!json.Contains("UNITY_INCLUDE_TESTS"))
                    offenders.Add($"{Rel(file)} — {Path.GetFileName(asmdef)} 에 UNITY_INCLUDE_TESTS 제약이 없다");
            }

            Assert.IsEmpty(offenders,
                "테스트 전용 코드가 배포되는 어셈블리에 있다. 플레이어 빌드가 nunit.framework 해석 실패로 죽는다 " +
                "(T-269). Tests/Editor/ 로 옮기거나 UNITY_INCLUDE_TESTS 를 건 asmdef 를 둬라.\n"
                + string.Join("\n", offenders));
        }

        /// <summary>이 폴더부터 위로 올라가며 처음 만나는 asmdef. 없으면 기본 어셈블리에 들어간다는 뜻이다.</summary>
        static string NearestAsmdef(string dir)
        {
            var full = Path.GetFullPath(dir);
            var stop = Path.GetFullPath("Assets");
            while (!string.IsNullOrEmpty(full) && full.StartsWith(stop))
            {
                var found = Directory.GetFiles(full, "*.asmdef", SearchOption.TopDirectoryOnly);
                if (found.Length > 0) return found[0];
                full = Path.GetDirectoryName(full);
            }
            return null;
        }

        static string Rel(string path) => path.Replace('\\', '/');

        static void OpenMainScene()
        {
            var scene = SceneManager.GetActiveScene();
            if (scene.path != MainScenePath)
                EditorSceneManager.OpenScene(MainScenePath, OpenSceneMode.Single);
        }

        static string HierarchyPath(Transform t)
        {
            var path = t.name;
            while (t.parent != null)
            {
                t = t.parent;
                path = t.name + "/" + path;
            }
            return path;
        }
    }
}
