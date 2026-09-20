import hashlib


class FakeEmbeddingProvider:
    def __init__(self, dimension: int = 1536, model_id: str = "fake-embedding-v1") -> None:
        self.dimension = dimension
        self.model_id = model_id
        self.calls: list[list[str]] = []

    async def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(list(texts))
        return [self._vector_for(text) for text in texts]

    def _vector_for(self, text: str) -> list[float]:
        digest = hashlib.sha256(text.encode("utf-8")).digest()
        return [digest[index % len(digest)] / 255 for index in range(self.dimension)]
