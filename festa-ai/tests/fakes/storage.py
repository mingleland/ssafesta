from collections.abc import Mapping
from dataclasses import dataclass, field


@dataclass
class StoredObject:
    body: bytes
    content_length: int
    metadata: dict[str, str] = field(default_factory=dict)


class FakeObjectStorage:
    def __init__(self, objects: Mapping[str, StoredObject] | None = None) -> None:
        self._objects: dict[str, StoredObject] = dict(objects or {})

    def put_object(
        self,
        object_key: str,
        body: bytes,
        metadata: Mapping[str, str] | None = None,
    ) -> None:
        self._objects[object_key] = StoredObject(
            body=body,
            content_length=len(body),
            metadata=dict(metadata or {}),
        )

    def get_object(self, object_key: str) -> bytes:
        return self._objects[object_key].body

    def head_object(self, object_key: str) -> StoredObject:
        return self._objects[object_key]
