# Changelog

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
