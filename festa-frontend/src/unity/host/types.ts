// Unity WebGL Host가 다루는 최소 타입 — 빌드 URL 서술자와 Unity 인스턴스 표면
// 출처: 013a WebGL Host 계획 B-1(manifest는 확정 계약 아님 — Host는 URL 4종만 안다)

// production resolver(manifest 읽기)든 mock이든 이 4개 URL만 만들어내면 된다.
// manifest.json의 실제 스키마는 Unity 측 미확정 계약이라 이 타입에 묶지 않는다.
export interface UnityBuildDescriptor {
  loaderUrl: string;
  dataUrl: string;
  frameworkUrl: string;
  codeUrl: string;
}

// Unity WebGL 로더가 반환하는 인스턴스 — 실 로더 스크립트는 저장소에 없어(빌드 산출물,
// festa-unity/.gitignore) 공개 문서에 있는 API만 선언한다.
export interface UnityInstance {
  SendMessage(gameObjectName: string, methodName: string, value?: string | number): void;
  SetFullscreen(fullscreen: 0 | 1): void;
  Quit(): Promise<void>;
}

export type UnityProgressListener = (progress: number) => void;
