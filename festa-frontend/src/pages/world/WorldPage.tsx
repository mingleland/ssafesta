// /app/world가 마운트하는 화면 — Unity WebGL Host를 그대로 띄운다 (spec 013a, docs/10 §3)
import { UnityHost } from '../../unity/host/UnityHost';

export function WorldPage() {
  return <UnityHost />;
}
