# Golden corpus generator

The compiler in `:core` is checked against the ComfyUI frontend itself. This script loads a
running server's own frontend in headless Chromium, feeds it saved UI workflows, and records what
its `graphToPrompt` produces. Each case becomes `workflow.json` + `api.json`, next to the server's
`object_info.json`.

```sh
npm i puppeteer-core@23
CHROME=/path/to/chromium node tools/golden/oracle.mjs http://127.0.0.1:8188 \
  core/src/jvmTest/resources/corpus/mine  path/to/workflows/*.json
```

Any directory under `core/src/jvmTest/resources/corpus/` that contains an `object_info.json`
joins the golden test run. Only `public/` is committed: it is built from ComfyUI's MIT-licensed
workflow templates and its `object_info.json` keeps only the node classes and choice values those
templates use. Keep corpora made from your own workflows out of git (see `.gitignore`).

The test reports four outcomes: **exact**, **deferred** (the compiler flagged the workflow as one
it cannot reproduce on its own, so the app sources the prompt elsewhere), **frontend-invalid**
(the frontend's output would fail the server's own validation where ours passes), and
**confidently wrong** — the only one that fails the build.
