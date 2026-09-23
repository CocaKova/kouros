"""Kouros Bridge — optional ComfyUI routes the stock server lacks, for the Kouros app.

GET  /kouros/bridge          what this bridge offers (the app probes it; absent = stock server)
POST /kouros/outputs/delete  delete generated files: {"files": [{"filename", "subfolder", "type"}]}
GET  /kouros/memory          memory the OS can still hand out, and the models ComfyUI holds
POST /kouros/models/download fetch a model into a model folder: {"url", "directory", "name"}
GET  /kouros/models/downloads  progress of every download since the server started
POST /kouros/models/downloads/{id}/cancel

Deletion is confined to the output and temp folders, plus the input folder's "kouros"
subfolder (where the app uploads). Paths are resolved and checked to stay inside their
folder; only regular files are removed. No node classes: it only adds routes.

Downloads take https URLs only, into a folder ComfyUI itself defines (checkpoints, vae,
loras…), under a plain file name. They stream to "<name>.part" and are renamed when complete,
so a half-written file is never offered as a model. A download that would leave less than
5 GB free is refused. Set HF_TOKEN in ComfyUI's environment for gated Hugging Face files.
"""
import asyncio
import logging
import os
import re
import shutil
import time
import uuid

from aiohttp import web

import folder_paths
from server import PromptServer

VERSION = 2
NODE_CLASS_MAPPINGS = {}
NODE_DISPLAY_NAME_MAPPINGS = {}

routes = PromptServer.instance.routes


def _target(entry):
    """The on-disk path for one {"filename","subfolder","type"}, or None when it isn't allowed."""
    kind = entry.get("type") or "output"
    name = entry.get("filename") or ""
    sub = entry.get("subfolder") or ""
    if kind not in ("output", "temp", "input") or not name or "/" in name or "\\" in name:
        return None
    if kind == "input" and not (sub == "kouros" or sub.startswith("kouros/")):
        return None
    base = folder_paths.get_directory_by_type(kind)
    if base is None:
        return None
    base = os.path.realpath(base)
    path = os.path.realpath(os.path.join(base, sub, name))
    if os.path.commonpath([base, path]) != base or path == base:
        return None
    return path


@routes.get("/kouros/bridge")
async def bridge_info(request):
    return web.json_response({"name": "kouros-bridge", "version": VERSION, "features": ["outputs.delete", "memory", "models.download"]})


@routes.post("/kouros/outputs/delete")
async def delete_outputs(request):
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "expected JSON"}, status=400)
    files = body.get("files") if isinstance(body, dict) else None
    if not isinstance(files, list) or len(files) > 1000:
        return web.json_response({"error": "files must be a list of at most 1000"}, status=400)
    deleted, missing, refused = [], [], []
    for entry in files:
        if not isinstance(entry, dict):
            continue
        label = {k: entry.get(k) for k in ("filename", "subfolder", "type")}
        path = _target(entry)
        if path is None:
            refused.append(label)
        elif not os.path.isfile(path):
            missing.append(label)
        else:
            try:
                os.remove(path)
                deleted.append(label)
            except OSError as e:
                logging.warning("kouros-bridge: could not delete %s: %s", path, e)
                refused.append(label)
    return web.json_response({"deleted": deleted, "missing": missing, "refused": refused})


def _meminfo():
    out = {}
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                k, v = line.split(":", 1)
                if k in ("MemTotal", "MemAvailable", "SwapTotal", "SwapFree"):
                    out[k] = int(v.strip().split()[0]) * 1024
    except OSError:
        pass
    return out


@routes.get("/kouros/memory")
async def memory(request):
    info = _meminfo()
    models = []
    try:
        import comfy.model_management as mm
        for lm in list(mm.current_loaded_models):
            m = lm.model
            if m is None:
                continue
            inner = getattr(m, "model", None)
            name = type(inner).__name__ if inner is not None else type(m).__name__
            size = 0
            try:
                size = int(lm.model_memory())
            except Exception:
                pass
            loaded = 0
            try:
                loaded = int(lm.model_loaded_memory())
            except Exception:
                pass
            models.append({"name": name, "size": size, "loaded": loaded})
    except Exception as e:
        logging.debug("kouros-bridge: loaded-model listing failed: %s", e)
    return web.json_response({
        "total": info.get("MemTotal"),
        "available": info.get("MemAvailable"),
        "swap_total": info.get("SwapTotal"),
        "swap_free": info.get("SwapFree"),
        "models": models,
    })


# ── model downloads ─────────────────────────────────────────────────────────────

_downloads = {}          # id → state dict (what the app sees)
_tasks = {}              # id → asyncio.Task
_slots = asyncio.Semaphore(2)
_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._ +()\[\]-]{0,200}$")
_KEEP_FREE = 5 * 1024 ** 3


def _folder(directory):
    """The first on-disk path of a model folder ComfyUI knows, or None."""
    if directory not in getattr(folder_paths, "folder_names_and_paths", {}) or directory in ("custom_nodes", "configs"):
        return None
    paths = folder_paths.get_folder_paths(directory)
    return paths[0] if paths else None


def _public(d):
    return {k: d.get(k) for k in ("id", "name", "directory", "state", "done", "total", "error")}


async def _fetch(job, url, dest):
    import aiohttp
    part = dest + ".part"
    headers = {}
    token = os.environ.get("HF_TOKEN")
    if token and re.match(r"^https://([a-z0-9-]+\.)*huggingface\.co/", url):
        headers["Authorization"] = "Bearer " + token
    async with _slots:
        try:
            timeout = aiohttp.ClientTimeout(total=None, sock_connect=30, sock_read=120)
            async with aiohttp.ClientSession(timeout=timeout) as session:
                async with session.get(url, headers=headers) as r:
                    if r.status != 200:
                        raise RuntimeError(f"HTTP {r.status}")
                    total = r.content_length
                    job["total"] = total
                    if total and shutil.disk_usage(os.path.dirname(dest)).free - total < _KEEP_FREE:
                        raise RuntimeError("not enough disk space")
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(part, "wb") as f:
                        async for chunk in r.content.iter_chunked(1 << 20):
                            f.write(chunk)
                            job["done"] += len(chunk)
            os.replace(part, dest)
            job["state"] = "done"
            logging.info("kouros-bridge: downloaded %s into %s", job["name"], job["directory"])
        except asyncio.CancelledError:
            job["state"], job["error"] = "failed", "cancelled"
            raise
        except Exception as e:
            job["state"], job["error"] = "failed", str(e) or type(e).__name__
            logging.warning("kouros-bridge: download of %s failed: %s", job["name"], job["error"])
        finally:
            if job["state"] != "done" and os.path.exists(part):
                try:
                    os.remove(part)
                except OSError:
                    pass
            _tasks.pop(job["id"], None)


@routes.post("/kouros/models/download")
async def download_model(request):
    try:
        body = await request.json()
    except Exception:
        return web.json_response({"error": "expected JSON"}, status=400)
    url = str(body.get("url") or "")
    directory = str(body.get("directory") or "")
    name = str(body.get("name") or "")
    if not url.startswith("https://"):
        return web.json_response({"error": "only https links"}, status=400)
    if not _NAME.match(name) or ".." in name:
        return web.json_response({"error": "not a plain file name"}, status=400)
    base = _folder(directory)
    if base is None:
        return web.json_response({"error": f"no model folder named {directory!r}"}, status=400)
    base = os.path.realpath(base)
    dest = os.path.realpath(os.path.join(base, name))
    if os.path.dirname(dest) != base:
        return web.json_response({"error": "not a plain file name"}, status=400)
    for job in _downloads.values():
        if job["state"] == "running" and job["name"] == name and job["directory"] == directory:
            return web.json_response(_public(job))
    job = {"id": uuid.uuid4().hex, "name": name, "directory": directory, "state": "running",
           "done": 0, "total": None, "error": None, "started": time.time()}
    _downloads[job["id"]] = job
    if os.path.isfile(dest):
        job["state"] = "done"
        job["done"] = job["total"] = os.path.getsize(dest)
    else:
        _tasks[job["id"]] = asyncio.get_running_loop().create_task(_fetch(job, url, dest))
    return web.json_response(_public(job))


@routes.get("/kouros/models/downloads")
async def downloads(request):
    jobs = sorted(_downloads.values(), key=lambda j: j["started"], reverse=True)
    return web.json_response([_public(j) for j in jobs])


@routes.post("/kouros/models/downloads/{id}/cancel")
async def cancel_download(request):
    task = _tasks.get(request.match_info["id"])
    if task is None:
        return web.json_response({"error": "no such running download"}, status=404)
    task.cancel()
    return web.json_response({"ok": True})
