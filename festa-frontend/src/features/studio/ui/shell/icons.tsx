// Booth Studio 아이콘 — 20px 스트로크 세트. 에모지·외부 아이콘 폰트 없이 인라인 SVG 로 통일한다.
import type { SVGProps } from 'react';

type P = SVGProps<SVGSVGElement> & { size?: number };

function base({ size = 18, ...rest }: P) {
  return {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
    ...rest,
  };
}

export const IcBack = (p: P) => <svg {...base(p)}><path d="M15 5l-7 7 7 7" /></svg>;
export const IcEdit = (p: P) => <svg {...base(p)}><path d="M4 20h4l11-11-4-4L4 16v4Z" /></svg>;
export const IcSave = (p: P) => <svg {...base(p)}><path d="M5 4h11l3 3v13H5V4Z" /><path d="M8 4v5h7V4M8 20v-6h8v6" /></svg>;
export const IcPlay = (p: P) => <svg {...base(p)}><path d="M7 5v14l11-7-11-7Z" fill="currentColor" stroke="none" /></svg>;
export const IcUndo = (p: P) => <svg {...base(p)}><path d="M9 7 4 12l5 5" /><path d="M4 12h10a5 5 0 0 1 0 10h-2" /></svg>;
export const IcRedo = (p: P) => <svg {...base(p)}><path d="m15 7 5 5-5 5" /><path d="M20 12H10a5 5 0 0 0 0 10h2" /></svg>;
export const IcChevron = (p: P) => <svg {...base(p)}><path d="m6 9 6 6 6-6" /></svg>;
export const IcGear = (p: P) => <svg {...base(p)}><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z" /></svg>;
export const IcCube = (p: P) => <svg {...base(p)}><path d="M12 3 4 7.5v9L12 21l8-4.5v-9L12 3Z" /><path d="M4 7.5 12 12l8-4.5M12 12v9" /></svg>;
export const IcHome = (p: P) => <svg {...base(p)}><path d="M4 11 12 4l8 7v9H4v-9Z" /><path d="M10 20v-6h4v6" /></svg>;
export const IcSparkle = (p: P) => <svg {...base(p)}><path d="M12 3v4M12 17v4M3 12h4M17 12h4M6.5 6.5 9 9M15 15l2.5 2.5M6.5 17.5 9 15M15 9l2.5-2.5" /></svg>;
export const IcChair = (p: P) => <svg {...base(p)}><path d="M6 4h9v8H6zM6 12h12v4H6zM8 16v4M16 16v4" /></svg>;
export const IcDevice = (p: P) => <svg {...base(p)}><rect x="3" y="5" width="18" height="12" rx="2" /><path d="M8 21h8M12 17v4" /></svg>;
export const IcTemplate = (p: P) => <svg {...base(p)}><rect x="3" y="3" width="8" height="8" rx="1.5" /><rect x="13" y="3" width="8" height="8" rx="1.5" /><rect x="3" y="13" width="8" height="8" rx="1.5" /><rect x="13" y="13" width="8" height="8" rx="1.5" /></svg>;
export const IcPlus = (p: P) => <svg {...base(p)}><path d="M12 5v14M5 12h14" /></svg>;
export const IcLock = (p: P) => <svg {...base(p)}><rect x="5" y="11" width="14" height="10" rx="2" /><path d="M8 11V7a4 4 0 0 1 8 0v4" /></svg>;
export const IcCursor = (p: P) => <svg {...base(p)}><path d="M5 3l14 8-6.5 1.5L9 19 5 3Z" /></svg>;
export const IcMove = (p: P) => <svg {...base(p)}><path d="M12 3v18M3 12h18M8 7l4-4 4 4M8 17l4 4 4-4M7 8l-4 4 4 4M17 8l4 4-4 4" /></svg>;
export const IcRotate = (p: P) => <svg {...base(p)}><circle cx="12" cy="12" r="3" /><path d="M20 12a8 8 0 1 1-3-6.2M20 4v5h-5" /></svg>;
export const IcFrame = (p: P) => <svg {...base(p)}><path d="M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5" /></svg>;
export const IcMagnet = (p: P) => <svg {...base(p)}><path d="M6 4v8a6 6 0 0 0 12 0V4" /><path d="M6 4h4v8M14 4h4v8" /></svg>;
export const IcTrash = (p: P) => <svg {...base(p)}><path d="M5 7h14M9 7V5h6v2M7 7l1 13h8l1-13" /></svg>;
export const IcCopy = (p: P) => <svg {...base(p)}><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V5h10" /></svg>;
export const IcZoomOut = (p: P) => <svg {...base(p)}><circle cx="11" cy="11" r="6" /><path d="m20 20-4-4M8 11h6" /></svg>;
export const IcZoomIn = (p: P) => <svg {...base(p)}><circle cx="11" cy="11" r="6" /><path d="m20 20-4-4M8 11h6M11 8v6" /></svg>;
export const IcBrush = (p: P) => <svg {...base(p)}><path d="M14 3 21 10l-9 9-7-7 9-9Z" /><path d="M5 12 3 21l9-2" /></svg>;
export const IcLink = (p: P) => <svg {...base(p)}><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></svg>;
export const IcCheck = (p: P) => <svg {...base(p)}><path d="M4 12.5l5 5L20 6.5" /></svg>;
