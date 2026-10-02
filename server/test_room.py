"""The box keeps four people, and it really deletes what they drop."""

from __future__ import annotations

import asyncio
import base64
import json
import socket
import struct
import threading
import time
import uuid
import zlib
from dataclasses import dataclass
from pathlib import Path

import httpx
import pytest
import uvicorn
import websockets

from app import (
    ROOM_FULL,
    Suggestion,
    app,
    prepare_upload_dir,
    room,
    sniff,
)


@dataclass
class Service:
    base: str
    upload_dir: Path

    @property
    def ws(self) -> str:
        return "ws://" + self.base.removeprefix("http://") + "/ws"


class QuietServer(uvicorn.Server):
    def install_signal_handlers(self) -> None:
        return


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def tiny_png() -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    scan = zlib.compress(b"\x00\xff\x00\x00")
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", scan) + chunk(b"IEND", b"")


def tiny_gif() -> bytes:
    # Two-frame GIF89a. The server treats every GIF header as a GIF; the phone plays frames.
    return (
        b"GIF89a"
        b"\x01\x00\x01\x00\x80\x00\x00"
        b"\xff\xff\xff\x00\x00\x00"
        b"!\xf9\x04\x08\x0a\x00\x00\x00"
        b",\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00"
        b"!\xf9\x04\x08\x0a\x00\x00\x00"
        b",\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x02D\x01\x00"
        b";"
    )


@pytest.fixture(scope="module")
def service(tmp_path_factory):
    upload = tmp_path_factory.mktemp("uploads")
    room.upload_dir = upload
    room.suggestions.clear()
    room.clients.clear()
    port = free_port()
    server = QuietServer(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning"))
    thread = threading.Thread(target=lambda: asyncio.run(server.serve()), name="box-test", daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"
    deadline = time.monotonic() + 8
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            response = httpx.get(base + "/health", timeout=0.3)
            if response.status_code == 200:
                break
        except httpx.HTTPError as exc:
            last_error = exc
        time.sleep(0.05)
    else:
        raise RuntimeError(f"server did not start: {last_error}")
    yield Service(base, upload)
    server.should_exit = True
    thread.join(timeout=5)


def test_prepare_wipes_existing_files(tmp_path: Path) -> None:
    leftover = tmp_path / "old.gif"
    leftover.write_bytes(b"GIF89a leftover")
    nested = tmp_path / "nested"
    nested.mkdir()
    (nested / "note.png").write_bytes(b"png")
    prepare_upload_dir(tmp_path)
    assert list(tmp_path.iterdir()) == []


def test_sniff_and_deletion_is_not_a_flag() -> None:
    assert sniff(b"GIF89ahello")[0] == "gif"
    assert sniff(tiny_png()) == ("image", "image/png", "png")
    assert sniff(b"\xff\xd8\xff\xe0rest")[0] == "image"
    webp = b"RIFF" + b"\x00\x00\x00\x00" + b"WEBP" + b"body"
    assert sniff(webp)[0] == "image"
    assert sniff(b"hello there") is None
    assert "deleted" not in Suggestion.__dataclass_fields__


def test_health(service: Service) -> None:
    response = httpx.get(service.base + "/health", timeout=2)
    assert response.status_code == 200
    assert response.json()["ok"] is True


def test_phone_can_fetch_a_newer_package(service: Service) -> None:
    version = httpx.get(service.base + "/app/version", timeout=5)
    assert version.status_code == 200
    assert "no-store" in version.headers["cache-control"]
    assert version.json()["versionCode"] >= 2
    package = httpx.get(service.base + "/app/suggestion-box.apk", timeout=30)
    assert package.status_code == 200
    assert package.content[:2] == b"PK"
    assert "android.package-archive" in package.headers["content-type"]
    assert "no-store" in package.headers["cache-control"]


def test_page_is_fresh_html(service: Service) -> None:
    response = httpx.get(service.base + "/", timeout=2)
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    assert "no-store" in response.headers["cache-control"]
    body = response.text
    assert "Type your suggestion" in body
    assert 'id="draft"' in body
    assert "contenteditable" in body
    assert "interactive-widget=resizes-content" in body
    assert "visualViewport" in body
    assert "Math.min(window.innerHeight, view.height)" in body
    assert "overlaysContent = false" in body
    assert "translateY" not in body
    assert "scrollIntoView" not in body
    assert "offsetTop" not in body
    assert "Opening the box" not in body
    assert "APPLICATION LOADING" not in body
    assert "SERVICE WAKING UP" not in body
    assert "WELCOME TO RENDER" not in body
    assert "localStorage" not in body
    assert "indexedDB" not in body
    assert "sessionStorage" not in body


def test_android_installs_an_app(service: Service) -> None:
    page = httpx.get(service.base + "/", timeout=2)
    assert page.status_code == 200
    assert 'rel="manifest"' in page.text
    assert "Install app" in page.text
    assert "beforeinstallprompt" in page.text
    assert "/sw.js" in page.text
    assert 'name="application-name"' in page.text

    manifest = httpx.get(service.base + "/manifest.webmanifest", timeout=2)
    assert manifest.status_code == 200
    assert "manifest" in manifest.headers["content-type"]
    assert "no-store" in manifest.headers["cache-control"]
    body = manifest.json()
    assert body["display"] == "standalone"
    assert body["orientation"] == "portrait-primary"
    assert body["start_url"] == "/"
    assert body["scope"] == "/"
    assert body["short_name"] == "Suggest"
    purposes = {icon["purpose"] for icon in body["icons"]}
    assert "any" in purposes
    assert "maskable" in purposes

    worker = httpx.get(service.base + "/sw.js", timeout=2)
    assert worker.status_code == 200
    assert "javascript" in worker.headers["content-type"]
    assert "no-store" in worker.headers["cache-control"]
    assert "respondWith" in worker.text
    assert "SERVICE WAKING UP" in worker.text
    assert "caches.put" not in worker.text
    assert "caches.add" not in worker.text
    assert "/media" in worker.text

    for path in (
        "/icons/icon-192.png",
        "/icons/icon-512.png",
        "/icons/icon-192-maskable.png",
        "/icons/icon-512-maskable.png",
    ):
        icon = httpx.get(service.base + path, timeout=2)
        assert icon.status_code == 200
        assert icon.headers["content-type"].startswith("image/png")
        assert icon.content.startswith(b"\x89PNG")
        assert "no-store" in icon.headers["cache-control"]


def test_four_people_and_many_suggestions(service: Service) -> None:
    asyncio.run(_limits(service))


async def _limits(service: Service) -> None:
    people = [await websockets.connect(service.ws) for _ in range(4)]
    fifth = await websockets.connect(service.ws)
    try:
        welcomes = [json.loads(await asyncio.wait_for(sock.recv(), 2)) for sock in people]
        rejected = json.loads(await asyncio.wait_for(fifth.recv(), 2))
        assert all(item["type"] == "welcome" for item in welcomes)
        assert welcomes[-1]["occupancy"] == 4
        assert rejected == {"type": "rejected", "message": ROOM_FULL}
        await asyncio.sleep(0.05)
        assert fifth.close_code == 1008

        # Presence notices for the later arrivals are not suggestions.
        await _drain(people[0])

        suggestion_id = str(uuid.uuid4())
        await people[0].send(json.dumps({"type": "text", "id": suggestion_id, "text": "Add a window seat"}))
        delivered = await _next_suggestion(people[1])
        assert delivered["id"] == suggestion_id
        assert delivered["sender"] == welcomes[0]["you"]
        assert delivered["kind"] == "text"
        assert delivered["text"] == "Add a window seat"
        assert suggestion_id in room.suggestions

        second_id = str(uuid.uuid4())
        await people[0].send(json.dumps({"type": "text", "id": second_id, "text": "And a lamp"}))
        again = await _next_suggestion(people[1])
        assert again["id"] == second_id
        assert again["text"] == "And a lamp"
        held = [item.id for item in room.suggestions.values() if item.sender_id == welcomes[0]["you"]]
        assert held == [suggestion_id, second_id]
    finally:
        await _close(*people, fifth)

    # A free slot can be taken after someone leaves.
    again = await websockets.connect(service.ws)
    try:
        welcome = json.loads(await asyncio.wait_for(again.recv(), 2))
        assert welcome["type"] == "welcome"
    finally:
        await _close(again)


def test_stranger_and_non_image_are_refused(service: Service) -> None:
    asyncio.run(_refused(service))


async def _refused(service: Service) -> None:
    before = set(service.upload_dir.iterdir())
    stranger = httpx.post(
        service.base + "/media",
        data={"id": str(uuid.uuid4())},
        files={"file": ("note.png", tiny_png(), "image/png")},
        timeout=2,
    )
    assert stranger.status_code == 403
    assert set(service.upload_dir.iterdir()) == before

    socket = await websockets.connect(service.ws)
    try:
        welcome = json.loads(await asyncio.wait_for(socket.recv(), 2))
        refused = httpx.post(
            service.base + "/media",
            headers={"X-Client-Id": welcome["you"]},
            data={"id": str(uuid.uuid4())},
            files={"file": ("note.txt", b"not an image", "text/plain")},
            timeout=2,
        )
        assert refused.status_code == 415
        assert set(service.upload_dir.iterdir()) == before
        missing = httpx.get(service.base + "/media/" + str(uuid.uuid4()), timeout=2)
        assert missing.status_code == 404
    finally:
        await _close(socket)


def test_suggestions_and_files_are_deleted_after_three_seconds(service: Service) -> None:
    asyncio.run(_deleted(service))
    root = Path(__file__).resolve().parent
    leftovers = [
        path
        for path in root.rglob("*")
        if path.is_file() and path.suffix in {".db", ".sqlite", ".sqlite3"}
    ]
    assert leftovers == []


async def _deleted(service: Service) -> None:
    sender = await websockets.connect(service.ws)
    receiver = await websockets.connect(service.ws)
    try:
        welcome = json.loads(await asyncio.wait_for(sender.recv(), 2))
        receiver_welcome = json.loads(await asyncio.wait_for(receiver.recv(), 2))
        await _drain(sender)
        await _drain(receiver)
        client_id = welcome["you"]
        receiver_id = receiver_welcome["you"]

        text_id = str(uuid.uuid4())
        text_started = time.monotonic()
        await sender.send(json.dumps({"type": "text", "id": text_id, "text": "Softer light"}))

        png_id = str(uuid.uuid4())
        png = tiny_png()
        png_started = time.monotonic()
        png_response = httpx.post(
            service.base + "/media",
            headers={"X-Client-Id": client_id},
            data={"id": png_id},
            files={"file": ("seat.png", png, "image/png")},
            timeout=2,
        )
        assert png_response.status_code == 201
        png_path = next(service.upload_dir.glob(f"{png_id}.*"))
        assert png_path.read_bytes() == png

        text_message = await _next_suggestion(receiver)
        assert text_message["text"] == "Softer light"
        png_message = await _next_suggestion(receiver)
        assert png_message["id"] == png_id
        assert png_message["kind"] == "image"
        with pytest.raises(asyncio.TimeoutError):
            await asyncio.wait_for(sender.recv(), 0.2)

        await _wait_until_gone(text_id, None, text_started)
        await _wait_until_gone(png_id, png_path, png_started)
        assert not any(service.upload_dir.iterdir())

        gif_id = str(uuid.uuid4())
        gif = tiny_gif()
        gif_started = time.monotonic()
        gif_response = httpx.post(
            service.base + "/media",
            headers={"X-Client-Id": client_id},
            data={"id": gif_id},
            files={"file": ("spark.gif", gif, "image/gif")},
            timeout=2,
        )
        assert gif_response.status_code == 201
        assert gif_response.json()["kind"] == "gif"
        stored = list(service.upload_dir.glob(f"{gif_id}.*"))
        assert len(stored) == 1
        assert stored[0].read_bytes() == gif
        assert stored[0].is_file()
        fetched = httpx.get(service.base + f"/media/{gif_id}", timeout=2)
        assert fetched.status_code == 200
        assert fetched.content == gif
        assert fetched.headers["cache-control"] == "no-store"

        gif_message = await _next_suggestion(receiver)
        assert gif_message["kind"] == "gif"
        assert base64.b64decode(gif_message["data"]) == gif

        png_id = str(uuid.uuid4())
        png = tiny_png()
        png_started = time.monotonic()
        png_response = httpx.post(
            service.base + "/media",
            headers={"X-Client-Id": receiver_id},
            data={"id": png_id},
            files={"file": ("seat.png", png, "image/png")},
            timeout=2,
        )
        assert png_response.status_code == 201
        assert png_response.json()["kind"] == "image"
        png_path = next(service.upload_dir.glob(f"{png_id}.*"))
        assert png_path.read_bytes() == png
        png_message = await _next_suggestion(sender)
        assert png_message["kind"] == "image"
        assert base64.b64decode(png_message["data"]) == png

        await _wait_until_gone(gif_id, stored[0], gif_started)
        await _wait_until_gone(png_id, png_path, png_started)
        assert httpx.get(service.base + f"/media/{gif_id}", timeout=2).status_code == 404
        assert httpx.get(service.base + f"/media/{png_id}", timeout=2).status_code == 404
        assert gif_id not in room.suggestions
        assert png_id not in room.suggestions
        assert text_id not in room.suggestions
        assert list(service.upload_dir.iterdir()) == []

        # After it vanishes, the same person can drop another. It is not a history thread.
        again_id = str(uuid.uuid4())
        again_started = time.monotonic()
        await sender.send(json.dumps({"type": "text", "id": again_id, "text": "One more, then it's gone"}))
        again = await _next_suggestion(receiver)
        assert again["text"] == "One more, then it's gone"
        assert again_id in room.suggestions
        await _wait_until_gone(again_id, None, again_started)
        assert again_id not in room.suggestions
    finally:
        await _close(sender, receiver)


async def _wait_until_gone(suggestion_id: str, path: Path | None, started: float) -> None:
    deadline = started + 6
    while time.monotonic() < deadline:
        gone = suggestion_id not in room.suggestions and (path is None or not path.exists())
        if gone:
            elapsed = time.monotonic() - started
            assert elapsed >= 2.8, f"deleted too soon ({elapsed:.2f}s)"
            return
        await asyncio.sleep(0.05)
    pytest.fail(f"{suggestion_id} was still stored")


async def _next_suggestion(socket, timeout: float = 2) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        remaining = max(0.05, deadline - time.monotonic())
        raw = await asyncio.wait_for(socket.recv(), remaining)
        payload = json.loads(raw)
        if payload.get("type") == "suggestion":
            return payload
    raise asyncio.TimeoutError


async def _drain(socket, timeout: float = 0.2) -> None:
    while True:
        try:
            await asyncio.wait_for(socket.recv(), timeout)
        except asyncio.TimeoutError:
            return


async def _close(*sockets) -> None:
    for socket in sockets:
        try:
            await socket.close()
        except Exception:
            pass
