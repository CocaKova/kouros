# Kouros Bridge

An optional ComfyUI extension that gives the Kouros app two things stock ComfyUI doesn't have:

- **Deleting generated files.** Stock ComfyUI can only forget history; the files stay in `output/`.
  With the bridge, deleting in the app removes the files from disk. This works only in `output/` and `temp/`,
  and in the `input/kouros/` folder where the app uploads. Other paths are refused.
- **An honest memory report.** It reports how much memory the OS can still hand out, plus which models ComfyUI has loaded and how big they are.
- **Fetching missing models.** When a workflow names a model the server doesn't have and says where to get it (the official templates do), the app can ask the server to download it straight into the right model folder. Nothing passes through the phone.

It adds routes under `/kouros/` and no nodes. Kouros detects it automatically. Without it, the app
works the same, except that deleting only removes history.

## Install

Copy or symlink `kouros_bridge/` into `ComfyUI/custom_nodes/` and restart ComfyUI:

```sh
ln -s /path/to/kouros/bridge/kouros_bridge ComfyUI/custom_nodes/kouros_bridge
```

## Model downloads

Downloads take `https://` links only, into a folder ComfyUI itself defines (`checkpoints`, `vae`,
`loras`, `diffusion_models`…), under a plain file name. They stream to `<name>.part` and are renamed
when complete, so ComfyUI never lists a half-written file. At most two run at once, and a
download that would leave less than 5 GB free on the disk is refused.

For gated Hugging Face models, set `HF_TOKEN` in ComfyUI's environment. It is sent only to
`huggingface.co`.

## Security

ComfyUI has no authentication. Anyone who can reach its port can already run code through
custom nodes, so the bridge adds no new exposure (a download route fetches only into model folders, and custom nodes can already do far more). Still, its delete route resolves every path and refuses anything outside its folders. Keep ComfyUI on
localhost, a tailnet, or behind an authenticating proxy.

License: MIT. Author: CocaKova.
