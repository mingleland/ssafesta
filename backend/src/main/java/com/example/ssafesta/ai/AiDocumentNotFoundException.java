package com.example.ssafesta.ai;

import com.example.ssafesta.common.ApiException;
import com.example.ssafesta.common.ErrorCode;

/** 그 문서가 없다. */
class AiDocumentNotFoundException extends ApiException {

    AiDocumentNotFoundException(Long documentId) {
        super(ErrorCode.DOCUMENT_NOT_FOUND, "문서를 찾을 수 없습니다: " + documentId);
    }
}
