# Changelog

## 1.1.0 — 2026-09-23

- **Apps.** A new first tab: one-purpose tools built on a workflow — a photo in, one or two knobs,
  a button. **Upscale 4×** enlarges without diffusion, in seconds and with little memory.
  **SUPIR restore** rebuilds real detail as it enlarges, for photos that need more than sharpening.
- **Setting an app up is one button.** Each card says what this server is still missing — a node
  pack, model files and how many gigabytes. Kouros fetches the models through the bridge, asks
  ComfyUI-Manager to install the pack, and restarts the server so it can see it. A server with
  neither is told plainly what to install by hand, with the link.
- **Apps are data, not code.** An app is a prompt, a form arrangement and a list of what it needs;
  nothing about any node pack is compiled in. The server's own `/object_info` and model folders
  decide whether an app is ready.
- **A run that stops short says why.** A run can be ended by something that has nothing to do with
  the phone — a memory guard, someone else's interrupt, a restart — and until now the app simply
  showed nothing, which made the app look broken. Now it records who stopped it, the node it had
  reached, how much memory the server had left, and the server's own last log lines, and shows
  them on the run screen with a plain "Kouros didn't stop it". An interrupted run also notifies,
  which it never used to.
- **The button says what it does.** "Restore" on SUPIR, "Upscale" on the upscaler, "Run" everywhere else.

## 1.0.1 — 2026-09-23

- **Tracks are presented as tracks.** A music result used to be a black rectangle with a filename:
  the player's controls faded out after a few seconds. There is now a proper player — cover
  plinth with a moving level meter, title, scrubber, elapsed and total time, a transport that
  stays on screen, ten-second skips and a repeat toggle.
- **The result comes first.** Workflows that hand back working notes beside the result (YuE2's ABC
  plan, any PreviewAny dump) filed them in node order, so opening a song landed on a wall of text.
  Media now leads and text follows, in the gallery and in the viewer alike.
- **Text outputs are readable.** Monospace so structure survives, labelled with the node that made
  them, selectable, and Copy replaces Save and Share (there is no file to save).
- **The run screen shows a finished track** instead of an empty plinth.

## 1.0.0 — 2026-09-23

First stable release, signed with the release key. **Coming from a pre-release:** uninstall it
first (the pre-releases were signed with a development key, so Android won't update over them),
then install 1.0.0 and add your server again. Your results stay on the server.

- **Template gallery.** Browse the templates your server ships, add one to your workflows (the
  desktop sees it too) and open it as a form. Templates that need a paid cloud account are left out.
- **Missing models.** The form lists every model the server doesn't have, the folder it goes in
  and a link to it. With Kouros Bridge 2, the server downloads it itself.
- **Arrange fields.** Pin fields to the top, reorder, rename and hide them, per workflow.
- **Presets.** Save the whole form (reference photos included) under a name and load it later.
- **Whole-server gallery.** "Everything on the server" lists the output folder, so it survives
  server restarts; files from a known run can still be remixed.
- **First run.** "Connect a server" opens the form directly and lands on your workflows after
  saving; notification permission is asked at the first Run instead of at launch.
- **Fixes.** Missing models were not reported for an empty model folder; the progress bar could
  count cached nodes as still to run; the Workflows tab could stay stuck on Servers; long prompts
  now scroll inside their box; clipped template captions.
- **Accessibility.** Status dots and long-press actions are labelled for TalkBack; the
  connecting pulse stops when animations are turned off.
- **Faster start.** An app baseline profile ships with the APK.

## 0.3.1

- Atelier colours everywhere (the nav bar, chips and tabs had fallen back to Material's defaults).
- Connection dot beside the server name; honest free memory on unified-memory machines.

## 0.3.0

- Server management: delete media (with Kouros Bridge), Activity tab with memory, queue and
  history, a server console (system, logs, models), and workflow files (rename, move,
  duplicate, delete).

## 0.2.x

- Prompt assistant over any OpenAI-compatible endpoint; workflow kinds, pins and folders.
- Reference photos for any workflow that takes them, named the way the model expects.

## 0.1.x

- First builds: any saved workflow as a phone form, live previews and background-safe runs.
