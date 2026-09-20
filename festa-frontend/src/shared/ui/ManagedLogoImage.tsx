// BE 관리 로고(img 태그로 토큰을 못 싣는 자리) 전용 이미지.
//
// 배경: content 조회는 게시 참조 + 유효 임대면 익명이지만, 그 전(미게시·저장 직후)에는
// 부스 편집자만 통과한다. <img> 는 Authorization 을 실을 수 없어 그 분기에서 404 가 떨어지고
// 깨진 아이콘이 된다. 그래서 관리 경로 모양일 때만 토큰付き fetch 로 받아 blob URL 로 건다.
// 방문자용(ProjectOverlay)은 게시본만 그리므로 기존 <img> 그대로 둔다.
import { useEffect, useRef, useState } from 'react';
import { getAccessToken } from '../api/client';

/** BE ManagedProjectLogoUrl.logoIdOf 와 같은 모양 — 이 형태만 가로챈다. */
const MANAGED_LOGO_PATH = /^\/api\/v1\/booths\/\d+\/project-logos\/[^/]+\/content$/;

export function isManagedLogoUrl(src: string): boolean {
  return MANAGED_LOGO_PATH.test(src);
}

interface Props {
  src: string;
  alt: string;
  className?: string;
  onError?: () => void;
}

export function ManagedLogoImage({ src, alt, className, onError }: Props) {
  const [resolved, setResolved] = useState<string | null>(isManagedLogoUrl(src) ? null : src);
  const owned = useRef<string | null>(null);
  // onError 는 인라인 람다가 오므로 effect deps 에 넣지 않는다 — 바뀌었다고 다시 받지 않는다
  const onErrorRef = useRef(onError);
  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  useEffect(() => {
    if (!isManagedLogoUrl(src)) {
      if (owned.current) {
        URL.revokeObjectURL(owned.current);
        owned.current = null;
      }
      setResolved(src);
      return;
    }
    let cancelled = false;
    setResolved(null);
    const token = getAccessToken();
    fetch(src, {
      headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    })
      .then((response) => {
        if (!response.ok) throw new Error(`logo fetch failed: ${response.status}`);
        return response.blob();
      })
      .then((blob) => {
        if (cancelled) return;
        // 이전 blob 은 여기서 걷는다 — unmount·src 교체 때만 revoke 하면 그 사이 것이 샌다
        if (owned.current) URL.revokeObjectURL(owned.current);
        owned.current = URL.createObjectURL(blob);
        setResolved(owned.current);
      })
      .catch(() => {
        if (cancelled) return;
        // 실패는 호출부의 onError 폴백(깨진 자리 표시)으로 넘긴다 — 여기서 문구를 만들지 않는다
        onErrorRef.current?.();
      });
    return () => {
      cancelled = true;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src]);

  useEffect(
    () => () => {
      if (owned.current) URL.revokeObjectURL(owned.current);
    },
    [],
  );

  if (resolved === null) return null;
  return <img className={className} src={resolved} alt={alt} onError={onError} />;
}
