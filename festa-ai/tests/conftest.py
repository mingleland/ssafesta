import pytest


@pytest.fixture
def sample_document_bytes() -> bytes:
    return b"%PDF-1.7\n% SSAFY FESTA test fixture\n"
