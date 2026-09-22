using TMPro;
using UnityEngine;

namespace Festa.World
{
    /// <summary>
    /// 축제장 부스 한 칸의 <b>앞 표지판</b>과, 그 부스 위에 떠 도는 전시 카드 (GitLab #171).
    ///
    /// <para><b>지붕 위 마퀴에서 앞 입간판으로 바꿨다</b> (2026-09-10 사용자 판단). 큰 판을 지붕에
    /// 올렸더니 부스보다 판이 먼저 보이고 하늘을 다 가려서, 꾸미는 물건이 아니라 가리는 물건이 됐다.
    /// 지금은 카니발 킷 입간판(<c>PF_A_Frame_Sign</c>)을 부스 앞 왼쪽에 세우고 판 위에 이름을 적는다.</para>
    ///
    /// <para><b>카드는 그림이 있을 때만 뜬다.</b> 이름만 있는데 카드를 올리면 빈 판 12장이
    /// 하늘에 뜬다 — <see cref="ShowThumbnail"/> 만 카드를 켠다.</para>
    ///
    /// <para>값은 <see cref="BoothSignPresenter"/> 가 넣는다. 이 컴포넌트는 참조를 들고 카드 회전만 돈다.</para>
    /// </summary>
    public class BoothSign : MonoBehaviour
    {
        [Tooltip("외부 슬롯 번호 (1~12). BoothPortal.boothId 와 같은 번호다.")]
        public int boothId;

        [Tooltip("입간판 앞면 글자")]
        public TMP_Text label;

        [Tooltip("입간판 뒷면 글자. A 자 입간판은 양면이라 한쪽만 적으면 뒤에서 빈 판이 보인다.")]
        public TMP_Text labelBack;

        [Tooltip("이름이 들어갈 자리의 가로:세로 비. 몇 줄로 접을지 고를 때 쓴다 — 빌더가 실측해 넣는다.")]
        public float labelBoxAspect = 1.6f;

        [Tooltip("입간판 판 위의 전시 이미지 면 (앞·뒤). 썸네일이 오면 켜진다.")]
        public Renderer[] photoFaces;

        [Tooltip("이미지가 없을 때 그 자리에 분필로 쓴 듯 남는 번호 (앞·뒤)")]
        public TMP_Text[] photoPlaceholders;

        [Tooltip("떠 있는 전시 카드의 회전축. 이 트랜스폼을 돌린다.")]
        public Transform cardPivot;

        [Tooltip("전시 카드 렌더러. 썸네일 텍스처가 여기 들어간다.")]
        public Renderer cardRenderer;

        [Tooltip("카드 회전 속도 (도/초). 한 바퀴 12초 = 30 도/초 — 눈이 따라갈 수 있는 속도.")]
        public float spinSpeed = 30f;

        [Tooltip("카드가 위아래로 흔들리는 폭 (world unit)")]
        public float bobAmplitude = 2.2f;

        [Tooltip("카드 상하 주기 (초)")]
        public float bobPeriod = 3.4f;

        Vector3 _cardHome;
        bool _homeCaptured;

        /// <summary>표지판 양면에 이름을 적는다. 줄바꿈은 <see cref="WrapByWord"/> 가 어절 단위로 미리 넣는다.</summary>
        public void SetLabel(string text)
        {
            var wrapped = WrapByWord(text, MaxLines, labelBoxAspect);
            if (label != null) label.text = wrapped;
            if (labelBack != null) labelBack.text = wrapped;
        }

        /// <summary>표지판 한 판에 허용하는 최대 줄 수. 넘치면 글자가 작아진다(자동 축소).</summary>
        public const int MaxLines = 4;

        /// <summary>
        /// 한 줄에 담는 글자 수의 기준. <b>판이 가로보다 세로로 길다</b>(실측 7.7 × 9.7)라
        /// 줄을 짧게 끊고 여러 줄로 쌓는 편이 글자를 크게 유지한다.
        ///
        /// <para>지금은 <b>띄어쓰기 없는 한 덩어리를 쪼갤 때만</b> 쓴다 — 어절이 있는 이름의 줄 수는
        /// 판 비율을 보고 <see cref="WrapByWord"/> 가 재서 고른다.</para>
        /// </summary>
        const int CharsPerLine = 4;

        /// <summary>
        /// 공백이 없는 한 덩어리를 줄 수에 맞춰 <b>고르게</b> 쪼갠다.
        /// 앞줄만 채우고 마지막 줄에 한 글자를 남기지 않도록 줄당 글자 수를 먼저 계산한다.
        /// </summary>
        static string ChunkByChar(string word, int maxLines)
        {
            if (word.Length <= CharsPerLine) return word;

            int lines = Mathf.Clamp(Mathf.CeilToInt(word.Length / (float)CharsPerLine), 1, Mathf.Max(1, maxLines));
            int per = Mathf.CeilToInt(word.Length / (float)lines);

            var sb = new System.Text.StringBuilder();
            for (int i = 0; i < word.Length; i += per)
            {
                if (i > 0) sb.Append('\n');
                sb.Append(word, i, Mathf.Min(per, word.Length - i));
            }
            return sb.ToString();
        }

        /// <summary>
        /// 어절 단위로 줄을 나눈다. <b>TMP 에 맡기면 한글이 글자 단위로 끊긴다</b> — CJK 는
        /// 어디서든 줄을 바꿔도 되는 것으로 처리하기 때문이다. 그래서 "7조 · 스마트팜 모니터링" 이
        /// <c>7조 · 스 / 마트팜 / 모니터링</c> 으로 나왔다(2026-09-10 지적). 여기서 미리 <c>\n</c> 을
        /// 넣고 TMP 는 <c>NoWrap</c> 으로 두면 그 함정을 통째로 피한다.
        ///
        /// <para><paramref name="boxAspect"/> 는 글자가 들어갈 자리의 가로:세로 비다. 줄 수를
        /// 이 비율에 맞춰 <b>재 보고 고른다</b> — 못 박아 두면 판 폭을 절반밖에 못 쓰는 경우가 생긴다.</para>
        /// </summary>
        public static string WrapByWord(string text, int maxLines, float boxAspect = 1.6f)
        {
            if (string.IsNullOrWhiteSpace(text)) return string.Empty;
            var words = text.Trim().Split(new[] { ' ', '\t', '\n' }, System.StringSplitOptions.RemoveEmptyEntries);

            // 띄어쓰기가 없는 긴 이름("우리팀의아주긴프로젝트이름" 같은 것)은 어절이 하나라
            // 나눌 자리가 없다. 그대로 두면 한 줄에 다 넣느라 글자가 5 pt 까지 쪼그라든다 —
            // 여기서만 글자 단위로 쪼갠다. 지킬 어절 경계가 애초에 없으니 잃는 것이 없다.
            if (words.Length == 1) return ChunkByChar(words[0], maxLines);

            // 줄 수를 **재 본 뒤에 고른다.** 예전에는 "총 글자 / 4" 로 못 박았는데, 그러면
            // "AI 프로젝트 전시관" 이 세 줄로 갈려 판 폭의 절반밖에 못 썼다(3.4 / 6.6).
            // 1~maxLines 를 다 접어 보고 **글자가 가장 커지는 쪽**을 쓴다.
            //
            // 글자 크기는 줄 높이(판높이/줄수)와 글자 폭(판폭/가장 긴 줄) 중 작은 쪽에 묶인다.
            // 한글은 대체로 정사각이라 폭 = 높이로 놓고 비교하면 충분하다.
            int limit = Mathf.Max(1, maxLines);
            var packed = new string[limit + 1];
            var scores = new float[limit + 1];
            float bestScore = -1f;

            for (int lines = 1; lines <= limit; lines++)
            {
                packed[lines] = Pack(words, lines, out int longest);
                if (packed[lines] == null) continue;   // 그 줄 수로는 안 나뉜다

                float byHeight = 1f / lines;
                float byWidth = boxAspect / Mathf.Max(1, longest);
                scores[lines] = Mathf.Min(byHeight, byWidth);
                bestScore = Mathf.Max(bestScore, scores[lines]);
            }
            if (bestScore <= 0f) return string.Join(" ", words);

            // **가장 큰 글자가 곧 가장 예쁜 것은 아니다.** 순수하게 크기만 보면 "운동 자세 교정" 이
            // <c>운동 / 자세 / 교정</c> 으로 갈린다 — 세 줄로 쌓으면 글자가 1.53, 두 줄이면 1.31 이라
            // 계산상으로는 세 줄이 이긴다. 그런데 한 줄에 두 글자는 읽기 나쁘다는 지적을 받았다
            // (2026-09-10 — "한줄에 두글자밖에 안들어가네? 네글자까지는 되겠는데").
            //
            // 그래서 **최고치의 85% 안에 드는 것 중 줄 수가 가장 적은 것**을 고른다. 크기를 조금
            // 내주고 줄을 길게 가져간다 — 위 예는 두 줄(1.31 = 최고의 86%)이 되고,
            // "AI 프로젝트 전시관" 은 두 줄로 가면 54% 로 뚝 떨어지므로 세 줄이 그대로 남는다.
            const float Tolerance = 0.85f;
            for (int lines = 1; lines <= limit; lines++)
                if (packed[lines] != null && scores[lines] >= bestScore * Tolerance)
                    return packed[lines];

            return string.Join(" ", words);
        }

        /// <summary>
        /// 어절들을 정확히 <paramref name="lines"/> 줄로 고르게 나눈다. 나눌 수 없으면 null.
        /// <paramref name="longest"/> 는 가장 긴 줄의 글자 수 — 글자 크기를 가늠하는 데 쓴다.
        /// </summary>
        static string Pack(string[] words, int lines, out int longest)
        {
            longest = 0;
            if (lines > words.Length) return null;   // 어절보다 줄이 많을 수는 없다

            int total = 0;
            foreach (var w in words) total += w.Length;
            int budget = Mathf.CeilToInt((float)(total + words.Length - 1) / lines);

            var sb = new System.Text.StringBuilder();
            int lineLen = 0, used = 1;
            foreach (var w in words)
            {
                bool first = lineLen == 0;
                int add = first ? w.Length : w.Length + 1;
                // 마지막 줄에는 남은 것을 전부 밀어 넣는다 — 줄 수를 넘기지 않기 위해서다.
                if (!first && lineLen + add > budget && used < lines)
                {
                    longest = Mathf.Max(longest, lineLen);
                    sb.Append('\n');
                    lineLen = 0;
                    used++;
                    add = w.Length;
                }
                else if (!first)
                {
                    sb.Append(' ');
                }
                sb.Append(w);
                lineLen += add;
            }
            longest = Mathf.Max(longest, lineLen);
            return used == lines ? sb.ToString() : null;   // 요청한 줄 수를 다 못 채우면 버린다
        }

        /// <summary>
        /// 썸네일을 카드에 입히고 카드를 띄운다. 바탕색을 흰색으로 올리는 것이 핵심이다 —
        /// 기본 바탕은 어둡게 깔려 있어서 그대로 두면 그림이 곱해져 시커멓게 나온다.
        /// <b>이 메서드만 카드를 켠다</b> — 그림이 없으면 카드는 없다.
        /// </summary>
        public void ShowThumbnail(Texture texture)
        {
            if (texture == null) return;

            // ① 떠 있는 카드
            if (cardRenderer != null && cardPivot != null)
            {
                var mat = cardRenderer.material;   // 인스턴스 — sharedMaterial 을 건드리면 12칸이 같은 그림이 된다
                mat.SetColor("_BaseColor", Color.white);
                mat.SetTexture("_BaseMap", texture);
                mat.mainTexture = texture;
                cardPivot.gameObject.SetActive(true);
            }

            // ② 입간판 판 위 (사용자가 올린 레퍼런스처럼 이미지 위 · 이름 아래)
            if (photoFaces != null)
            {
                foreach (var r in photoFaces)
                {
                    if (r == null) continue;
                    var m = r.material;   // 면마다 인스턴스가 필요하다 — 앞뒤가 같은 머티리얼을 공유하면 안 된다
                    m.SetTexture("_BaseMap", texture);
                    m.mainTexture = texture;
                    // 밤에도 그림이 보이게 살짝 자체 발광시킨다. 발광 맵을 같이 물려야 면 전체가
                    // 균일하게 뜨지 않고 그림 모양대로 밝아진다.
                    m.EnableKeyword("_EMISSION");
                    m.SetTexture("_EmissionMap", texture);
                    m.SetColor("_EmissionColor", Color.white * 0.35f);
                    r.gameObject.SetActive(true);
            }
        }

        /// <summary>전시가 바뀌거나 사라질 때 이전 다운로드 Texture 참조를 화면에서 끊는다.</summary>
        public void ClearThumbnail()
        {
            if (cardRenderer != null && cardPivot != null)
            {
                var mat = cardRenderer.material;
                mat.SetTexture("_BaseMap", null);
                mat.mainTexture = null;
                cardPivot.gameObject.SetActive(false);
            }

            if (photoFaces != null)
                foreach (var r in photoFaces)
                {
                    if (r == null) continue;
                    var mat = r.material;
                    mat.SetTexture("_BaseMap", null);
                    mat.SetTexture("_EmissionMap", null);
                    r.gameObject.SetActive(false);
                }
        }
            if (photoPlaceholders != null)
                foreach (var t in photoPlaceholders)
                    if (t != null) t.gameObject.SetActive(false);
        }

        void Awake()
        {
            if (cardPivot != null) { _cardHome = cardPivot.localPosition; _homeCaptured = true; }
        }

        void Update()
        {
            if (cardPivot == null || !_homeCaptured || !cardPivot.gameObject.activeSelf) return;

            cardPivot.Rotate(0f, spinSpeed * Time.deltaTime, 0f, Space.Self);

            // 위상을 boothId 로 흩는다 — 여러 장이 한 몸처럼 같이 오르내리면 기계처럼 보인다.
            float phase = boothId * 0.7f;
            float y = Mathf.Sin((Time.time / Mathf.Max(0.1f, bobPeriod) + phase) * Mathf.PI * 2f) * bobAmplitude;
            cardPivot.localPosition = _cardHome + new Vector3(0f, y, 0f);
        }
    }
}
