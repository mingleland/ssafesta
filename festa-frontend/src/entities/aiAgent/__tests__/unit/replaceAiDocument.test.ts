// 문서 수정본 교체의 호출 경로 (S15P21A604-691, GitLab #179).
//
// 교체는 신규 등록과 2·3단계를 공유하고 1단계만 다르다. 잠글 것은 그 한 가지 — **1단계가 원본 문서를
// PUT 으로 지목하고, 완료는 1단계가 돌려준 새 문서 id 로 부른다**. 원본 id 로 완료를 부르면 서버는
// 409 를 주고 교체본은 영영 대기로 남는다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { replaceAiDocument } from '../../api';

const ORIGINAL_ID = 153;
const REPLACEMENT_ID = 1201;

function file(): File {
  return new File(['# 고친 안내문'], 'guide.md', { type: 'text/markdown' });
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

let calls: { url: string; method: string; body?: unknown }[];

beforeEach(() => {
  calls = [];
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function stubFetch(grant: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? 'GET';
      calls.push({ url, method, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined });
      if (url.includes('/replacement')) return jsonResponse(grant);
      if (url.startsWith('https://storage.example')) return new Response(null, { status: 200 });
      if (url.includes('/complete')) return jsonResponse({ documentId: REPLACEMENT_ID, processingStatus: 'QUEUED' });
      throw new Error('예상하지 못한 호출: ' + method + ' ' + url);
    }),
  );
}

describe('replaceAiDocument — 교체 성공', () => {
  it('원본을 PUT 으로 지목하고, 업로드한 뒤, 새 문서 id 로 완료한다', async () => {
    stubFetch({ duplicate: false, documentId: REPLACEMENT_ID, uploadUrl: 'https://storage.example/put' });

    const result = await replaceAiDocument(ORIGINAL_ID, file());

    expect(result).toEqual({ duplicate: false, documentId: REPLACEMENT_ID, processingStatus: 'QUEUED' });
    expect(calls).toHaveLength(3);
    expect(calls[0].method).toBe('PUT');
    expect(calls[0].url).toContain(`/api/v1/documents/${ORIGINAL_ID}/replacement`);
    expect(calls[0].body).toMatchObject({ fileName: 'guide.md', contentType: 'text/markdown' });
    expect(calls[1].method).toBe('PUT');
    expect(calls[1].url).toBe('https://storage.example/put');
    // 완료는 원본이 아니라 교체본 id 로 간다
    expect(calls[2].url).toContain(`/api/v1/documents/${REPLACEMENT_ID}/complete`);
    expect(calls[2].url).not.toContain(`/documents/${ORIGINAL_ID}/complete`);
  });
});

describe('replaceAiDocument — 같은 파일', () => {
  it('중복이면 업로드도 완료도 하지 않는다', async () => {
    stubFetch({ duplicate: true, documentId: REPLACEMENT_ID });

    const result = await replaceAiDocument(ORIGINAL_ID, file());

    expect(result).toEqual({ duplicate: true, documentId: REPLACEMENT_ID });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toContain('/replacement');
  });
});
