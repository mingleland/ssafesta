using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 입장 게이트의 층수 표시기 (spec 002 FR-013 진행 표시).
    ///
    /// **폰트를 쓰지 않는다.** 실제 엘리베이터처럼 7세그먼트를 큐브로 조립한다.
    /// WebGL 에서 폰트 에셋·TMP 아틀라스가 얽히는 위험을 아예 없애고
    /// (프로젝트 규칙상 텍스트 UI 는 React 몫이다), 표시기는 순수 지오메트리로 남긴다.
    ///
    /// 머티리얼은 `Resources/GateSegment.mat` 에셋을 읽는다 — 런타임 Shader.Find 는
    /// 빌드 셰이더 스트리핑에 당한다 (T-213). 에셋 참조가 있어야 셰이더가 빌드에 포함된다.
    /// </summary>
    public class GateFloorIndicator : MonoBehaviour
    {
        const string MaterialResourcePath = "GateSegment";

        // 비트 0=A(위) 1=B(우상) 2=C(우하) 3=D(아래) 4=E(좌하) 5=F(좌상) 6=G(가운데)
        static readonly int[] DigitPatterns =
        {
            0x3F, // 0
            0x06, // 1
            0x5B, // 2
            0x4F, // 3
            0x66, // 4
            0x6D, // 5
            0x7D, // 6
            0x07, // 7
            0x7F, // 8
            0x6F, // 9
        };

        Transform[] _digitRoots;      // [0]=십의 자리 [1]=일의 자리
        GameObject[][] _segments;     // [자릿수][세그먼트]
        float _spacing;
        float _digitWidth;
        int _shown = -1;

        /// <summary>
        /// 두 자리 표시기를 만든다. 숫자면은 월드 +x 를 바라본다 (게이트 카메라가 -x 를 본다).
        /// </summary>
        public static GateFloorIndicator Build(Transform parent, Vector3 center, float digitHeight)
        {
            var material = Resources.Load<Material>(MaterialResourcePath);
            if (material == null)
            {
                // 조용히 넘어가면 진행 표시가 사라진 이유를 아무도 모른다.
                Debug.LogError($"[GateFloorIndicator] Resources/{MaterialResourcePath}.mat 을 찾지 못해 층수 표시를 건너뛴다");
                return null;
            }

            var go = new GameObject("FloorIndicator");
            go.transform.SetParent(parent, false);
            go.transform.SetPositionAndRotation(center, Quaternion.Euler(0f, 90f, 0f));

            var indicator = go.AddComponent<GateFloorIndicator>();
            indicator.BuildSegments(material, digitHeight);
            return indicator;
        }

        void BuildSegments(Material material, float h)
        {
            float w = h * 0.55f;    // 자릿수 폭
            float t = h * 0.14f;    // 획 두께
            float d = h * 0.10f;    // 두께(깊이)
            _spacing = w * 1.5f;
            _digitWidth = w;

            // 표시기는 월드 +x 를 보도록 Y 90도 회전해 붙는다. 그 결과 **로컬 +X 가 화면 왼쪽**이다.
            // 자릿수 배치와 자리 안 세그먼트가 같은 규약을 따라야 숫자가 뒤집히지 않는다
            // (처음에 자릿수만 맞추고 세그먼트를 안 맞춰 전 숫자가 거울상으로 나왔다).
            //
            // 자릿수마다 컨테이너를 두는 이유: 한 자리 숫자를 **가운데로 옮기기 위해서**다.
            // 십의 자리를 비워 두면 숫자가 한쪽으로 쏠려 보인다.
            _digitRoots = new Transform[2];
            _segments = new GameObject[2][];

            for (int digit = 0; digit < 2; digit++)
            {
                var root = new GameObject(digit == 0 ? "digit_tens" : "digit_ones").transform;
                root.SetParent(transform, false);
                _digitRoots[digit] = root;

                _segments[digit] = new[]
                {
                    MakeSegment(root, material, new Vector3(0f, h * 0.5f, 0f), new Vector3(w, t, d)),           // A
                    MakeSegment(root, material, new Vector3(-w * 0.5f, h * 0.25f, 0f), new Vector3(t, h * 0.5f, d)),  // B
                    MakeSegment(root, material, new Vector3(-w * 0.5f, -h * 0.25f, 0f), new Vector3(t, h * 0.5f, d)), // C
                    MakeSegment(root, material, new Vector3(0f, -h * 0.5f, 0f), new Vector3(w, t, d)),          // D
                    MakeSegment(root, material, new Vector3(w * 0.5f, -h * 0.25f, 0f), new Vector3(t, h * 0.5f, d)),  // E
                    MakeSegment(root, material, new Vector3(w * 0.5f, h * 0.25f, 0f), new Vector3(t, h * 0.5f, d)),   // F
                    MakeSegment(root, material, new Vector3(0f, 0f, 0f), new Vector3(w, t, d)),                 // G
                };
            }
        }

        GameObject MakeSegment(Transform parent, Material material, Vector3 localPos, Vector3 localScale)
        {
            var seg = GameObject.CreatePrimitive(PrimitiveType.Cube);
            seg.name = "seg";
            // 표시기는 장식이다. 콜라이더를 두면 플레이어·카메라 충돌에 걸린다.
            Destroy(seg.GetComponent<Collider>());
            seg.transform.SetParent(parent, false);
            seg.transform.localPosition = localPos;
            seg.transform.localScale = localScale;
            seg.GetComponent<MeshRenderer>().sharedMaterial = material;
            seg.SetActive(false);
            return seg;
        }

        /// <summary>0~99 를 표시한다. **한 자리면 가운데로 모은다** (십의 자리를 비운 채 두지 않는다).</summary>
        public void SetNumber(int value)
        {
            value = Mathf.Clamp(value, 0, 99);
            if (value == _shown) return;
            _shown = value;

            int tens = value / 10;
            int ones = value % 10;
            bool twoDigits = tens > 0;

            // 로컬 +X 가 화면 왼쪽이므로 십의 자리를 +쪽에 둔다.
            float tensX = twoDigits ? _spacing * 0.5f : 0f;
            float onesX = twoDigits ? -_spacing * 0.5f : 0f;

            // 숫자 1 은 오른쪽 세로획(B·C)만 켜져 칸 오른쪽에 쏠린다. 칸 중앙으로 당겨야
            // 부호는 측정으로 확정했다 — - 로 넣었더니 어긋남이 1.10 → 2.20 으로 커졌다.
            if (twoDigits && tens == 1) tensX += _digitWidth * 0.5f;
            if (ones == 1) onesX += _digitWidth * 0.5f;

            _digitRoots[0].localPosition = new Vector3(tensX, 0f, 0f);
            _digitRoots[1].localPosition = new Vector3(onesX, 0f, 0f);

            ApplyDigit(0, twoDigits ? DigitPatterns[tens] : 0);
            ApplyDigit(1, DigitPatterns[ones]);
        }

        void ApplyDigit(int digit, int pattern)
        {
            var segs = _segments[digit];
            for (int i = 0; i < segs.Length; i++)
                segs[i].SetActive((pattern & (1 << i)) != 0);
        }
    }
}
