# Kouros

A native Android app for running [ComfyUI](https://github.com/comfyanonymous/ComfyUI) from your
phone. Open any workflow saved on your server, get a clean form for the parts that matter, queue
it, and follow it live — in the notification, on the lock screen, anywhere — until the result
lands in your gallery.

> Status: early development. Not yet released.

## What makes it different

- **Any workflow, as it is.** Subgraphs, bypassed and muted nodes, reroutes, primitive nodes,
  dynamic inputs — Kouros compiles saved workflows the same way the ComfyUI frontend does, and
  is tested against the frontend's own output on hundreds of real workflows (see
  [`tools/golden`](tools/golden/README.md)). When a workflow needs something only the desktop can
  compute, it says so instead of guessing.
- **Nothing hard-coded.** Every node is understood through what your server reports about it
  (`/object_info`). Node packs, models and custom nodes are yours; the app adapts to them.
- **Built for a phone.** Background-safe progress, reconnects that never lose a run, previews in
  the notification, and a gallery of everything you made.

## Building

Requires JDK 17 and the Android SDK (API 36).

```sh
./gradlew :core:jvmTest        # protocol, compiler and golden corpus
./gradlew :app:assembleFossDebug
```

`tools/ship.sh` runs tests and assembles in one go, and can hand the build to another machine
(see `tools/remote-ship.sh`).

## Authorship

Kouros is written and maintained solely by [CocaKova](https://github.com/CocaKova).

## License

[PolyForm Noncommercial 1.0.0](LICENSE). Free for personal and other noncommercial use;
commercial use is reserved to the author.

Bundled fonts: [Fraunces](licenses/Fraunces-OFL.txt) and [Inter](licenses/Inter-OFL.txt), both
under the SIL Open Font License. The public test corpus is derived from ComfyUI's MIT-licensed
workflow templates.
