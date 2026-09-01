from dataclasses import dataclass

from app.providers.storage import ObjectMetadata, ObjectNotFoundError


@dataclass
class StoredObject:
    body: bytes
    content_type: str | None = None


class FakeObjectStorage:
    def __init__(self, objects: dict[str, StoredObject] | None = None) -> None:
        self._objects: dict[str, StoredObject] = dict(objects or {})

    def put_object(
        self, object_key: str, body: bytes, *, content_type: str | None = None
    ) -> None:
        self._objects[object_key] = StoredObject(body=body, content_type=content_type)

    def head_object(self, object_key: str) -> ObjectMetadata:
        stored = self._objects.get(object_key)
        if stored is None:
            raise ObjectNotFoundError(object_key)
        return ObjectMetadata(
            content_length=len(stored.body), content_type=stored.content_type
        )

    def get_object(self, object_key: str) -> bytes:
        stored = self._objects.get(object_key)
        if stored is None:
            raise ObjectNotFoundError(object_key)
        return stored.body
