// Unity Web 빌드용 로컬 서버 (node 판). serve.py 와 같은 역할 —
// .wasm MIME 등록 + 캐시 무효화. Windows 셸에서 python 별칭이 막혀 있을 때 쓴다.
const http=require("http"),fs=require("fs"),path=require("path");
const ROOT=process.argv[2]||"Builds/web", PORT=+(process.argv[3]||8000);
const MIME={".html":"text/html; charset=utf-8",".js":"text/javascript",".wasm":"application/wasm",
  ".data":"application/octet-stream",".json":"application/json",".css":"text/css",
  ".png":"image/png",".jpg":"image/jpeg",".symbols":"application/octet-stream"};

// FESTA_MOCK_LAYOUTS=1 일 때만 부스 게시 레이아웃을 가로채 MockBoothApiClient 와 **같은 내용**으로 돌려준다.
// 왜: 성능 측정은 Mock 빌드에서 잡은 기준선과 비교하는데, 로컬 Spring 에는 게시본이 0건이라
// 부스가 전부 비어 버린다. 부스 프롭이 없으면 BoothInteractionInput 의 대상 수 N 이 0 이라
// 측정하려는 회귀 자체가 나타나지 않는다 (2026-09-08). 규칙은 Mock 과 동일 — 홀수 슬롯만 게시.
const MOCK_LAYOUTS = process.env.FESTA_MOCK_LAYOUTS === "1";
const MOCK_LAYOUT = MOCK_LAYOUTS
  ? JSON.parse(fs.readFileSync(path.join(__dirname,"mock-api","booth-slot-layout.json"),"utf8"))
  : null;

http.createServer((req,res)=>{
  const url=decodeURIComponent(req.url.split("?")[0]);

  const slotMatch = MOCK_LAYOUTS && url.match(/^\/api\/v1\/booth-slots\/(\d+)\/layouts\/published$/);
  if (slotMatch) {
    const origin = req.headers.origin || "*";
    const cors = {
      "Access-Control-Allow-Origin": origin,
      "Vary": "Origin",
      "Access-Control-Allow-Credentials": "true",
      "Access-Control-Allow-Methods": "GET,OPTIONS",
      "Access-Control-Allow-Headers": "authorization,content-type",
      "Cache-Control": "no-store"
    };
    if (req.method === "OPTIONS") { res.writeHead(204, cors); return res.end(); }
    const slot = +slotMatch[1];
    if (slot % 2 === 0) { res.writeHead(404, cors); return res.end(); }   // 미게시 = null
    const body = JSON.stringify({ ...MOCK_LAYOUT, boothId: slot });
    res.writeHead(200, { ...cors, "Content-Type":"application/json; charset=utf-8" });
    return res.end(body);
  }

  // Unity 빌드는 API 를 이 오리진에서 찾는다(빌드 타임 localhost:8000).
  // FE 가 __FESTA_CONFIG__ 를 채우지 않아 주입이 안 되므로 여기서 Spring 으로 넘긴다.
  if (url.startsWith("/api/")) {
    const opts={host:"127.0.0.1",port:18080,path:req.url,method:req.method,headers:{...req.headers,host:"localhost:18080"}};
    const up=require("http").request(opts,r=>{
      res.writeHead(r.statusCode, r.headers);   // Spring 의 CORS 헤더를 그대로 전달한다
      r.pipe(res);
    });
    up.on("error",e=>{res.writeHead(502);res.end("proxy: "+e.message)});
    req.pipe(up);
    return;
  }
  const f=path.join(ROOT, url==="/"?"index.html":url.slice(1));
  fs.readFile(f,(e,d)=>{
    if(e){res.writeHead(404);return res.end("404 "+url)}
    // 릴리스(Brotli) 산출물은 원래 확장자 뒤에 .unityweb 가 붙는다 (X.wasm.unityweb 등).
    // 서버가 Content-Encoding: br 을 붙여야 브라우저가 풀어 준다 — 안 붙이면 Decompression Fallback 이
    // JS 로 푸느라 로딩이 크게 느려지고, 폴백이 꺼진 빌드는 아예 못 뜬다.
    const extra = {};
    if (f.endsWith(".unityweb")) {
      const inner = path.extname(f.slice(0, -".unityweb".length));
      extra["Content-Type"] = inner === ".wasm" ? "application/wasm"
                            : inner === ".js"   ? "text/javascript"
                            : "application/octet-stream";
      extra["Content-Encoding"] = "br";
    }
    res.writeHead(200,{"Content-Type":MIME[path.extname(f)]||"application/octet-stream", ...extra,
      "Cache-Control":"no-store",
      // FE(5173) 가 다른 오리진에서 manifest·바이너리를 읽는다 — CORS 필수.
      "Access-Control-Allow-Origin":"*",
      "Cross-Origin-Resource-Policy":"cross-origin","Cross-Origin-Opener-Policy":"same-origin",
      "Cross-Origin-Embedder-Policy":"require-corp"});
    res.end(d);
  });
}).listen(PORT,()=>console.log(`serve.js → http://localhost:${PORT}/  (${ROOT}) mockLayouts=${MOCK_LAYOUTS}`));
