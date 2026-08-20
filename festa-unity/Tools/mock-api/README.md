# Mock HTTP API (정적 JSON)

Spring 없이 `HttpBoothApiClient`를 검증하기 위한 정적 endpoint.
`api/v1/booths/7/layouts/published` 파일이 실제 API 경로 모양 그대로 배치되어 있다.

## 사용법 — 같은 오리진 서빙 (CORS 회피)

Web 빌드를 서빙하는 폴더에 `api/`를 복사해서 **웹페이지와 같은 오리진**(localhost:8000)으로 제공한다.
브라우저에서 cross-origin 요청은 CORS 헤더가 필요하지만, 같은 오리진이면 불필요하다.

```powershell
# 1. api 폴더를 웹 서빙 폴더로 복사 (Web 재빌드 후에도 다시 실행)
robocopy Tools\mock-api\api Builds\web\api /E

# 2. 웹 서버 실행 (이미 켜져 있으면 생략)
py -m http.server 8000 --directory Builds/web   # python 별칭이 안 잡히면 py 사용

# 3. 브라우저로 직접 확인 (JSON이 보여야 함)
#    http://localhost:8000/api/v1/booths/7/layouts/published
```

- ApiConfig의 Local 환경 springBaseUrl이 `http://localhost:8000`인 이유가 이것 (웹페이지와 동일 오리진)
- **에디터**에서도 같은 URL로 검증 가능 (에디터는 CORS 제약 없음)
- 404 테스트: 존재하지 않는 boothId(예: 99)로 요청하면 웹 서버가 404 반환 → graceful 처리 확인
- 실제 Spring이 준비되면 ApiConfig의 base URL만 바꾸면 된다 (코드 변경 없음)
