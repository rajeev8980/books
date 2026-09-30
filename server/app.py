"""A suggestion box for exactly two people.

Text, images, and GIFs live in memory and on disk only until they are deleted.
Nothing is kept behind a hidden flag.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import uvicorn
from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse

VANISH_SECONDS = 3.0
MAX_MEDIA_BYTES = 8 * 1024 * 1024
MAX_TEXT = 2000
ROOM_FULL = "This room already has two people."
ALREADY_IN_BOX = "You already have a suggestion in the box."
HOST = "0.0.0.0"
PORT = 43123

log = logging.getLogger("box")
ROOT = Path(__file__).resolve().parent
DEFAULT_UPLOAD_DIR = ROOT / "data" / "uploads"


class AlreadyInBox(Exception):
    pass


def prepare_upload_dir(path: Path) -> None:
    """Create the upload directory and delete anything already in it."""
    path.mkdir(parents=True, exist_ok=True)
    for child in list(path.iterdir()):
        if child.is_file() or child.is_symlink():
            child.unlink()
        elif child.is_dir():
            for nested in sorted(child.rglob("*"), reverse=True):
                if nested.is_file() or nested.is_symlink():
                    nested.unlink()
                elif nested.is_dir():
                    nested.rmdir()
            child.rmdir()


def sniff(data: bytes) -> tuple[str, str, str] | None:
    """Return (kind, mime, extension) or None. Extension has no dot."""
    if data.startswith(b"GIF87a") or data.startswith(b"GIF89a"):
        return ("gif", "image/gif", "gif")
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ("image", "image/png", "png")
    if data.startswith(b"\xff\xd8\xff"):
        return ("image", "image/jpeg", "jpg")
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return ("image", "image/webp", "webp")
    return None


def as_uuid(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        return str(uuid.UUID(value))
    except ValueError:
        return None


@dataclass
class Suggestion:
    id: str
    sender_id: str
    kind: str
    text: str | None
    path: Path | None
    mime: str | None


class Room:
    def __init__(self, upload_dir: Path) -> None:
        self.upload_dir = upload_dir
        self.clients: dict[str, WebSocket] = {}
        self.suggestions: dict[str, Suggestion] = {}
        self.lock = asyncio.Lock()
        self.tasks: set[asyncio.Task] = set()

    def schedule(self, coro) -> None:
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def broadcast(self, payload: dict, exclude: str | None = None) -> None:
        async with self.lock:
            targets = [(cid, ws) for cid, ws in self.clients.items() if cid != exclude]
        dead: list[str] = []
        for cid, ws in targets:
            try:
                await ws.send_json(payload)
            except Exception:
                log.info("closed client %s", cid)
                dead.append(cid)
        for cid in dead:
            await self.disconnect(cid)

    async def disconnect(self, client_id: str) -> None:
        async with self.lock:
            removed = self.clients.pop(client_id, None)
            occupancy = len(self.clients)
        if removed is None:
            return
        log.info("left occupancy %s", occupancy)
        await self.broadcast({"type": "presence", "event": "left", "occupancy": occupancy})

    def _sender_is_holding(self, sender_id: str) -> bool:
        return any(item.sender_id == sender_id for item in self.suggestions.values())

    async def accept_text(self, sender_id: str, suggestion_id: str, text: str, deadline: float) -> None:
        async with self.lock:
            if sender_id not in self.clients:
                return
            if self._sender_is_holding(sender_id):
                raise AlreadyInBox()
            if suggestion_id in self.suggestions:
                return
            self.suggestions[suggestion_id] = Suggestion(
                id=suggestion_id,
                sender_id=sender_id,
                kind="text",
                text=text,
                path=None,
                mime=None,
            )
        await self.broadcast(
            {
                "type": "suggestion",
                "id": suggestion_id,
                "sender": sender_id,
                "kind": "text",
                "text": text,
                "sentAt": int(time.time() * 1000),
            },
            exclude=sender_id,
        )
        self.schedule(self.expire(suggestion_id, deadline))

    async def accept_media(
        self,
        sender_id: str,
        suggestion_id: str,
        data: bytes,
        deadline: float,
    ) -> Suggestion:
        sniffed = sniff(data)
        if sniffed is None:
            raise HTTPException(status_code=415, detail="Use a JPEG, PNG, WEBP, or GIF.")
        kind, mime, ext = sniffed
        async with self.lock:
            if sender_id not in self.clients:
                raise HTTPException(status_code=403, detail="Only the two people in the box can drop something in.")
            if self._sender_is_holding(sender_id) or suggestion_id in self.suggestions:
                raise AlreadyInBox()
        path = self.path_for(suggestion_id, ext)
        path.write_bytes(data)
        suggestion = Suggestion(
            id=suggestion_id,
            sender_id=sender_id,
            kind=kind,
            text=None,
            path=path,
            mime=mime,
        )
        async with self.lock:
            if sender_id not in self.clients or self._sender_is_holding(sender_id) or suggestion_id in self.suggestions:
                path.unlink(missing_ok=True)
                if sender_id not in self.clients:
                    raise HTTPException(
                        status_code=403,
                        detail="Only the two people in the box can drop something in.",
                    )
                raise AlreadyInBox()
            self.suggestions[suggestion_id] = suggestion
        encoded = base64.b64encode(data).decode("ascii")
        await self.broadcast(
            {
                "type": "suggestion",
                "id": suggestion_id,
                "sender": sender_id,
                "kind": kind,
                "mime": mime,
                "data": encoded,
                "sentAt": int(time.time() * 1000),
            },
            exclude=sender_id,
        )
        self.schedule(self.expire(suggestion_id, deadline))
        return suggestion

    def path_for(self, suggestion_id: str, ext: str) -> Path:
        uid = str(uuid.UUID(suggestion_id))
        root = self.upload_dir.resolve()
        path = (root / f"{uid}.{ext}").resolve()
        if path.parent != root:
            raise HTTPException(status_code=400, detail="Bad suggestion id.")
        return path

    async def expire(self, suggestion_id: str, deadline: float) -> None:
        delay = deadline - time.monotonic()
        if delay > 0:
            await asyncio.sleep(delay)
        async with self.lock:
            suggestion = self.suggestions.pop(suggestion_id, None)
        if suggestion is None:
            return
        if suggestion.path is not None:
            try:
                suggestion.path.unlink(missing_ok=True)
            except OSError:
                log.exception("could not delete file for %s", suggestion_id)
        log.info("deleted suggestion %s", suggestion_id)

    async def shutdown(self) -> None:
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        async with self.lock:
            leftover = list(self.suggestions.values())
            self.suggestions.clear()
            self.clients.clear()
        for suggestion in leftover:
            if suggestion.path is not None:
                try:
                    suggestion.path.unlink(missing_ok=True)
                except OSError:
                    log.exception("could not delete file for %s", suggestion.id)


room = Room(DEFAULT_UPLOAD_DIR)


@asynccontextmanager
async def lifespan(_: FastAPI):
    prepare_upload_dir(room.upload_dir)
    try:
        yield
    finally:
        await room.shutdown()


app = FastAPI(lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    async with room.lock:
        occupancy = len(room.clients)
    return {"ok": True, "occupancy": occupancy}


@app.get("/media/{suggestion_id}")
async def get_media(suggestion_id: str):
    uid = as_uuid(suggestion_id)
    if uid is None:
        raise HTTPException(status_code=404)
    async with room.lock:
        suggestion = room.suggestions.get(uid)
        path = suggestion.path if suggestion is not None else None
        mime = suggestion.mime if suggestion is not None else None
    if path is None or not path.is_file():
        raise HTTPException(status_code=404)
    return FileResponse(path, media_type=mime or "application/octet-stream", headers={"Cache-Control": "no-store"})


@app.post("/media", status_code=201)
async def upload_media(
    file: UploadFile = File(...),
    id: str = Form(...),
    x_client_id: str | None = Header(default=None),
):
    deadline = time.monotonic() + VANISH_SECONDS
    if not x_client_id:
        raise HTTPException(status_code=403, detail="Only the two people in the box can drop something in.")
    sender = as_uuid(x_client_id)
    suggestion_id = as_uuid(id)
    if sender is None or suggestion_id is None:
        raise HTTPException(status_code=400, detail="Suggestion id must be a UUID.")
    async with room.lock:
        if sender not in room.clients:
            raise HTTPException(status_code=403, detail="Only the two people in the box can drop something in.")
    raw = await file.read(MAX_MEDIA_BYTES + 1)
    if len(raw) > MAX_MEDIA_BYTES:
        raise HTTPException(status_code=413, detail="Images and GIFs need to be under 8 MB.")
    if not raw:
        raise HTTPException(status_code=400, detail="That file is empty.")
    try:
        suggestion = await room.accept_media(sender, suggestion_id, raw, deadline)
    except AlreadyInBox:
        raise HTTPException(status_code=409, detail=ALREADY_IN_BOX) from None
    return {"id": suggestion.id, "kind": suggestion.kind, "mime": suggestion.mime}


@app.websocket("/ws")
async def socket_endpoint(websocket: WebSocket) -> None:
    await websocket.accept()
    client_id: str | None = None
    try:
        async with room.lock:
            if len(room.clients) >= 2:
                full = True
                occupancy = len(room.clients)
            else:
                full = False
                client_id = str(uuid.uuid4())
                room.clients[client_id] = websocket
                occupancy = len(room.clients)
        if full:
            await websocket.send_json({"type": "rejected", "message": ROOM_FULL})
            await websocket.close(code=1008, reason=ROOM_FULL)
            return
        assert client_id is not None
        log.info("joined occupancy %s", occupancy)
        await websocket.send_json({"type": "welcome", "you": client_id, "occupancy": occupancy})
        await room.broadcast(
            {"type": "presence", "event": "joined", "occupancy": occupancy},
            exclude=client_id,
        )
        while True:
            raw = await websocket.receive_text()
            await handle_client_message(client_id, websocket, raw)
    except WebSocketDisconnect:
        pass
    finally:
        if client_id is not None:
            await room.disconnect(client_id)


async def handle_client_message(client_id: str, websocket: WebSocket, raw: str) -> None:
    deadline = time.monotonic() + VANISH_SECONDS
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        await websocket.send_json({"type": "error", "message": "That wasn't a suggestion."})
        return
    if not isinstance(payload, dict) or payload.get("type") != "text":
        await websocket.send_json({"type": "error", "message": "Drop text, or upload a photo or GIF."})
        return
    text = payload.get("text")
    if not isinstance(text, str) or not text.strip():
        await websocket.send_json({"type": "error", "message": "Write a suggestion first."})
        return
    text = text.strip()
    if len(text) > MAX_TEXT:
        await websocket.send_json({"type": "error", "message": "Keep it under 2000 characters."})
        return
    suggestion_id = as_uuid(payload.get("id"))
    if suggestion_id is None:
        await websocket.send_json({"type": "error", "message": "Suggestion id must be a UUID."})
        return
    try:
        await room.accept_text(client_id, suggestion_id, text, deadline)
    except AlreadyInBox:
        await websocket.send_json({"type": "error", "message": ALREADY_IN_BOX})


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    uvicorn.run(
        app,
        host=HOST,
        port=PORT,
        log_level="info",
        ws_max_size=16 * 1024 * 1024,
    )


if __name__ == "__main__":
    main()
