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
            var wrapped = WrapByWord(text, MaxLines);
            if (label != null) label.text = wrapped;
            if (labelBack != null) labelBack.text = wrapped;
        }

        /// <summary>표지판 한 판에 허용하는 최대 줄 수. 넘치면 글자가 작아진다(자동 축소).</summary>
        public const int MaxLines = 4;

        /// <summary>
        /// 한 줄에 담는 글자 수의 기준. <b>판이 가로보다 세로로 길다</b>(실측 7.7 × 9.7)라
        /// 줄을 짧게 끊고 여러 줄로 쌓는 편이 글자를 크게 유지한다.
        ///
        /// <para>처음엔 8 이었는데 "AI 프로젝트 전시관" 이 <c>AI</c> / <c>프로젝트 전시관</c> 로 갈려
        /// 둘째 줄이 판보다 넓어졌고, 자동 축소 바닥에 걸려 그대로 삐져나가 프레임 뒤로 잘렸다
        /// (2026-09-10). 4 로 낮추면 <c>AI</c> / <c>프로젝트</c> / <c>전시관</c> 이 된다.</para>
        /// </summary>
        const int CharsPerLine = 4;

        /// <summary>
        /// 어절 단위로 줄을 나눈다. <b>TMP 에 맡기면 한글이 글자 단위로 끊긴다</b> — CJK 는
        /// 어디서든 줄을 바꿔도 되는 것으로 처리하기 때문이다. 그래서 "7조 · 스마트팜 모니터링" 이
        /// <c>7조 · 스 / 마트팜 / 모니터링</c> 으로 나왔다(2026-09-10 지적). 여기서 미리 <c>\n</c> 을
        /// 넣고 TMP 는 <c>NoWrap</c> 으로 두면 그 함정을 통째로 피한다.
        ///
        /// <para>줄 수는 길이에서 정하고, 각 줄의 길이가 <b>고르게</b> 되도록 채운다 — 첫 줄만 꽉 차고
        /// 마지막 줄에 한 글자만 남는 모양이 제일 못생겼다. 한 어절이 통째로 길면(공백 없는 긴 이름)
        /// 자르지 않고 그대로 둔다 — 자동 축소가 글자 크기를 줄여 맞춘다.</para>
        /// </summary>
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

        public static string WrapByWord(string text, int maxLines)
        {
            if (string.IsNullOrWhiteSpace(text)) return string.Empty;
            var words = text.Trim().Split(new[] { ' ', '\t', '\n' }, System.StringSplitOptions.RemoveEmptyEntries);

            // 띄어쓰기가 없는 긴 이름("우리팀의아주긴프로젝트이름" 같은 것)은 어절이 하나라
            // 나눌 자리가 없다. 그대로 두면 한 줄에 다 넣느라 글자가 5 pt 까지 쪼그라든다 —
            // 여기서만 글자 단위로 쪼갠다. 지킬 어절 경계가 애초에 없으니 잃는 것이 없다.
            if (words.Length == 1) return ChunkByChar(words[0], maxLines);

            int total = 0;
            foreach (var w in words) total += w.Length;

            int lines = Mathf.Clamp(Mathf.CeilToInt(total / (float)CharsPerLine), 1, Mathf.Max(1, maxLines));
            if (lines == 1) return string.Join(" ", words);

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
            return sb.ToString();
        }

        /// <summary>
        /// 썸네일을 카드에 입히고 카드를 띄운다. 바탕색을 흰색으로 올리는 것이 핵심이다 —
        /// 기본 바탕은 어둡게 깔려 있어서 그대로 두면 그림이 곱해져 시커멓게 나온다.
        /// <b>이 메서드만 카드를 켠다</b> — 그림이 없으면 카드는 없다.
        /// </summary>
        public void ShowThumbnail(Texture texture)
        {
            if (cardRenderer == null || texture == null || cardPivot == null) return;

            var mat = cardRenderer.material;   // 인스턴스 — sharedMaterial 을 건드리면 12칸이 같은 그림이 된다
            mat.SetColor("_BaseColor", Color.white);
            mat.SetTexture("_BaseMap", texture);
            mat.mainTexture = texture;

            cardPivot.gameObject.SetActive(true);
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
