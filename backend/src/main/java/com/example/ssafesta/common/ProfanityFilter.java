package com.example.ssafesta.common;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 금칙어 한 벌 (S15P21A604-792).
 *
 * <p><b>목록이 하나다.</b> 닉네임({@code NicknamePolicy})과 월드 채팅이 같은 것을 쓴다. 채팅용
 * 목록을 따로 두면 둘이 갈리고, 어느 쪽이 정본인지 알 수 없게 된다. 정책 정본은
 * {@code specs/001-auth-user/contracts/nickname-policy.md} 다.
 *
 * <p><b>{@code static} 이다.</b> 상태가 없다 — {@code @Component} 로 만들면 이것을 쓰는 서비스마다
 * 생성자가 하나씩 길어지고 그 생성자를 직접 부르는 기존 테스트가 함께 깨진다.
 *
 * <p>운영자·시스템 사칭 예약어({@code 관리자}·{@code admin})는 여기 없다. 닉네임 사칭을 막는
 * 것이라, 채팅에 걸면 "관리자님 계신가요" 가 막힌다.
 */
public final class ProfanityFilter {

    private static final Map<Character, Character> LEET = Map.of(
            '0', 'o', '1', 'i', '3', 'e', '4', 'a', '5', 's', '7', 't', '8', '팔');

    private static final List<String> BLOCKED_TERMS = List.of(
            "씨발", "시발", "씨i발", "시i발", "씹발", "씨팔", "시팔", "씨벌", "시벌", "씹", "씹새끼", "개새끼", "개새",
            "개년", "개놈", "개같", "개좆", "좆", "좃", "존나", "졸라", "병신", "븅신", "빙신",
            "등신", "미친놈", "미친년", "또라이", "지랄", "염병", "느금마", "꺼져", "닥쳐", "뒤져", "죽어",
            "ㅅㅂ", "ㅆㅂ", "ㅂㅅ", "ㅄ", "ㅈㄹ", "ㅈㄴ", "ㄱㅅㄲ", "ㄲㅈ", "ㄷㅊ", "ㄴㄱㅁ", "ㄴㅇㅁ",
            "섹스", "야동", "야설", "야짤", "포르노", "자위", "보지", "자지", "꼬추", "고추", "성기", "강간", "성폭행", "몰카", "누드",
            "한남", "한녀", "김치녀", "김치남", "맘충", "급식충", "틀딱", "잼민", "메갈", "창녀", "창남", "호모", "정박아",
            "마약", "필로폰", "대마", "코카인", "헤로인", "펜타닐", "엑스터시",
            "fuck", "fucking", "fucker", "fck", "fuk", "fucc", "fuxk", "shit", "bullshit", "bitch", "bastard", "asshole", "cunt",
            "dick", "cock", "pussy", "whore", "slut", "retard", "stfu", "gtfo", "sex", "sexy", "porn", "xxx", "hentai", "nude", "nudes",
            "naked", "penis", "vagina", "cum", "semen", "anal", "blowjob", "handjob", "rape", "rapist", "molest");

    /**
     * 금칙어를 품고 있지만 욕이 아닌 말 (2026-09-15 확정 목록).
     *
     * <p>부분 문자열 대조라 {@code 고추장} 이 {@code 고추} 에, {@code analysis} 가 {@code anal} 에
     * 걸린다 — 닉네임(2~30자)에서는 드물었지만 100자 문장에서는 흔하다. 업계가 쓰는 해법이
     * 이 예외 목록이고, 교과서 사례가 영국 지명 {@code Scunthorpe} 다.
     *
     * <p><b>긴 것부터 지운다.</b> {@code 고추장찌개} 가 {@code 고추장} 보다 먼저 걸려야 한다.
     *
     * <p><b>한계</b>: {@code -아/어보지} 는 어미라 끝없이 만들어진다({@code 뛰어보지}·{@code 물러보지}
     * …). 대표형만 넣었고 나머지는 오탐이 보고될 때 한 줄씩 늘린다. 목록을 코드 밖으로 빼는 것은
     * 운영 UI 가 생길 때다.
     */
    private static final List<String> ALLOWED_TERMS = Stream.of(
            // 보지 — 어미 -아/어보지
            "해보지", "가보지", "와보지", "맛보지", "먹어보지", "들어보지", "알아보지", "써보지",
            "물어보지", "찾아보지", "비교해보지", "확인해보지", "생각해보지", "해보지도", "해보지만",
            // 고추
            "고추장", "고춧가루", "고추가루", "청양고추", "풋고추", "꽈리고추", "홍고추", "고추냉이",
            "고추기름", "고추장찌개",
            // 꺼져
            "불꺼져", "불이꺼져", "꺼져있다", "꺼져있는", "꺼져있음", "화면꺼져", "전원꺼져",
            // 뒤져
            "뒤져보다", "뒤져봐", "뒤져보니", "뒤져보면", "샅샅이뒤져",
            // 씹
            "씹다", "씹어", "씹는", "씹었다", "씹으면", "씹히다", "씹히는", "씹는맛", "씹을거리",
            // 성기 · 대마
            "성기훈", "성기능", "성기능장애", "대마도", "대마도여행", "대마도항", "대마도여객선",
            // 년 · 놈 · 새끼
            "학년", "내년", "작년", "금년", "매년", "내후년", "청년", "중년", "노년", "송년", "신년", "연년생",
            "게놈", "게놈분석", "게놈지도",
            "새끼손가락", "새끼발가락", "새끼고양이", "새끼강아지", "새끼돼지", "새끼줄",
            // sex · ass · anal · cum
            "sussex", "essex", "middlesex", "sexagesimal",
            "class", "classic", "classify", "classification", "pass", "password", "bypass", "compass",
            "grass", "glass", "mass", "bass", "asset", "assign", "assist", "assistant", "assessment", "assume",
            "analysis", "analyst", "analytic", "analytical", "analyze", "analogue", "analog", "analogy", "canal",
            "cucumber", "circumstance", "accumulate", "accumulation", "cumulative", "document", "undocumented",
            // rape · dick · cock · tit · hoe
            "grape", "grapefruit", "drape", "scrape", "scraper", "therapeutic",
            "dickens", "dickinson", "dicksonian",
            "cocktail", "peacock", "woodcock", "cocker", "cockerel", "hancock", "cockatoo",
            "title", "subtitle", "entity", "identity", "quantity", "institute", "institution", "constitute", "attitude",
            "shoe", "shoes", "shoelace", "horseshoe",
            // 고유명사 · 학술어 · 그 밖
            "capricorn", "penistone", "shitake", "shiitake", "fagaceae", "fagales", "dyke", "dike", "raccoon",
            "night", "knight", "nightly", "enigma", "benign", "significant", "gayageum",
            "parse", "parser", "sparse", "parsley",
            "hello", "shell", "nutshell", "wheelhouse", "adamant")
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    /**
     * 예외를 지운 자리에 남기는 것.
     *
     * <p>빈 문자열로 지우면 앞뒤가 붙어 <b>없던 금칙어가 생긴다</b> — {@code 씨class발} 에서
     * {@code class} 를 지우면 {@code 씨발} 이 된다. 금칙어에는 공백이 없으므로 공백 하나가 벽이 된다.
     *
     * <p><b>대가는 알고 있다.</b> 예외 단어를 욕 사이에 끼우는 우회가 열린다({@code 씨class발} 은
     * 통과한다). 아무 죄 없는 문장을 우연히 막는 쪽보다 낫다고 본다 — 그쪽은 매일 일어나고
     * 이쪽은 일부러 해야 한다.
     */
    private static final String GAP = " ";

    private ProfanityFilter() {
    }

    /**
     * 정규화 결과에 금칙어가 남는가.
     *
     * <p>순서가 중요하다 — 정규화 → 예외 제거 → 대조다. 예외를 먼저 지우면 {@code F_U_C_K} 같은
     * 우회 표기를 예외 목록이 알아보지 못한다.
     */
    public static boolean contains(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String scanned = normalize(text);
        for (String allowed : ALLOWED_TERMS) {
            scanned = scanned.replace(allowed, GAP);
        }
        String remaining = scanned;
        return BLOCKED_TERMS.stream().anyMatch(remaining::contains);
    }

    /**
     * 대조에 쓰는 형태로 다듬는다 — NFKC · 소문자 · 공백과 기호 제거 · 숫자 치환.
     *
     * <p>{@code 씨---1---발} 과 {@code F_U_C_K} 가 여기서 각각 {@code 씨i발}·{@code fuck} 이 된다.
     * 공백까지 지우는 것이 {@code 씨 발} 을 잡는 대신 문장에서는 오탐을 만든다 — 그래서
     * {@link #ALLOWED_TERMS} 가 있다.
     */
    public static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        for (char character : normalized.toCharArray()) {
            if (Character.isLetterOrDigit(character) || Character.UnicodeScript.of(character) == Character.UnicodeScript.HANGUL) {
                result.append(LEET.getOrDefault(character, character));
            }
        }
        return result.toString();
    }
}
