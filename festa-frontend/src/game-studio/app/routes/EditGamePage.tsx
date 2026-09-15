import { useMemo } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { createApiGameDraftRepository, createApiGamePublisher, createApiGameVisibilityPort } from '../../studio/ports/gameAuthoringApi.ts';
import { createBrowserAssetRepository } from '../../studio/assets/localAssetRepository.ts';
import { createApiGameAssetRepository } from '../../studio/assets/remoteAssetRepository.ts';
import { createBrowserPublicationPorts } from '../../studio/ports/localPublicationRepository.ts';
import { GameStudioShell } from '../../studio/ui/GameStudioShell.tsx';
import { createEditorStressProject } from '../../studio/model/createEditorStressProject.ts';
import { isBrowserPublicationEnabled, isServerAuthoringEnabled } from '../runtimeConfig.ts';

export const EditGamePage = () => {
  const { gameId } = useParams();
  const [searchParams] = useSearchParams();
  const parsedGameId = Number(gameId);
  const validGameId = Number.isSafeInteger(parsedGameId) && parsedGameId >= 1;
  const stressFixtureEnabled = import.meta.env.DEV && searchParams.get('fixture') === 'max';
  // S15P21A604-409/-477 — 이전엔 아래 다섯 값이 전부 모듈 최상단 상수로 import 시점에 한
  // 번만 읽혀서, .env.local 값에 좌우되는 결함이 PlayGamePage.tsx(S15P21A604-409)와 똑같이
  // 있었다(GitLab Issue #125). 컴포넌트 마운트 시점(이른 return보다 먼저 — Hooks 규칙
  // 준수)에 accessor로 읽고, 포트 인스턴스는 useMemo로 참조 안정성을 유지한다.
  const serverAuthoringEnabled = isServerAuthoringEnabled();
  const browserPublicationEnabled = isBrowserPublicationEnabled();
  const serverDraftRepository = useMemo(() => (
    serverAuthoringEnabled ? createApiGameDraftRepository() : undefined
  ), [serverAuthoringEnabled]);
  const serverPublisher = useMemo(() => (
    serverAuthoringEnabled ? createApiGamePublisher() : undefined
  ), [serverAuthoringEnabled]);
  const serverVisibilityPort = useMemo(() => (
    serverAuthoringEnabled ? createApiGameVisibilityPort() : undefined
  ), [serverAuthoringEnabled]);
  // S15P21A604-116 — 서버 저작이 켜지면 자산도 서버로 간다. 여기가 비어 있던 동안 편집기는
  // assetRepository=null 을 받아 업로드가 "로컬 저장소를 사용할 수 없습니다" 로 막혔다 —
  // 원격 어댑터는 이미 있었고 부르는 쪽이 없었다(GitLab #69).
  // local 을 함께 넘기는 이유는 기존 프로젝트의 asset://local/ 참조가 편집 중에는 계속
  // 보여야 하기 때문이다. 업로드는 항상 서버로 가고, 로컬은 옛 참조 읽기·삭제에만 쓰인다.
  const serverAssetRepository = useMemo(() => (
    serverAuthoringEnabled
      ? createApiGameAssetRepository({ local: createBrowserAssetRepository() })
      : undefined
  ), [serverAuthoringEnabled]);
  const browserPublicationPorts = useMemo(() => (
    browserPublicationEnabled ? createBrowserPublicationPorts() : null
  ), [browserPublicationEnabled]);
  const initialProject = useMemo(() => (
    stressFixtureEnabled && validGameId ? createEditorStressProject(parsedGameId) : undefined
  ), [parsedGameId, stressFixtureEnabled, validGameId]);
  if (!validGameId) {
    return <main>올바르지 않은 게임 ID입니다.</main>;
  }
  return (
    <GameStudioShell
      assetRepository={serverAssetRepository}
      gameId={parsedGameId}
      initialProject={initialProject}
      key={parsedGameId}
      persistenceLabel={stressFixtureEnabled ? '최대 부하 검증' : serverAuthoringEnabled ? '서버' : browserPublicationEnabled ? '브라우저(Mock)' : '브라우저'}
      publisher={stressFixtureEnabled ? null : serverPublisher ?? browserPublicationPorts?.publisher}
      repository={stressFixtureEnabled ? null : serverDraftRepository}
      visibilityPort={stressFixtureEnabled ? null : serverVisibilityPort ?? null}
    />
  );
};
