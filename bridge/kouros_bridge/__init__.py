"""Kouros Bridge — optional ComfyUI routes the stock server lacks, for the Kouros app.

GET  /kouros/bridge          what this bridge offers (the app probes it; absent = stock server)
POST /kouros/outputs/delete  delete generated files: {"files": [{"filename", "subfolder", "type"}]}
GET  /kouros/memory          memory the OS can still hand out, and the models ComfyUI holds

Deletion is confined to the output and temp folders, plus the input folder's "kouros"
subfolder (where the app uploads). Paths are resolved and checked to stay inside their
folder; only regular files are removed. No node classes: it only adds routes.
"""
import logging
import os

from aiohttp import web

import folder_paths
from server import PromptServer

VERSION = 1
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
    return web.json_response({"name": "kouros-bridge", "version": VERSION, "features": ["outputs.delete", "memory"]})


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
