import { useEffect, useState } from 'react';
import type { DialogueChoice, DialogueScene, GameProject } from '../../contracts/gameProject.ts';
import {
  addDialogueChoice,
  addDialogueNode,
  removeDialogueChoice,
  renameScene,
  setDialogueBackground,
  updateDialogueChoice,
  updateDialogueNode,
} from '../model/authoringCommands.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { assetDisplayLabel, isAssetForRole } from '../assets/builtinAssetCatalog.ts';
import { CommitInput } from './CommitInput.tsx';

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
  const selectedNode = scene.nodes.find((node) => node.id === selectedNodeId) ?? scene.nodes[0];

  if (selectedNode === undefined) return null;
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
        {scene.nodes.map((node, index) => (
          <button
            className={`gss-node-button${node.id === selectedNode.id ? ' is-active' : ''}`}
            key={node.id}
            onClick={() => setSelectedNodeId(node.id)}
            type="button"
          >
            <span>{index + 1}</span>
            <div><strong>{node.speaker || '내레이션'}</strong><small>{node.text}</small></div>
            {node.id === scene.startNodeId && <em>START</em>}
          </button>
        ))}
      </aside>

      <div className="gss-dialogue-editor">
        <div className="gss-dialogue-scene-header">
          <div>
            <span className="gss-eyebrow">DIALOGUE SCENE</span>
            <CommitInput
              label="Scene 이름"
              onCommit={(name) => onApply(renameScene(project, scene.id, name))}
              value={scene.name}
            />
          </div>
          <span className="gss-type-badge">{scene.presentation}</span>
        </div>
        <section
          className={`gss-dialogue-live-preview${scene.presentation === 'OVERLAY' ? ' is-overlay' : ''}`}
          style={backgroundVisual === null ? undefined : staticImageBackgroundStyle(backgroundVisual)}
        >
          <span className="gss-preview-badge">LIVE PREVIEW · {scene.presentation}</span>
          {portraitVisual !== null && (
            <div className="gss-dialogue-preview-portrait" style={staticImageBackgroundStyle(portraitVisual)} />
          )}
          <div className="gss-dialogue-preview-box">
            <strong>{selectedNode.speaker || '내레이션'}</strong>
            <p>{selectedNode.text}</p>
            {selectedNode.choices.length > 0 && <div>{selectedNode.choices.map((choice, index) => <span key={choice.id}>{index + 1}. {choice.text}</span>)}</div>}
          </div>
        </section>
        <section className="gss-dialogue-node-card">
          <header><span>NODE</span><strong>{selectedNode.id}</strong></header>
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
            value={selectedNode.speaker || '내레이션'}
          />
          <CommitInput
            label="대사"
            multiline
            onCommit={(text) => onApply(updateDialogueNode(project, scene.id, selectedNode.id, (node) => ({
              ...node,
              text,
            })))}
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
