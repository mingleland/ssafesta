// -116 원격 Asset 어댑터 — 계약(specs/019-game-studio/contracts/game-asset-upload.md) 기준 회귀.
// 서버 구현(-107·-176)이 아직 없어 실서버 왕복은 못 한다. 여기서 고정하는 것은 계약이 이미
// 확정한 부분이다: source 형식(§2)·업로드 3단계(§3.1~3.2)·전달 경로(§3.4)·검증(§5).
import { describe, expect, it, vi } from 'vitest';
import {
  assetContentPath,
  createApiGameAssetRepository,
  parseStableAssetSource,
} from '../../studio/assets/remoteAssetRepository';

const ASSET_ID = 'aB7kQ2mZ9xR4tL6vN0wY3sJ8pc';
const file = (overrides: Partial<{ type: string; size: number; name: string }> = {}) => ({
  type: 'image/png',
  size: 1024,
  name: 'hero.png',
  ...overrides,
} as File);

describe('parseStableAssetSource — 계약 §2', () => {
  it('stable 형식을 gameId·assetId 로 가른다', () => {
    expect(parseStableAssetSource(`asset://game/123/${ASSET_ID}`)).toEqual({ gameId: 123, assetId: ASSET_ID });
  });

  it('로컬 참조는 받지 않는다', () => {
    // local 과 arity 가 같아 느슨하게 자르면 로컬 참조가 원격으로 새어 나간다
    expect(parseStableAssetSource(`asset://local/123/${ASSET_ID}`)).toBeNull();
  });

  it('builtin·빈 문자열·authority 누락을 거부한다', () => {
    expect(parseStableAssetSource('builtin://sprites/hero.png')).toBeNull();
    expect(parseStableAssetSource('')).toBeNull();
    expect(parseStableAssetSource(`asset://game/${ASSET_ID}`)).toBeNull();
    expect(parseStableAssetSource('asset://game//abc')).toBeNull();
  });

  it('gameId 가 양의 정수가 아니면 거부한다', () => {
    expect(parseStableAssetSource(`asset://game/0/${ASSET_ID}`)).toBeNull();
    expect(parseStableAssetSource(`asset://game/-1/${ASSET_ID}`)).toBeNull();
    expect(parseStableAssetSource(`asset://game/1.5/${ASSET_ID}`)).toBeNull();
    expect(parseStableAssetSource(`asset://game/abc/${ASSET_ID}`)).toBeNull();
  });

  it('stableId 패턴 밖의 assetId 를 거부한다', () => {
    expect(parseStableAssetSource('asset://game/1/9startsWithDigit')).toBeNull();
    expect(parseStableAssetSource('asset://game/1/has space')).toBeNull();
    expect(parseStableAssetSource('asset://game/1/has/slash')).toBeNull();
    expect(parseStableAssetSource(`asset://game/1/${'a'.repeat(65)}`)).toBeNull();
  });
});

describe('assetContentPath — 계약 §3.4', () => {
  it('전달 경로로 변환한다', () => {
    expect(assetContentPath({ gameId: 123, assetId: ASSET_ID }))
      .toBe(`/api/v1/games/123/assets/${ASSET_ID}/content`);
  });
});

describe('resolve', () => {
  it('stable 참조는 인증 fetch 로 받아 object URL 을 만든다', async () => {
    // `<img src>` 직결이 아니라 fetch 인 이유: /content 는 소유자 전용이고(§3) AT 는 메모리에
    // 있어 `<img>` 요청에 Authorization 이 실리지 않는다. 편집기에서 자기 Draft Asset 을
    // 보려면 헤더가 필요하다.
    const fetchContent = vi.fn(async () => new Blob(['x']));
    const repository = createApiGameAssetRepository({ fetchContent });
    const url = await repository.resolve(`asset://game/7/${ASSET_ID}`);
    expect(fetchContent).toHaveBeenCalledWith(`/api/v1/games/7/assets/${ASSET_ID}/content`);
    expect(url).toMatch(/^blob:/);
  });

  it('로컬 참조는 주입된 로컬 저장소에 넘긴다', async () => {
    const local = { resolve: vi.fn(async () => 'blob:local') };
    const fetchContent = vi.fn();
    const repository = createApiGameAssetRepository({ local, fetchContent });
    await expect(repository.resolve(`asset://local/7/${ASSET_ID}`)).resolves.toBe('blob:local');
    expect(fetchContent).not.toHaveBeenCalled();
  });

  it('로컬 저장소가 없으면 로컬 참조는 null 이고 서버를 부르지 않는다', async () => {
    const fetchContent = vi.fn();
    const repository = createApiGameAssetRepository({ fetchContent });
    await expect(repository.resolve(`asset://local/7/${ASSET_ID}`)).resolves.toBeNull();
    expect(fetchContent).not.toHaveBeenCalled();
  });

  it('builtin 참조는 건드리지 않는다', async () => {
    const fetchContent = vi.fn();
    const repository = createApiGameAssetRepository({ fetchContent });
    await expect(repository.resolve('builtin://sprites/hero.png')).resolves.toBeNull();
    expect(fetchContent).not.toHaveBeenCalled();
  });
});

describe('save — 계약 §3.1~3.2', () => {
  const grant = { assetId: ASSET_ID, uploadUrl: 'https://storage.example/put', requiredHeaders: { 'Content-Type': 'image/png' } };
  const completed = { assetId: ASSET_ID, status: 'READY', kind: 'IMAGE', source: `asset://game/7/${ASSET_ID}` };

  const repositoryWith = (request: ReturnType<typeof vi.fn>, upload = vi.fn(async () => undefined)) => ({
    repository: createApiGameAssetRepository({ request: request as never, upload }),
    upload,
  });

  it('시작 → presigned PUT → 완료 순서로 부르고 서버가 준 source 를 그대로 쓴다', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(grant)
      .mockResolvedValueOnce(completed);
    const { repository, upload } = repositoryWith(request);

    const result = await repository.save(7, { kind: 'IMAGE', file: file() });

    expect(request.mock.calls[0][0]).toBe('/api/v1/games/7/assets');
    expect(upload).toHaveBeenCalledOnce();
    expect(request.mock.calls[1][0]).toBe(`/api/v1/games/7/assets/${ASSET_ID}/complete`);
    // FE 가 source 를 조립하지 않는다 — 조립하면 서버가 형식을 바꿨을 때 저장은 성공하고
    // 표시만 깨진다
    expect(result.asset).toEqual({ id: ASSET_ID, kind: 'IMAGE', source: `asset://game/7/${ASSET_ID}` });
    expect(result.originalName).toBe('hero.png');
  });

  it('클라이언트가 제시한 assetId 를 쓰지 않는다', async () => {
    const request = vi.fn().mockResolvedValueOnce(grant).mockResolvedValueOnce(completed);
    const { repository } = repositoryWith(request);
    const result = await repository.save(7, { kind: 'IMAGE', file: file(), suggestedAssetId: 'clientChosen' });
    expect(result.asset.id).toBe(ASSET_ID);
  });

  it('AUDIO 는 서버를 부르기 전에 거부한다 — 계약 §1', async () => {
    const request = vi.fn();
    const { repository } = repositoryWith(request);
    await expect(repository.save(7, { kind: 'AUDIO', file: file({ type: 'audio/mpeg' }) })).rejects.toThrow();
    expect(request).not.toHaveBeenCalled();
  });

  it('5MiB 초과·허용 밖 MIME 도 서버를 부르기 전에 거부한다 — 계약 §5', async () => {
    const request = vi.fn();
    const { repository } = repositoryWith(request);
    await expect(repository.save(7, { kind: 'IMAGE', file: file({ size: 5 * 1024 * 1024 + 1 }) })).rejects.toThrow();
    // SVG 는 스크립트를 담을 수 있어 화이트리스트 밖이다
    await expect(repository.save(7, { kind: 'IMAGE', file: file({ type: 'image/svg+xml' }) })).rejects.toThrow();
    expect(request).not.toHaveBeenCalled();
  });

  it('완료 응답이 READY 가 아니면 실패로 다룬다 — 계약 §4', async () => {
    const request = vi.fn().mockResolvedValueOnce(grant).mockResolvedValueOnce({ ...completed, status: 'FAILED' });
    const { repository } = repositoryWith(request);
    await expect(repository.save(7, { kind: 'IMAGE', file: file() })).rejects.toMatchObject({ code: 'GAME_ASSET_NOT_READY' });
  });

  it('완료 응답의 source 가 요청한 game·asset 과 다르면 거부한다 — 계약 §2 1차 방어선', async () => {
    const request = vi.fn().mockResolvedValueOnce(grant).mockResolvedValueOnce({ ...completed, source: `asset://game/99/${ASSET_ID}` });
    const { repository } = repositoryWith(request);
    await expect(repository.save(7, { kind: 'IMAGE', file: file() })).rejects.toMatchObject({ code: 'GAME_API_RESPONSE_INVALID' });
  });

  it('시작 응답의 assetId 형식이 계약 밖이면 업로드하지 않는다', async () => {
    const request = vi.fn().mockResolvedValueOnce({ ...grant, assetId: 'has space' });
    const upload = vi.fn(async () => undefined);
    const { repository } = repositoryWith(request, upload);
    await expect(repository.save(7, { kind: 'IMAGE', file: file() })).rejects.toMatchObject({ code: 'GAME_API_RESPONSE_INVALID' });
    expect(upload).not.toHaveBeenCalled();
  });
});
