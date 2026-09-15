package com.example.ssafesta.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * V37 이 넣는 이벤트 설문 문항이 기획 확정본과 글자 단위로 같다 (S15P21A604-767, GitLab #173).
 *
 * <p><b>왜 파일을 읽는가.</b> 통합 테스트에 넣을 수 없다 — {@code EventSurveyApiIntegrationTest} 의
 * {@code @BeforeEach} 가 매 테스트 전에 시드 설문의 문항과 선택지를 전부 지운다. 개수를 세는 단정이
 * 실행 순서에 좌우되지 않게 하려고 일부러 그렇게 둔 것이라 고칠 대상이 아니고, 그 클래스에
 * "문항이 6개다" 를 넣으면 순서에 따라 0개를 본다.
 *
 * <p><b>왜 유형·개수가 아니라 문구까지 보는가.</b> 실서비스 설문이라 오탈자도, Q3 과 Q4 의 선택지가
 * 뒤바뀌는 것도 결함이다. 그런 것은 개수 단정으로 잡히지 않는다. 정본은 GitLab #173 의 2026-09-11
 * 확정본이고 {@code docs/26} 에도 같은 표가 있다.
 *
 * <p>적용된 마이그레이션은 checksum 때문에 고칠 수 없으므로, 이 테스트가 무는 시점은 <b>병합 전</b>이다.
 */
class EventSurveySeedContractTest {

    private static final String MIGRATION = "/db/migration/V37__event_survey_questions.sql";

    /** 정본 6문항 — 순서·유형·필수 여부·문구. GitLab #173 (2026-09-11). */
    private static final List<Question> QUESTIONS = List.of(
            new Question("RATING", "전시 부스에서 다른 팀의 프로젝트를 둘러보는 과정이 얼마나 편리했나요? (1 = 매우 불편했다 · 5 = 매우 편리했다)", true, 1, 5),
            new Question("RATING", "SSAFESTA를 다시 이용할 의향이 있나요? (1 = 전혀 없다 · 5 = 매우 있다)", true, 1, 5),
            new Question("MULTIPLE_CHOICE", "SSAFESTA를 다시 이용한다면 어떤 기능을 가장 기대하시나요? 모두 선택해주세요.", true, null, null),
            new Question("MULTIPLE_CHOICE", "이용하면서 아쉽거나 부족하다고 느낀 점을 모두 선택해주세요.", true, null, null),
            new Question("LONG_TEXT", "추가로 개선했으면 하는 점이 있다면 자유롭게 적어주세요.", false, null, null),
            new Question("SHORT_TEXT", "이벤트 상점에 추가되었으면 하는 경품이나 물품이 있다면 적어주세요.", false, null, null));

    private static final List<String> Q3_OPTIONS = List.of(
            "다른 팀의 프로젝트·부스 둘러보기", "미니게임", "AI NPC·상담", "이벤트·경품 콘텐츠",
            "캐릭터·월드 탐색", "부스 꾸미기·프로젝트 전시", "다른 참가자와의 소통");

    private static final List<String> Q4_OPTIONS = List.of(
            "월드 이동·조작이 불편했다", "원하는 부스나 프로젝트를 찾기 어려웠다", "즐길 콘텐츠가 부족했다",
            "미니게임이 부족했다", "다른 참가자와 상호작용할 요소가 부족했다",
            "채팅이나 음성채팅 기능이 필요하다고 생각한다", "로딩·성능이 불편했다", "UI가 이해하기 어려웠다",
            "특별히 아쉬운 점이 없었다");

    private static final Pattern QUESTION_TUPLE = Pattern.compile(
            "\\('([A-Z_]+)', '(.+?)', (TRUE|FALSE), (\\d+), (NULL|\\d+), (NULL|\\d+)\\)");

    private static final Pattern OPTION_TUPLE = Pattern.compile("\\('(.+?)', (\\d+)\\)");

    /**
     * 문구가 한 글자라도 다르면 실서비스 설문이 그렇게 나간다. 순서는 응답 배열 순서와 같은 계약이라
     * ({@code SurveyQuestion#displayOrder}) 뒤바뀌면 집계가 다른 문항에 붙는다.
     */
    @Test
    void theSixConfirmedQuestionsAreSeededInOrder() throws IOException {
        List<Question> seeded = questionsOf(statementsOf(MIGRATION));

        assertEquals(QUESTIONS, seeded, "시드 문항이 기획 확정본과 다릅니다 (GitLab #173 2026-09-11)");
    }

    /**
     * {@code is_required} 를 생략하면 컬럼 기본값 FALSE 로 들어간다 — 화면에는 아무 증상이 없고 응답
     * 데이터에서만 드러난다. 그래서 필수 4 · 선택 2 를 따로 못박는다.
     */
    @Test
    void fourQuestionsAreRequiredAndTwoAreNot() throws IOException {
        List<Question> seeded = questionsOf(statementsOf(MIGRATION));

        assertEquals(List.of(true, true, true, true, false, false),
                seeded.stream().map(Question::required).toList(),
                "필수 여부가 확정본과 다릅니다: " + seeded);
    }

    /**
     * {@code ck_survey_questions_rating} 은 RATING 일 때 두 값이 있고 min 이 max 보다 작을 것을
     * 요구한다. 반대로 RATING 이 아닌 문항에 범위를 넣으면 {@code validateQuestion} 이 400 을 낸다.
     */
    @Test
    void onlyTheTwoRatingQuestionsCarryAScale() throws IOException {
        List<Question> seeded = questionsOf(statementsOf(MIGRATION));

        for (int index = 0; index < seeded.size(); index++) {
            Question question = seeded.get(index);
            boolean rating = "RATING".equals(question.type());
            assertEquals(rating, question.ratingMin() != null,
                    "RATING 만 별점 범위를 가져야 합니다 — %d 번째: %s".formatted(index, question));
            if (rating) {
                assertEquals(Integer.valueOf(1), question.ratingMin(), "별점 하한: " + question);
                assertEquals(Integer.valueOf(5), question.ratingMax(), "별점 상한: " + question);
            }
        }
    }

    /** 선택지 문구와 순서. 두 목록이 통째로 뒤바뀌어도 개수 단정은 통과한다 — 그래서 목록째 비교한다. */
    @Test
    void theChoiceOptionsMatchTheConfirmedWordingAndOrder() throws IOException {
        List<String> statements = statementsOf(MIGRATION);

        assertEquals(Q3_OPTIONS, optionsForQuestion(statements, 2), "Q3 선택지가 확정본과 다릅니다");
        assertEquals(Q4_OPTIONS, optionsForQuestion(statements, 3), "Q4 선택지가 확정본과 다릅니다");
    }

    /** 선택형이 아닌 문항에 선택지를 넣으면 {@code validateQuestion} 이 400 을 낸다. */
    @Test
    void noOptionsAreSeededForTheRatingAndTextQuestions() throws IOException {
        List<String> statements = statementsOf(MIGRATION);

        for (int order : new int[] {0, 1, 4, 5}) {
            assertTrue(optionsForQuestion(statements, order).isEmpty(),
                    "선택형이 아닌 문항 %d 에 선택지가 붙었습니다".formatted(order));
        }
    }

    /**
     * 설문 행은 IDENTITY 라 고정 id 가 없다. 숫자를 박으면 다른 환경에서 엉뚱한 설문에 문항이 붙는다 —
     * {@code survey_key} 가 유일한 손잡이다.
     */
    @Test
    void theSurveyIsFoundByItsKeyNeverByANumericId() throws IOException {
        String sql = String.join(" ", statementsOf(MIGRATION));

        assertTrue(sql.contains("survey_key = 'SSAFESTA_2026'"), "survey_key 로 찾아야 합니다: " + sql);
        assertFalse(sql.matches(".*survey_id\\s*=\\s*\\d+.*"), "설문 id 를 숫자로 박으면 안 됩니다: " + sql);
    }

    /** 선택지 표 이름은 {@code survey_options} 다. {@code survey_question_options} 는 존재하지 않는다. */
    @Test
    void theOptionsGoIntoSurveyOptions() throws IOException {
        String sql = String.join(" ", statementsOf(MIGRATION));

        assertTrue(sql.contains("INSERT INTO survey_options"), "선택지는 survey_options 에 들어간다: " + sql);
        assertFalse(sql.contains("survey_question_options"), "그런 표는 없습니다: " + sql);
    }

    private static List<Question> questionsOf(List<String> statements) {
        String questionInsert = statements.stream()
                .filter(statement -> statement.contains("INSERT INTO survey_questions"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("문항 INSERT 가 없습니다."));
        List<Question> parsed = new ArrayList<>();
        Matcher matcher = QUESTION_TUPLE.matcher(questionInsert);
        while (matcher.find()) {
            assertEquals(parsed.size(), Integer.parseInt(matcher.group(4)),
                    "display_order 는 0 부터 연속이어야 합니다: " + questionInsert);
            parsed.add(new Question(matcher.group(1), matcher.group(2), "TRUE".equals(matcher.group(3)),
                    number(matcher.group(5)), number(matcher.group(6))));
        }
        return parsed;
    }

    private static List<String> optionsForQuestion(List<String> statements, int displayOrder) {
        return statements.stream()
                .filter(statement -> statement.contains("INSERT INTO survey_options"))
                .filter(statement -> statement.contains("q.display_order = " + displayOrder))
                .findFirst()
                .map(EventSurveySeedContractTest::optionsOf)
                .orElseGet(List::of);
    }

    private static List<String> optionsOf(String statement) {
        List<String> options = new ArrayList<>();
        Matcher matcher = OPTION_TUPLE.matcher(statement);
        while (matcher.find()) {
            assertEquals(options.size(), Integer.parseInt(matcher.group(2)),
                    "선택지 display_order 는 0 부터 연속이어야 합니다: " + statement);
            options.add(matcher.group(1));
        }
        return options;
    }

    private static Integer number(String token) {
        return "NULL".equals(token) ? null : Integer.valueOf(token);
    }

    /** 주석을 걷어낸 실행 구문. 주석에는 표 이름과 문구가 설명으로 등장한다. */
    private static List<String> statementsOf(String resource) throws IOException {
        try (InputStream source = EventSurveySeedContractTest.class.getResourceAsStream(resource)) {
            if (source == null) {
                throw new IllegalStateException(resource + " 가 클래스패스에 없습니다.");
            }
            String text = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            String joined = text.lines()
                    .map(line -> line.replaceFirst("--.*$", ""))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
            return Arrays.stream(joined.split(";"))
                    .map(String::trim)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        }
    }

    private record Question(String type, String text, boolean required, Integer ratingMin, Integer ratingMax) {
    }
}
