import { useEffect, useMemo, useState } from 'react';
import type { DialogueChoice, DialogueScene, GameProject } from '../../contracts/gameProject.ts';
import {
  addDialogueChoice,
  addDialogueNode,
  dialogueNodeRemovalReason,
  removeDialogueChoice,
  removeDialogueNode,
  renameScene,
  reorderDialogueNode,
  setDialogueBackground,
  setStartNode,
  startNodeChangeReason,
  updateDialogueChoice,
  updateDialogueNode,
} from '../model/authoringCommands.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { assetDisplayLabel, isAssetForRole } from '../assets/builtinAssetCatalog.ts';
import { CommitInput } from './CommitInput.tsx';
import { analyzeDialogueFlow } from '../model/dialogueFlow.ts';

interface DialogueEditorProps {
  readonly project: GameProject;
  readonly scene: DialogueScene;
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly onApply: (project: GameProject) => void;
}

const choiceOutcome = (choice: DialogueChoice): string => {
  if (choice.nextNodeId !== undefined) return `next:${choice.nextNodeId}`;
  const terminal = choice.actions.at(-1);
  if (terminal?.type === 'CLOSE_DIALOGUE') return 'close';
  if (terminal?.type === 'COMPLETE_GAME') return 'complete';
  if (terminal?.type === 'GO_TO_SCENE') return `scene:${terminal.sceneId}`;
  return 'none';
};

export const DialogueEditor = ({ project, scene, assetUrls, onApply }: DialogueEditorProps) => {
  const [selectedNodeId, setSelectedNodeId] = useState(scene.startNodeId);
  useEffect(() => setSelectedNodeId(scene.startNodeId), [scene.id, scene.startNodeId]);
  // S15P21A604-494 — 씬 목록의 드래그 정렬(draggedSceneIndex/dragOverSceneIndex)과 동일한 패턴.
  const [draggedNodeIndex, setDraggedNodeIndex] = useState<number | null>(null);
  const [dragOverNodeIndex, setDragOverNodeIndex] = useState<number | null>(null);
  const selectedNode = scene.nodes.find((node) => node.id === selectedNodeId) ?? scene.nodes[0];
  const dialogueFlow = useMemo(() => analyzeDialogueFlow(scene), [scene]);

  // 커밋 전(blur/Enter 이전) 실시간 입력값 — LIVE PREVIEW·좌측 노드 요약을 즉시 갱신하기 위함.
  // 실제 project 상태(undo 히스토리)는 여전히 CommitInput의 onCommit(blur/Enter)에서만 바뀐다.
  const [speakerDraft, setSpeakerDraft] = useState<string | null>(null);
  const [textDraft, setTextDraft] = useState<string | null>(null);
  const [choiceDrafts, setChoiceDrafts] = useState<Record<string, string>>({});
  useEffect(() => {
    setSpeakerDraft(null);
    setTextDraft(null);
    setChoiceDrafts({});
  }, [selectedNodeId, scene.id]);

  if (selectedNode === undefined) return null;
  const previewSpeaker = speakerDraft ?? selectedNode.speaker;
  const previewText = textDraft ?? selectedNode.text;
  const backgroundVisual = resolveStaticImageVisual(
    project.assets.find((asset) => asset.id === scene.backgroundAssetId),
    assetUrls,
  );
  const portraitVisual = resolveStaticImageVisual(
    project.assets.find((asset) => asset.id === selectedNode.portraitAssetId),
    assetUrls,
  );

  const setOutcome = (choice: DialogueChoice, outcome: string) => {
    onApply(updateDialogueChoice(project, scene.id, selectedNode.id, choice.id, (current) => {
      if (outcome.startsWith('next:')) {
        return { ...current, nextNodeId: outcome.slice(5), actions: [] };
      }
      if (outcome.startsWith('scene:')) {
        return { ...current, nextNodeId: undefined, actions: [{ type: 'GO_TO_SCENE', sceneId: outcome.slice(6) }] };
      }
      if (outcome === 'close') return { ...current, nextNodeId: undefined, actions: [{ type: 'CLOSE_DIALOGUE' }] };
      return { ...current, nextNodeId: undefined, actions: [{ type: 'COMPLETE_GAME' }] };
    }));
  };

  return (
    <div className="gss-dialogue-workspace">
      <aside className="gss-node-rail">
        <div className="gss-node-rail-header">
          <span>NODES</span>
          <button
            aria-label="대화 노드 추가"
            disabled={scene.nodes.length >= 300}
            onClick={() => {
              const result = addDialogueNode(project, scene.id);
              onApply(result.project);
              setSelectedNodeId(result.nodeId);
            }}
            type="button"
          >+</button>
        </div>
        {scene.nodes.map((node, index) => {
          const removalReason = dialogueNodeRemovalReason(project, scene.id, node.id);
          const startReason = startNodeChangeReason(project, scene.id, node.id);
          const rowClassName = ['gss-node-row',
            draggedNodeIndex === index ? 'is-dragging' : '',
            dragOverNodeIndex === index && draggedNodeIndex !== index ? 'is-drag-over' : '']
            .filter(Boolean).join(' ');
          return (
            <div
              className={rowClassName}
              key={node.id}
              onDragOver={(dragEvent) => {
                if (draggedNodeIndex === null) return;
                dragEvent.preventDefault();
                if (dragOverNodeIndex !== index) setDragOverNodeIndex(index);
              }}
              onDrop={(dragEvent) => {
                dragEvent.preventDefault();
                if (draggedNodeIndex !== null && draggedNodeIndex !== index) {
                  onApply(reorderDialogueNode(project, scene.id, scene.nodes[draggedNodeIndex]!.id, index));
                }
                setDraggedNodeIndex(null);
                setDragOverNodeIndex(null);
              }}
            >
              <button
                aria-label={`${index + 1}번째 노드 순서 변경 핸들`}
                className="gss-icon-button gss-drag-handle"
                draggable
                onDragEnd={() => { setDraggedNodeIndex(null); setDragOverNodeIndex(null); }}
                onDragStart={(dragEvent) => {
                  dragEvent.dataTransfer?.setData('text/plain', String(index));
                  setDraggedNodeIndex(index);
                }}
                type="button"
              >☰</button>
              <button
                className={`gss-node-button${node.id === selectedNode.id ? ' is-active' : ''}`}
                onClick={() => setSelectedNodeId(node.id)}
                type="button"
              >
                {/* S15P21A604-494 — 배열 인덱스 기반 번호는 드래그로 순서를 바꾸는 순간
                    같이 바뀌어서 "이 노드의 고정된 이름표"처럼 오인하기 쉬웠다. 그 자체로
                    저장되거나 참조되는 값도 아니라 표시를 뺀다 — 어떤 노드인지는
                    speaker/text 요약과 START 배지로 구분한다. */}
                <div>
                  <strong>{(node.id === selectedNode.id ? previewSpeaker : node.speaker) || '내레이션'}</strong>
                  <small>{node.id === selectedNode.id ? previewText : node.text}</small>
                </div>
                {node.id === scene.startNodeId && <em>START</em>}
              </button>
              <button
                aria-label={`${index + 1}번째 노드를 시작으로 설정`}
                className="gss-icon-button"
                disabled={startReason !== null}
                onClick={() => onApply(setStartNode(project, scene.id, node.id))}
                title={startReason ?? '이 노드를 시작으로 설정'}
                type="button"
              >★</button>
              <button
                aria-label={`${index + 1}번째 노드 삭제`}
                className="gss-icon-button"
                disabled={removalReason !== null}
                onClick={() => onApply(removeDialogueNode(project, scene.id, node.id))}
                title={removalReason ?? '노드 삭제'}
                type="button"
              >×</button>
            </div>
          );
        })}
      </aside>

      <div className="gss-dialogue-editor">
        {/* S15P21A604-512 — scene.presentation 원시값 배지 제거. 캔버스 상단 툴바가 이미
            describeSceneType()으로 같은 정보를 "대화-Overlay"/"대화-Fullscreen"처럼
            친숙하게 보여주고 있어서 여기서 다시 원시값("OVERLAY"/"FULL_SCREEN")을
            보여주는 건 정보 중복이었다. */}
        <div className="gss-dialogue-scene-header">
          <div>
            <span className="gss-eyebrow">DIALOGUE SCENE</span>
            <CommitInput
              label="Scene 이름"
              onCommit={(name) => onApply(renameScene(project, scene.id, name))}
              value={scene.name}
            />
          </div>
        </div>
        <section className="gss-dialogue-flow" aria-label="대화 흐름 개요">
          <header>
            <div><span className="gss-eyebrow">FLOW OVERVIEW</span><strong>대화 흐름</strong></div>
            <div className="gss-dialogue-flow-metrics">
              <span>{dialogueFlow.reachableCount}/{scene.nodes.length} 도달</span>
              {dialogueFlow.unreachableCount > 0 && <em>미연결 {dialogueFlow.unreachableCount}</em>}
              {dialogueFlow.incompleteOutcomeCount > 0 && <em>결과 확인 {dialogueFlow.incompleteOutcomeCount}</em>}
            </div>
          </header>
          <div className="gss-dialogue-flow-list">
            {dialogueFlow.nodes.map((flow, index) => {
              const node = scene.nodes.find((candidate) => candidate.id === flow.nodeId);
              return (
                <button
                  aria-current={flow.nodeId === selectedNode.id ? 'step' : undefined}
                  className={`${flow.nodeId === selectedNode.id ? 'is-active' : ''}${flow.reachable ? '' : ' is-unreachable'}${flow.hasIncompleteOutcome ? ' has-incomplete-outcome' : ''}`}
                  key={flow.nodeId}
                  onClick={() => setSelectedNodeId(flow.nodeId)}
                  type="button"
                >
                  <span>{index + 1}</span>
                  <div>
                    <strong>{node?.speaker || '내레이션'}</strong>
                    <small>{flow.outcomes.join(' · ')}</small>
                  </div>
                </button>
              );
            })}
          </div>
          {(dialogueFlow.unreachableCount > 0 || dialogueFlow.incompleteOutcomeCount > 0) && (
            <p>붉은 점은 시작 대화에서 갈 수 없거나 선택 후 결과가 없는 노드입니다. 눌러서 바로 수정하세요.</p>
          )}
        </section>
        <section
          className={`gss-dialogue-live-preview${scene.presentation === 'OVERLAY' ? ' is-overlay' : ''}`}
          style={backgroundVisual === null ? undefined : staticImageBackgroundStyle(backgroundVisual)}
        >
          {/* S15P21A604-512 — 원시 presentation 값 제거(정보 중복, 위 원인 참고). Overlay/
              Fullscreen 차이는 이 미리보기 카드 자체의 레이아웃(is-overlay 클래스)으로
              이미 시각적으로 드러난다. */}
          <span className="gss-preview-badge">LIVE PREVIEW</span>
          {portraitVisual !== null && (
            <div className="gss-dialogue-preview-portrait" style={staticImageBackgroundStyle(portraitVisual)} />
          )}
          <div className="gss-dialogue-preview-box">
            <strong>{previewSpeaker || '내레이션'}</strong>
            <p>{previewText}</p>
            {selectedNode.choices.length > 0 && (
              <div>
                {selectedNode.choices.map((choice, index) => (
                  <span key={choice.id}>{index + 1}. {choiceDrafts[choice.id] ?? choice.text}</span>
                ))}
              </div>
            )}
          </div>
        </section>
        <section className="gss-dialogue-node-card">
          {/* S15P21A604-512 — 내부 node id 노출 제거. 어떤 노드를 편집 중인지는 좌측 NODES
              rail의 speaker/text 요약과 START 배지로 이미 구분되고, id 자체는 사용자에게
              의미 없는 기술적 정보였다. */}
          <header><span>NODE</span></header>
          <div className="gss-field-row">
            <label className="gss-field">
              <span>연출 배경</span>
              <select
                onChange={(event) => onApply(setDialogueBackground(project, scene.id, event.target.value || undefined))}
                value={scene.backgroundAssetId ?? ''}
              >
                <option value="">배경 없음 / 게임 화면 유지</option>
                {project.assets.filter((asset) => isAssetForRole(asset, 'BACKGROUND')).map((asset) => <option key={asset.id} value={asset.id}>{assetDisplayLabel(asset)}</option>)}
              </select>
            </label>
            <label className="gss-field">
              <span>인물 이미지 / 표정</span>
              <select
                onChange={(event) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
                  ...node,
                  portraitAssetId: event.target.value || undefined,
                })))}
                value={selectedNode.portraitAssetId ?? ''}
              >
                <option value="">인물 없음</option>
                {project.assets.filter((asset) => isAssetForRole(asset, 'PORTRAIT')).map((asset) => <option key={asset.id} value={asset.id}>{assetDisplayLabel(asset)}</option>)}
              </select>
            </label>
          </div>
          <CommitInput
            label="말하는 인물"
            onCommit={(speaker) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
              ...node,
              speaker,
            })))}
            onDraftChange={setSpeakerDraft}
            value={selectedNode.speaker || '내레이션'}
          />
          <CommitInput
            label="대사"
            multiline
            onCommit={(text) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
              ...node,
              text,
            })))}
            onDraftChange={setTextDraft}
            value={selectedNode.text}
          />
        </section>

        <div className="gss-choice-heading">
          <div><span className="gss-eyebrow">CHOICES</span><strong>사용자 선택지</strong></div>
          <button
            disabled={selectedNode.choices.length >= 6}
            onClick={() => onApply(addDialogueChoice(project, scene.id, selectedNode.id))}
            type="button"
          >+ 선택지</button>
        </div>
        {selectedNode.choices.length === 0 && (
          <div className="gss-help-card"><strong>선택지가 없습니다</strong><p>자동 진행은 Runtime 정책 확정 전이므로 현재는 선택지를 하나 이상 추가하는 것을 권장합니다.</p></div>
        )}
        {selectedNode.choices.map((choice, index) => (
          <article className="gss-choice-card" key={choice.id}>
            <header><span>{index + 1}</span><strong>{choice.id}</strong><button
              aria-label="선택지 삭제"
              className="gss-icon-button"
              onClick={() => onApply(removeDialogueChoice(project, scene.id, selectedNode.id, choice.id))}
              type="button"
            >×</button></header>
            <CommitInput
              label="표시 문구"
              onCommit={(text) => onApply(updateDialogueChoice(
                project,
                scene.id,
                selectedNode.id,
                choice.id,
                (current) => ({ ...current, text }),
              ))}
              onDraftChange={(text) => setChoiceDrafts((prev) => ({ ...prev, [choice.id]: text }))}
              value={choice.text}
            />
            <label className="gss-field">
              <span>선택 후 결과</span>
              <select onChange={(event) => setOutcome(choice, event.target.value)} value={choiceOutcome(choice)}>
                {scene.nodes.filter((node) => node.id !== selectedNode.id).map((node) => (
                  <option key={node.id} value={`next:${node.id}`}>다음 대화 · {node.speaker}: {node.text}</option>
                ))}
                {scene.presentation === 'OVERLAY' ? (
                  <option value="close">대화 닫고 게임으로 복귀</option>
                ) : (
                  <>
                    {project.scenes.filter((candidate) => (
                      candidate.id !== scene.id && (candidate.type !== 'DIALOGUE' || candidate.presentation === 'FULL_SCREEN')
                    )).map((candidate) => (
                      <option key={candidate.id} value={`scene:${candidate.id}`}>Scene 이동 · {candidate.name}</option>
                    ))}
                    <option value="complete">게임 완료</option>
                  </>
                )}
              </select>
            </label>
          </article>
        ))}
      </div>
    </div>
  );
};
