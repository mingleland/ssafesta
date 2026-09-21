// 피드백 제출 — ESC 메뉴의 자식 화면 (S15P21A604-953). 관리·최초 발견 보상은 관리자 콘솔이 맡는다.
import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { feedbackApi } from '../../../entities/feedback/api.select';
import { isApiError } from '../../../shared/api/client';
import { OverlayFrame } from '../../overlay/ui/OverlayFrame';
import { showToast } from '../../../shared/ui/toast/toastStore';
import './feedback.css';

const MAX_LENGTH = 2000;

export function FeedbackOverlay({ onClose }: { onClose: () => void }) {
  const [content, setContent] = useState('');

  const submit = useMutation({
    mutationFn: () => feedbackApi.submit(content),
    onSuccess: () => {
      showToast('피드백을 보냈습니다. 감사합니다!', 'success');
      onClose();
    },
  });

  const trimmed = content.trim();
  const blocked = trimmed === '' || trimmed.length > MAX_LENGTH;
  const banner = submit.isError
    ? (isApiError(submit.error) ? submit.error.message : '피드백을 보내지 못했습니다. 잠시 후 다시 시도해 주세요.')
    : null;

  return (
    <OverlayFrame title="피드백" subtitle="버그·건의사항을 알려주세요" size="s" onClose={onClose}>
      <div className="fb-form">
        <textarea
          className="fb-textarea"
          value={content}
          maxLength={MAX_LENGTH + 200}
          rows={6}
          placeholder="무엇이 이상했나요? 어디서 발생했는지 적어주시면 더 빨리 확인할 수 있어요."
          onChange={(e) => setContent(e.target.value)}
          disabled={submit.isPending}
        />
        <div className="fb-footer">
          <span className="fb-count">{trimmed.length}/{MAX_LENGTH}</span>
          {banner !== null && <p className="ov-alert" role="alert">{banner}</p>}
          <button
            type="button"
            className="ov-btn ov-btn-primary"
            disabled={blocked || submit.isPending}
            onClick={() => submit.mutate()}
          >
            {submit.isPending ? '보내는 중...' : '보내기'}
          </button>
        </div>
      </div>
    </OverlayFrame>
  );
}
