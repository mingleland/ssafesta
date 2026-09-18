// 부스 안 뒷벽 위쪽에 그 부스의 이름을 음각처럼 새긴다 (사용자 지시 2026-09-18).
// 이 파일이 있는 이유: 실내에는 지금 어느 부스에 들어와 있는지 알려 주는 표시가 하나도 없다.
// 바깥 간판은 들어오는 순간 시야에서 사라지고, 뒷벽의 트러스 위 구간은 통째로 비어 있다.
using System.Collections.Generic;
using Festa.Booth;
using TMPro;
using UnityEngine;
using UnityEngine.Rendering;
using UnityEngine.SceneManagement;

namespace Festa.World
{
    /// <summary>
    /// 12개 부스 실내의 뒷벽에 부스명을 새긴다.
    ///
    /// <para><b>음각은 셰이더가 아니라 세 겹으로 만든다.</b> TMP 의 Bevel·Underlay 는 셰이더 키워드라
    /// WebGL 스트리핑에 걸리면 빌드에서만 깨진다(28 §0-5, T-213·T-227 계열). 대신 같은 글자를
    /// 위로 민 어두운 겹 · 아래로 민 밝은 겹 · 벽색 면 순으로 겹친다. 위에서 빛이 오는 방으로 보면
    /// 파인 홈의 윗면은 그늘, 아랫면은 빛을 받으므로 이 배치가 그대로 새긴 글씨로 읽힌다.
    /// 색만 다르고 머티리얼은 하나라 셰이더 변형도, 드로우콜 배칭도 그대로다.</para>
    ///
    /// <para><b>폰트·머티리얼은 같은 방의 <c>FasciaText</c> 에서 빌려 온다.</b> <c>Resources.Load</c> 로
    /// 집어오면 에디터에서는 되고 빌드에서 조용히 null 이 되는 부류다 — 이미 그 방에서 쓰이고 있어
    /// 빌드에 반드시 포함되는 것을 그대로 쓴다.</para>
    ///
    /// <para><b>이름은 새로 조회하지 않는다.</b> 바깥 간판이 쓰는 해석
    /// (<c>facade.signText</c> → <c>boothName</c> → <c>projects[0].name</c>)을 그대로 읽는다
    /// (<see cref="BoothSignPresenter.TryGetInfo"/>). 임대되지 않은 칸에는 아무것도 새기지 않는다.</para>
    ///
    /// 로컬 표시 전용 — NetworkObject 없음.
    /// </summary>
    [DisallowMultipleComponent]
    public sealed class BoothInteriorNamePlate : MonoBehaviour
    {
        const string ObjectName = "BoothInteriorNamePlate";
        const string PlateName = "@BoothNamePlate";
        const int InteriorCount = 12;
        const int ScanInterval = 45;      // 프레임 — 0.75 초쯤

        /// <summary>뒷벽에서 이만큼 앞까지를 "뒷벽에 붙은 것" 으로 본다(u). 글자를 트러스 위로 올릴 때 쓴다.</summary>
        const float NearBackDepth = 7f;

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
        static void AutoRegister()
        {
            if (Application.isBatchMode) return;
            if (FindAnyObjectByType<BoothInteriorNamePlate>() != null) return;
            var go = new GameObject(ObjectName);
            go.AddComponent<BoothInteriorNamePlate>();
            DontDestroyOnLoad(go);
        }

        sealed class Plate
        {
            public GameObject Root;
            public TextMeshPro Face;
            public TextMeshPro Shadow;
            public TextMeshPro Highlight;
            public string Applied;
        }

        readonly Dictionary<int, Plate> _plates = new();
        Transform _interiorsRoot;

        void Update()
        {
            if (Time.frameCount % ScanInterval != 0) return;

            var root = ResolveRoot();
            if (root == null) return;

            for (int slot = 1; slot <= InteriorCount; slot++)
            {
                var interior = root.Find($"Interior_{slot:00}");
                if (interior == null) continue;

                // 인테리어가 다시 만들어지면 붙여 둔 글자도 같이 사라진다 — 없으면 다시 만든다.
                if (!_plates.TryGetValue(slot, out var plate) || plate == null || plate.Root == null)
                {
                    plate = Build(interior);
                    if (plate == null) continue;
                    _plates[slot] = plate;
                }

                Fill(slot, plate);
            }
        }

        /// <summary>
        /// <c>@BoothInteriors</c> 루트. <c>GameObject.Find</c> 는 활성 오브젝트만 찾아서, 인테리어가
        /// 꺼져 있는 동안에는 null 이 된다 — 씬 루트 목록으로 찾고 캐시한다.
        /// </summary>
        Transform ResolveRoot()
        {
            if (_interiorsRoot != null) return _interiorsRoot;
            var scene = SceneManager.GetActiveScene();
            if (!scene.IsValid()) return null;
            foreach (var go in scene.GetRootGameObjects())
                if (go.name == "@BoothInteriors") { _interiorsRoot = go.transform; return _interiorsRoot; }
            return null;
        }

        Plate Build(Transform interior)
        {
            var wall = interior.Find("Wall_Back");
            var fascia = interior.Find("FasciaText");
            var source = fascia != null ? fascia.GetComponent<TextMeshPro>() : null;
            if (wall == null || source == null || source.font == null) return null;

            // 벽은 Box 프리미티브를 늘려 만든 것이라 로컬 값만으로 면의 위치가 나온다.
            float faceZ = wall.localPosition.z + wall.localScale.z * 0.5f;   // 방 쪽 면
            float wallTop = wall.localPosition.y + wall.localScale.y * 0.5f;
            float wallWidth = wall.localScale.x;

            float occupiedTop = HighestNearBack(interior, faceZ, wallTop);
            float band = wallTop - occupiedTop;
            if (band < 6f) return null;                                       // 글자를 넣을 자리가 없다

            float capHeight = Mathf.Clamp(band * 0.34f, 4f, 10f);
            float centerY = occupiedTop + band * 0.5f;

            var plateGo = new GameObject(PlateName);
            plateGo.transform.SetParent(interior, false);
            plateGo.transform.localPosition = new Vector3(wall.localPosition.x, centerY, faceZ + 0.06f);
            plateGo.transform.localRotation = Quaternion.identity;

            // 홈의 깊이로 읽히는 어긋남. 0.055 는 가까이서 보면 아래쪽 밝은 선이 굵어져 새김이 아니라
            // 흰 테두리로 보였다 — 얇게 낸다 (실측 2026-09-18).
            float lift = capHeight * 0.038f;

            var plate = new Plate { Root = plateGo };
            // 먼 것부터 만든다 — 같은 평면이라 z 로 앞뒤를 가른다. 카메라는 방 안(+z)에 있다.
            plate.Highlight = Layer(plateGo.transform, "Highlight", source, capHeight, wallWidth,
                                    new Vector3(0f, -lift, -0.04f), HighlightColor);
            plate.Shadow = Layer(plateGo.transform, "Shadow", source, capHeight, wallWidth,
                                 new Vector3(0f, lift, -0.02f), ShadowColor);
            plate.Face = Layer(plateGo.transform, "Face", source, capHeight, wallWidth,
                               Vector3.zero, FaceColor);

            plateGo.SetActive(false);            // 이름을 받기 전에는 빈 글자가 뜨지 않게
            return plate;
        }

        static TextMeshPro Layer(Transform parent, string name, TextMeshPro source,
                                 float capHeight, float wallWidth, Vector3 offset, Color color)
        {
            // RectTransform 을 먼저 붙인다 — 뒤에 붙이면 Transform 이 교체되어 참조가 깨진다(S15P21A604-355).
            var go = new GameObject(name, typeof(RectTransform));
            go.transform.SetParent(parent, false);
            go.transform.localPosition = offset;
            // **180° 돌려야 읽힌다.** 이 프로젝트의 월드 글자는 앞면이 −Z 를 본다 — 앞벽의
            // FasciaText·ExitDoorLabel 이 회전 없이 방 안쪽(−Z 방향)을 향해 제대로 읽힌다.
            // 뒷벽 글자는 반대편을 보므로 그대로 두면 좌우가 뒤집혀 나온다 (실측 2026-09-18).
            go.transform.localRotation = Quaternion.Euler(0f, 180f, 0f);

            var tmp = go.AddComponent<TextMeshPro>();
            tmp.font = source.font;
            tmp.fontSharedMaterial = source.fontSharedMaterial;   // 같은 머티리얼 — 색은 정점색으로 준다
            tmp.color = color;
            tmp.alignment = TextAlignmentOptions.Center;
            tmp.textWrappingMode = TextWrappingModes.NoWrap;
            tmp.overflowMode = TextOverflowModes.Overflow;
            tmp.fontSize = capHeight * 10f;
            tmp.characterSpacing = 10f;          // 벽에 새긴 글씨는 자간을 벌려야 읽힌다
            tmp.rectTransform.sizeDelta = new Vector2(wallWidth * 0.86f, capHeight * 1.6f);

            var mr = go.GetComponent<MeshRenderer>();
            mr.shadowCastingMode = ShadowCastingMode.Off;
            mr.receiveShadows = false;
            return tmp;
        }

        /// <summary>
        /// 뒷벽에 붙어 <b>바닥에서 쌓아 올라온</b> 것들(트러스·전시벽) 중 가장 높은 곳. 글자는 그 위에 놓는다.
        ///
        /// <para>천장에 매달린 것(조명·천장 패널)은 빼야 한다. 그것까지 세면 기준이 천장 바로 아래가 되어
        /// 글자를 넣을 구간이 통째로 사라진다 — 조명은 <c>WallH - 0.03 m</c> 에 달려 있다.
        /// 아랫변이 벽 높이의 3/4 보다 위에 있으면 매달린 것으로 본다.</para>
        /// </summary>
        static float HighestNearBack(Transform interior, float faceZ, float wallTop)
        {
            float hangingFrom = wallTop * 0.72f;
            float top = 0f;
            foreach (var renderer in interior.GetComponentsInChildren<Renderer>(true))
            {
                var go = renderer.gameObject;
                if (go.name.StartsWith("Wall_") || go.name == "Ceiling" || go.name == "Floor") continue;
                if (IsOurs(go.transform)) continue;

                var bounds = renderer.bounds;
                float nearZ = interior.InverseTransformPoint(new Vector3(bounds.center.x, bounds.center.y, bounds.min.z)).z;
                if (nearZ > faceZ + NearBackDepth) continue;      // 뒷벽에서 먼 것은 글자를 가리지 않는다

                float lowY = interior.InverseTransformPoint(new Vector3(bounds.center.x, bounds.min.y, bounds.center.z)).y;
                if (lowY > hangingFrom) continue;                 // 천장에 매달린 것

                float highY = interior.InverseTransformPoint(new Vector3(bounds.center.x, bounds.max.y, bounds.center.z)).y;
                if (highY >= wallTop) continue;
                if (highY > top) top = highY;
            }
            return top;
        }

        /// <summary>우리가 만든 글자인가. 다시 지을 때 자기 자신을 기준으로 삼지 않게 한다.</summary>
        static bool IsOurs(Transform t)
        {
            for (var p = t; p != null; p = p.parent)
                if (p.name == PlateName) return true;
            return false;
        }

        // ── 색은 벽의 알베도가 아니라 **화면에 찍히는 벽** 을 기준으로 잡는다 ───────────────
        //
        // 처음에는 머티리얼의 `_BaseColor`(0.93, 0.92, 0.89)에서 배수로 뽑았는데, 그 자리의 벽은
        // 실제로 (0.72, 0.73, 0.82) 로 찍힌다 — 조명이 얹히고 천장 쪽으로 갈수록 어두워진다.
        // 그래서 면색 0.78 이 벽보다 **밝아져** 새긴 글씨가 아니라 흐릿한 흰 글씨로 보였다
        // (사용자 지적 2026-09-18, 실측 캡처에서 픽셀로 확인).
        //
        // TMP 는 라이팅을 받지 않으므로 벽의 그러데이션을 따라갈 수 없다. 밴드 전체(0.65~0.75)에서
        // 확실히 읽히도록 홈 안쪽을 벽의 절반 아래로 내리고, 방의 푸른 기를 조금 섞는다.
        static readonly Color FaceColor = new Color(0.295f, 0.315f, 0.375f, 1f);   // 홈 안쪽 — 그늘
        static readonly Color ShadowColor = new Color(0.155f, 0.165f, 0.205f, 1f); // 홈 윗변 — 가장 깊은 그늘
        static readonly Color HighlightColor = new Color(0.88f, 0.90f, 0.95f, 1f); // 홈 아랫변 — 빛을 받는 면

        void Fill(int slot, Plate plate)
        {
            string name = ResolveName(slot);
            if (name == plate.Applied) return;
            plate.Applied = name;

            bool show = !string.IsNullOrWhiteSpace(name);
            plate.Root.SetActive(show);
            if (!show) return;

            plate.Face.text = name;
            plate.Shadow.text = name;
            plate.Highlight.text = name;
        }

        /// <summary>
        /// 이 칸에 새길 이름. 바깥 간판과 <b>같은 값</b>이어야 한다 — 같은 부스가 밖과 안에서 다른
        /// 이름으로 보이면 그게 더 이상하다. 임대되지 않은 칸은 빈 문자열이다.
        /// </summary>
        static string ResolveName(int slot)
        {
            if (!BoothSlotDirectory.TryGet(slot, out var dto) || dto == null || !dto.HasBooth) return string.Empty;
            if (BoothSignPresenter.TryGetInfo(slot, out var info) && !string.IsNullOrWhiteSpace(info.Name)) return info.Name.Trim();
            if (dto.facade != null && !string.IsNullOrWhiteSpace(dto.facade.signText)) return dto.facade.signText.Trim();
            return !string.IsNullOrWhiteSpace(dto.boothName) ? dto.boothName.Trim() : string.Empty;
        }
    }
}
