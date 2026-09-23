// Golden oracle: the server's own frontend compiles each UI workflow to API format.
// usage: node oracle.mjs <server> <outDir> <workflow.json>...
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
import path from 'node:path';
const [server, outDir, ...files] = process.argv.slice(2);
const browser = await puppeteer.launch({
  executablePath: process.env.CHROME || '/usr/bin/chromium',
  args: ['--no-sandbox', '--headless=new'],
});
const page = await browser.newPage();
page.on('pageerror', e => {});
await page.goto(server, { waitUntil: 'networkidle2', timeout: 120000 });
await page.waitForFunction(() => window.app && window.app.graph && window.app.graphToPrompt, { timeout: 120000 });
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'object_info.json'), await (await fetch(server + '/object_info')).text());
let ok = 0, bad = 0;
for (const f of files) {
  const name = path.basename(f).replace(/\.json$/, '');
  const wf = JSON.parse(fs.readFileSync(f, 'utf8'));
  try {
    const out = await page.evaluate(async (wf, name) => {
      await window.app.loadGraphData(wf, true, true, name);
      const r = await window.app.graphToPrompt();
      return JSON.stringify(r.output);
    }, wf, name);
    const d = path.join(outDir, name);
    fs.mkdirSync(d, { recursive: true });
    fs.copyFileSync(f, path.join(d, 'workflow.json'));
    fs.writeFileSync(path.join(d, "api.json"), JSON.stringify(JSON.parse(out), null, 1));
    ok++; console.log('ok  ', name, Object.keys(JSON.parse(out)).length, 'nodes');
  } catch (e) { bad++; console.log('FAIL', name, String(e).slice(0, 200)); }
}
console.log(`done ok=${ok} fail=${bad}`);
await browser.close();
