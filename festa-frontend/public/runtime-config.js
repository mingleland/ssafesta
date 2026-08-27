// 배포 이미지의 entrypoint가 PUBLIC_API_BASE_URL로 이 파일을 덮어쓴다 (S15P21A604-254).
// 여기 있는 빈 객체는 개발 서버용 기본값이다 — 값이 없으면 shared/config/runtime.ts가
// VITE_API_BASE_URL로 내려간다. 파일 자체를 두는 이유는 npm run dev에서 404 콘솔 오류를 만들지 않기 위해서다.
window.__FESTA_CONFIG__ = {};
