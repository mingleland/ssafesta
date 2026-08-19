# 닉네임 금칙어·예약어 정책

**적용 범위**: 최초 가입과 닉네임 변경. 아래 항목은 모두 `BLOCK`이며, 원문 목록은 운영 설정의 단일 기준으로 관리한다.

## 검사 규칙

1. Unicode 정규화와 소문자 변환 후 공백·기호를 제거한다.
2. 반복 문자 축약 및 숫자·기호 치환을 적용한다. 예: `씨---1---발` → `씨발`, `F_U_C_K` → `fuck`.
3. 정규화 결과와 원문 모두를 금칙어·예약어·연락처/URL 패턴으로 검사한다.
4. 금칙어 종류나 일치 단어를 사용자에게 노출하지 않고, “사용할 수 없는 닉네임”과 수정 안내만 표시한다.

## HARD_BLOCK 금칙어

| 범주 | 차단 단어·표현 |
|---|---|
| 한국어 욕설·우회 | 씨발, 시발, 씹발, 씨팔, 시팔, 씨벌, 시벌, 씹, 씹새, 씹새끼, 씹년, 씹놈, 씹창, 개새끼, 개새, 개색기, 개색끼, 개쉐끼, 개쉑, 개년, 개놈, 개자식, 개같, 개같은, 개좆, 개씹, 좆, 좃, 좆같, 좆같은, 좆까, 좆나, 좆도, 좆밥, 좆망, 좆병신, 좆됐, 좆됨, 존나, 졸라, 병신, 븅신, 빙신, 등신, 머저리, 미친놈, 미친년, 미친새끼, 또라이, 지랄, 지럴, 지랄병, 염병, 엠병, 니미, 니미럴, 니미랄, 애미, 애비, 느금마, 느금, 느개비, 꺼져, 닥쳐, 닥치, 뒤져, 뒤질, 죽어, ㅅㅂ, ㅆㅂ, ㅆㅃ, ㅂㅅ, ㅄ, ㅈㄹ, ㅈㄴ, ㅈ같, ㅈ까, ㅈ밥, ㄱㅅㄲ, ㄱㅅㄱ, ㄱㅆㄲ, ㄲㅈ, ㄷㅊ, ㄴㄱㅁ, ㄴㅇㅁ |
| 성적·음란 | 섹스, 쎅스, 쌕스, sex, 야스, 야동, 야설, 야짤, 음란, porn, pornhub, 포르노, 자위, 딸딸이, 딸치, 딸잡, 오나니, 보지, 봊, 자지, 꼬추, 고추, 성기, 음경, 질내사정, 질싸, 안싸, 입싸, 얼싸, 사정, 정액, 애액, 후장, 항문섹스, 강간, 윤간, 성폭행, 성추행, 몰카, 리벤지포르노, 노출, 누드, nude, nudes, hentai, 야애니, sexy, xxx, naked, boob, tits, penis, vagina, pussy, dick, cock, cum, semen, anal, blowjob, handjob, rimjob, orgasm, masturbate, rape, rapist, molest |
| 혐오·차별 | 한남, 한녀, 김치녀, 김치남, 된장녀, 된장남, 맘충, 급식충, 틀딱, 틀딱충, 틀니, 잼민이, 잼민, 페미충, 메갈, 메갈년, 쿵쾅, 쿵쾅이, 꼴페미, 보슬아치, 보슬, 창녀, 창남, 걸레년, 걸레, 호모, 게이새끼, 레즈년, 장애년, 장애놈, 정박아, 정신병자 및 인종·국적·종교 직접 비하 표현 |
| 영어 욕설 | fuck, fucking, fucker, fuckers, fucked, fuckyou, fucku, fck, fuk, fucc, fuxk, shit, shitty, bullshit, shithead, bitch, bitches, biatch, bastard, asshole, arsehole, dumbass, jackass, motherfucker, motherfuck, mfucker, cunt, dickhead, cocksucker, twat, whore, slut, retard, retarded, stfu, gtfo |
| 범죄·마약 | 마약, 필로폰, 히로뽕, 대마, 대마초, 코카인, 헤로인, 펜타닐, 엑스터시, 몰리, LSD, 메스암페타민, drug, drugs, cocaine, heroin, meth, methamphetamine, fentanyl, weed, marijuana, cannabis, ecstasy, mdma |

## RESERVED 운영자·시스템 사칭

`관리자`, `운영자`, `운영진`, `어드민`, `관리팀`, `운영팀`, `개발자`, `개발팀`, `공식`, `공식계정`, `SSAFY관리자`, `SSAFY운영자`, `싸피관리자`, `싸피운영자`, `SSAFESTA관리자`, `SSAFESTA운영자`, `admin`, `administrator`, `manager`, `moderator`, `mod`, `staff`, `operator`, `official`, `system`, `root`, `superuser`, `support`, `developer`, `devteam`, `management`, `ssafyadmin`, `ssafystaff`, `ssafestaadmin`, `ssafestastaff`를 차단한다. `ssafy`, `ssafesta`는 단독 사용 또는 위 예약어와 결합한 경우만 차단한다.

## 광고·개인정보 유도 패턴

`카톡`, `카카오톡`, `오픈채팅`, `텔레그램`, `디엠`, `DM주세요`, `문의주세요`, `연락주세요`, `판매`, `구매`, `삽니다`, `팝니다`, `kakao`, `kakaotalk`, `telegram`, `discord`, `contactme`, `dmme`, `followme` 및 전화번호, 이메일, URL(`http`, `www`, `t.me`, `open.kakao`) 패턴을 차단한다.

## 운영 원칙

- 신규 변형·인종·국적·종교 비하 표현은 운영 금칙어 목록에 즉시 추가한다.
- `weed` 등 오탐 위험 영어 단어는 단어 경계 또는 완전 일치로 검사한다.
- 금칙어 목록과 정규화 규칙 변경은 감사 가능한 운영 변경 이력으로 남긴다.
