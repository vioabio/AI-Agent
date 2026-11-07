import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {smokeSourceAllowed,createSmokeWorkspace} from './local-smoke-workspace.mjs'
test('public source and example config are selected',()=>{for(const p of ['src/main/java/example/App.java','src/main/resources/application.yml','src/main/resources/application-local.example.yml','vio-ai-agent-frontend/src/main.js','tools/pokemon-engine/package-lock.json'])assert.equal(smokeSourceAllowed(p),true)})
test('private configs credentials databases and outputs cannot be selected',()=>{for(const p of ['src/main/resources/application-local.yml','src/main/resources/application-deepseek.yml','src/main/resources/mcp-servers.json','vio-ai-agent-frontend/.env.local','tools/pokemon-engine/node_modules/test.js','src/main/resources/a.sqlite','../secret','C:/private.key','src/main/resources/../application.yml','target/classes/test.java','src/test/Test.java'])assert.equal(smokeSourceAllowed(p),false,p)})
test('existing or outside destinations fail before reading candidate files',t=>{const root=fs.mkdtempSync(path.join(os.tmpdir(),'smoke-copy-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));fs.mkdirSync(path.join(root,'artifacts'));assert.throws(()=>createSmokeWorkspace(root,path.join(root,'outside'),['missing']));assert.throws(()=>createSmokeWorkspace(root,path.join(root,'artifacts'),['missing']))})
test('missing required public manifests reject the package without creating output',t=>{const root=fs.mkdtempSync(path.join(os.tmpdir(),'smoke-required-'));t.after(()=>fs.rmSync(root,{recursive:true,force:true}));const out=path.join(root,'artifacts','new');assert.throws(()=>createSmokeWorkspace(root,out,['src/main/resources/application-local.yml']),/Missing required/);assert.equal(fs.existsSync(out),false)})
test('dependency credentials are excluded even inside a selected source directory',()=>{assert.equal(smokeSourceAllowed('tools/pokemon-engine/.npmrc'),false);assert.equal(smokeSourceAllowed('src/main/resources/.netrc'),false)})
test('case variants of Windows private config names remain excluded',()=>{for(const p of ['src/main/resources/APPLICATION-LOCAL.YML','src/main/resources/MCP-SERVERS.JSON','vio-ai-agent-frontend/.ENV.local','tools/pokemon-engine/.NPMRC'])assert.equal(smokeSourceAllowed(p),false)})
