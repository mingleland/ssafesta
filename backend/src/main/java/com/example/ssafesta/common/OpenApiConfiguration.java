package com.example.ssafesta.common;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger UI 의 첫 화면이 이 클래스다.
 *
 * <p>여기에는 <b>모든 endpoint 에 공통으로 적용되는 것</b>만 적는다 — 인증 세 종류, 오류 봉투의 모양,
 * 시간·금액 표기. endpoint 별 설명은 각 컨트롤러의 {@code @Operation} 이 담당한다. 같은 내용을 두 곳에
 * 적으면 한쪽이 낡는다.
 *
 * <p>계약 정본은 {@code docs/08_Backend_API_명세서.md} 이고, Game Studio 는
 * {@code specs/019-game-studio/contracts/game-api.md} 다. 이 주석과 정본이 어긋나면 정본이 맞다.
 */
@Configuration
public class OpenApiConfiguration {

    private static final String DESCRIPTION = """
            SSAFY FESTA 백엔드 API. 모든 경로는 `/api/v1` 로 시작하고, 내부 서비스 전용 경로만 `/internal` 로 시작한다.

            ## 인증 — 토큰이 세 종류다

            | 토큰 | 발급 | 쓰는 곳 |
            |---|---|---|
            | **회원 Access Token** | `POST /api/v1/auth/oauth/complete` 또는 `POST /api/v1/auth/refresh` | 부스·지갑·게임·AI 등 소유권이 있는 모든 API |
            | **게스트 Access Token** | `POST /api/v1/auth/guest` | 공개 관람 전용. 회원 API 는 `403` 으로 거부된다 |
            | **내부 서비스 토큰** | 배포 시 주입되는 고정 값 | `/internal/**` 전용. 사용자 토큰으로는 접근할 수 없다 |

            앞의 두 토큰은 `Authorization: Bearer <token>` 헤더로 보낸다. 아래 자물쇠가 잠긴 endpoint 는
            토큰이 **필수**라는 뜻이고, 열린 endpoint 는 토큰 없이 호출된다 (방문자가 읽는 공개 정보).

            **`/internal/**` 은 이 문서에 나오지 않는다.** 서버 간 전용이고 이 문서는 누구나 열 수 있어
            일부러 숨겼다 — 그 계약은 `specs/008-ai-conversation-rag/contracts/` 가 소유한다.

            게스트와 회원을 가르는 것은 토큰의 `role` 클레임이다. 게스트에게는 지갑도 코인도 없고,
            부스를 임대하거나 게임을 만들 수 없다.

            ## 오류 응답은 한 가지 모양이다

            ```json
            {
              "code": "BOOTH_SLOT_ALREADY_LEASED",
              "message": "이미 임대 중인 부스입니다.",
              "requestId": "req_1a2b3c4d",
              "errors": [],
              "warnings": []
            }
            ```

            - `code` 로 분기하고 `message` 는 사람에게 보여준다. **상태 코드만으로 분기하면 안 된다** —
              같은 `409` 에 서로 다른 사유가 여러 개 있다 (슬롯 임대의 `409` 세 종류가 그렇다).
            - `requestId` 는 응답 헤더 `X-Request-Id` 와 서버 로그의 값과 같다. 화면에서 본 id 하나로 로그를 찾을 수 있다.
            - `errors`·`warnings` 는 **항상 있다.** 보고할 것이 없으면 빈 배열이다. `errors` 가 비어 있지 않으면
              요청은 거부됐고, `warnings` 는 진행을 막지 않는다 — 저장·공개가 성공한 응답에도 붙는다.
            - 배치·게임 검증처럼 규칙이 여러 개인 API 는 `errors[].rule` 에 위반한 규칙 이름이, 필드 단위 위반이면
              `errors[].field` 에 요청 필드 이름이 들어간다.

            ## 표기 규칙

            - 시간은 모두 ISO-8601 UTC (`2026-09-02T05:00:00Z`). 일일 코인 지급 같은 "하루" 판정만 KST 기준이다.
            - 금액은 코인 정수다. 소수점이 없고 음수는 차감을 뜻한다.
            - 목록 조회의 페이지는 `page`(0부터)·`size`(1~100)다.
            """;

    private static final String MEMBER_TOKEN_DESCRIPTION = """
            `Authorization: Bearer <access-token>`

            회원 또는 게스트 Access Token. `POST /api/v1/auth/guest` 로 게스트 토큰을,
            OAuth 완료 또는 `POST /api/v1/auth/refresh` 로 회원 토큰을 받는다.
            회원 전용 API 에 게스트 토큰을 보내면 `403` 이다.
            """;

    @Bean
    OpenAPI ssafestaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SSAFESTA API")
                        .version("v1")
                        .description(DESCRIPTION))
                .servers(List.of(new Server().url("http://localhost:8080").description("로컬 개발 서버")))
                .tags(tags())
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description(MEMBER_TOKEN_DESCRIPTION)));
    }

    /**
     * 4xx·5xx 응답에 오류 봉투 스키마를 자동으로 붙인다.
     *
     * <p>모든 오류는 {@link ApiErrorResponse} 한 가지 모양이므로, endpoint 마다
     * {@code content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))} 를 반복해
     * 적지 않는다. 컨트롤러의 {@code @ApiResponse} 는 <b>상태 코드와 사유만</b> 적고 본문 스키마는 여기서
     * 한 번 채운다 — 반복이 없으면 새 endpoint 가 이 규칙에서 빠질 수도 없다.
     *
     * <p>4xx·5xx 의 본문은 <b>덮어쓴다.</b> springdoc 은 설명만 있는 응답에 그 핸들러의 <i>성공</i> 반환형을
     * 채워 넣는데, 그것은 오류 응답에 대해 사실이 아니다 — 실제로 오는 것은 언제나 이 봉투다.
     */
    @Bean
    OpenApiCustomizer errorEnvelopeCustomizer() {
        return openApi -> {
            registerErrorSchemas(openApi);
            if (openApi.getPaths() == null) {
                return;
            }
            Content envelope = new Content().addMediaType("application/json", new MediaType()
                    .schema(new Schema<>().$ref("#/components/schemas/ApiErrorResponse")));
            openApi.getPaths().values().stream()
                    .flatMap(path -> path.readOperations().stream())
                    .filter(operation -> operation.getResponses() != null)
                    .forEach(operation -> {
                        addUnauthorized(operation);
                        operation.getResponses().forEach((status, response) -> {
                            if (status.startsWith("4") || status.startsWith("5")) {
                                response.setContent(envelope);
                            }
                        });
                    });
        };
    }

    /**
     * 토큰이 필요한 endpoint 에 {@code 401} 을 자동으로 붙인다.
     *
     * <p>"토큰이 없으면 401" 은 자물쇠가 걸린 모든 endpoint 에서 같으므로 컨트롤러마다 적지 않는다.
     * 반대로 {@code 403}·{@code 404} 처럼 <b>사유가 endpoint 마다 다른 것</b>은 자동화하지 않는다 —
     * 그것이 문서의 실제 내용이다.
     *
     * <p>이미 {@code 401} 을 적어 둔 endpoint 는 건드리지 않는다. 사유가 특별한 경우가 있다 —
     * 예를 들어 {@code /auth/refresh} 의 401 은 "비로그인 방문자의 정상 응답"이다.
     */
    private void addUnauthorized(Operation operation) {
        boolean secured = operation.getSecurity() != null && !operation.getSecurity().isEmpty();
        if (!secured || operation.getResponses().containsKey("401")) {
            return;
        }
        operation.getResponses().addApiResponse("401", new ApiResponse()
                .description("Access Token 이 없거나 유효하지 않다. 만료라면 `POST /api/v1/auth/refresh` 로 갱신한다"));
    }

    /**
     * {@code $ref} 가 가리킬 스키마를 직접 등록한다 — springdoc 은 주석에서 참조된 타입만 자동 등록하므로,
     * 위 customizer 가 붙이는 참조는 이 등록 없이는 빈 이름을 가리킨다.
     */
    private void registerErrorSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        ModelConverters.getInstance().readAll(ApiErrorResponse.class)
                .forEach(openApi.getComponents()::addSchemas);
    }

    /**
     * 태그 순서가 Swagger UI 의 화면 순서다. 로그인에서 시작해 부스를 얻고 그 안을 채우는 실제 사용 순서로
     * 두었다 — 알파벳 순으로 두면 처음 보는 사람이 어디서 시작해야 하는지 알 수 없다.
     */
    private List<Tag> tags() {
        return List.of(
                tag("Auth", "게스트·소셜 로그인, Access Token 재발급, 로그아웃. 다른 모든 API 의 출발점이다"),
                tag("User", "내 계정 조회·닉네임 변경·아바타 외형 저장·탈퇴"),
                tag("Wallet", "코인 잔액과 거래 내역 조회. 충전·차감 API 는 없다 — 차감은 임대 같은 기능의 서버 로직에서만 일어난다"),
                tag("Booth Slot", "월드의 부스 자리 목록과 임대. 1인 1부스이고 기간은 24시간 고정이다"),
                tag("Booth", "방문자가 보는 부스 정보와 내 부스 상태"),
                tag("Booth Layout", "부스 내부 배치의 작업본(Draft) 저장과 공개(Publish). 방문자에게 보이는 것은 공개본뿐이다"),
                tag("Booth Facade", "부스 외관 — 간판 문구·색·로고"),
                tag("Booth Homepage", "부스 노트북에 띄우는 외부 홈페이지 주소"),
                tag("Project", "부스에 전시하는 프로젝트 카드의 작성·공개"),
                tag("AI Agent", "부스당 1명인 AI 직원의 인격 설정(이름·역할·말투·지시문)"),
                tag("AI Document", "AI 직원이 답변 근거로 쓰는 문서의 업로드. presigned URL 로 저장소에 직접 올린다"),
                tag("Game Studio", "브라우저 2D 게임의 제작·게시. 작업본과 게시본이 분리되어 있다"),
                tag("Game Asset", "게임에 쓰는 이미지의 업로드·전달. 바이트는 저장소로 직접 올라가고 내려받기만 서버가 중계한다"),
                tag("Inventory", "아바타 파츠 상점과 구매"),
                tag("World Session", "Unity 월드 접속 주소와 1회용 입장 토큰 발급"));
    }

    private Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }
}
