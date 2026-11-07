import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { createHash } from 'node:crypto'
import { inspectCache, exportBundle, importBundle } from './reference-cache.mjs'
const hash=value=>createHash('sha256').update(value).digest('hex')
function fixture(t) {
 const root=fs.mkdtempSync(path.join(os.tmpdir(),'reference-bundle-test-')),cache=path.join(root,'cache');fs.mkdirSync(cache)
 t.after(()=>{const resolved=path.resolve(root);assert.equal(path.dirname(resolved),path.resolve(os.tmpdir()));assert.ok(path.basename(resolved).startsWith('reference-bundle-test-'));fs.rmSync(resolved,{recursive:true,force:true})})
 // Synthetic transport fixtures only; these are not published upstream observations.
 const pokemon={id:7,name:'squirtle',species:{name:'squirtle',url:'https://pokeapi.co/api/v2/pokemon-species/7/'},stats:['hp','attack','defense','special-attack','special-defense','speed'].map(name=>({stat:{name},base_stat:44})),types:[{type:{name:'water'}}],abilities:[{ability:{name:'torrent'}}]}
 const species={id:7,name:'squirtle',generation:{name:'generation-i'},names:[]}
 function save(file,kind,payload) {const text=JSON.stringify(payload);const envelope={schemaVersion:1,url:`https://pokeapi.co/api/v2/${kind}/${kind==='pokemon-species'?payload.name:'7'}/`,payload:text,sha256:'sha256:'+hash(text),retrievedAt:'2026-10-20T00:00:00Z'};fs.writeFileSync(path.join(cache,file),JSON.stringify(envelope));}
 save('pokemon-7.json','pokemon',pokemon);save('pokemon-species-squirtle.json','pokemon-species',species)
 return {root,cache,pokemon,species,save}
}
test('portable round trip preserves original bytes, dates and aliases without network',t=>{
 const f=fixture(t);assert.equal(inspectCache(f.cache,['7']).queries[0].status,'ALIAS_MISSING')
 const bundle=path.join(f.root,'bundle'),restored=path.join(f.root,'restored');exportBundle(f.cache,['7'],bundle)
 assert.equal(importBundle(bundle,restored).ready,true);assert.equal(inspectCache(restored,['squirtle','7']).ready,true)
 for(const name of ['pokemon-7.json','pokemon-species-squirtle.json'])assert.deepEqual(fs.readFileSync(path.join(f.cache,name)),fs.readFileSync(path.join(restored,name)))
 assert.deepEqual(fs.readFileSync(path.join(restored,'pokemon-7.json')),fs.readFileSync(path.join(restored,'pokemon-squirtle.json')))
})
test('corrupt or conflicting cache data stays visible and cannot create an export',t=>{
 const f=fixture(t),file=path.join(f.cache,'pokemon-7.json'),entry=JSON.parse(fs.readFileSync(file));entry.payload+=' ';fs.writeFileSync(file,JSON.stringify(entry))
 const out=path.join(f.root,'bad');assert.equal(inspectCache(f.cache,['7']).queries[0].status,'INVALID');assert.throws(()=>exportBundle(f.cache,['7'],out),/INTEGRITY/);assert.equal(fs.existsSync(out),false)
 f.save('pokemon-7.json','pokemon',f.pokemon);f.save('pokemon-squirtle.json','pokemon',{...f.pokemon,extra:'changed-body'})
 assert.throws(()=>exportBundle(f.cache,['7'],out),/CONFLICTING_ALIAS/)
 f.save('pokemon-squirtle.json','pokemon',f.pokemon);const wrongSource=JSON.parse(fs.readFileSync(path.join(f.cache,'pokemon-species-squirtle.json')));wrongSource.url='https://pokeapi.co/api/v2/pokemon-species/7/';fs.writeFileSync(path.join(f.cache,'pokemon-species-squirtle.json'),JSON.stringify(wrongSource));assert.equal(inspectCache(f.cache,['7']).queries[0].status,'INVALID');f.save('pokemon-species-squirtle.json','pokemon-species',f.species)
 f.save('pokemon-squirtle.json','pokemon',f.pokemon);const date=JSON.parse(fs.readFileSync(file));date.retrievedAt='2026-02-30T00:00:00Z';fs.writeFileSync(file,JSON.stringify(date));assert.equal(inspectCache(f.cache,['7']).queries[0].status,'INVALID')
})
test('missing paired species and unrelated files never become an offline readiness claim',t=>{
 const f=fixture(t);fs.unlinkSync(path.join(f.cache,'pokemon-species-squirtle.json'));fs.writeFileSync(path.join(f.cache,'application-local.yml'),'private-do-not-read')
 const report=inspectCache(f.cache,['7']);assert.equal(report.ready,false);assert.deepEqual(report.queries[0].files,['pokemon-species-squirtle.json']);assert.equal(JSON.stringify(report).includes('private-do-not-read'),false)
 assert.throws(()=>inspectCache(f.cache,['../secret']),/INVALID_QUERY/)
})
test('bundle traversal, wrong source and tampering are rejected before destination writes',t=>{
 const f=fixture(t),bundle=path.join(f.root,'bundle'),out=path.join(f.root,'out');exportBundle(f.cache,['7'],bundle)
 const manifestPath=path.join(bundle,'manifest.json'),original=JSON.parse(fs.readFileSync(manifestPath));let changed=structuredClone(original);changed.files[0].file='../application-local.yml';fs.writeFileSync(manifestPath,JSON.stringify(changed))
 assert.throws(()=>importBundle(bundle,out),/UNSAFE_FILE_NAME/);assert.equal(fs.existsSync(out),false)
 fs.writeFileSync(manifestPath,JSON.stringify(original));const file=path.join(bundle,original.files[0].file),entry=JSON.parse(fs.readFileSync(file));entry.url='https://secret@example.com/pokemon/7/';fs.writeFileSync(file,JSON.stringify(entry))
 assert.throws(()=>importBundle(bundle,out),/SOURCE_IDENTITY/);assert.equal(fs.existsSync(out),false)
})
test('existing destinations and manifest omissions cannot overwrite data or hide incomplete packs',t=>{
 const f=fixture(t),bundle=path.join(f.root,'bundle');exportBundle(f.cache,['7'],bundle)
 assert.throws(()=>exportBundle(f.cache,['7'],bundle),/DESTINATION_EXISTS/)
 const target=path.join(f.root,'target');fs.mkdirSync(target);fs.writeFileSync(path.join(target,'keep'),'unchanged');assert.throws(()=>importBundle(bundle,target),/DESTINATION_EXISTS/);assert.equal(fs.readFileSync(path.join(target,'keep'),'utf8'),'unchanged')
 const m=JSON.parse(fs.readFileSync(path.join(bundle,'manifest.json')));m.files=m.files.slice(1);fs.writeFileSync(path.join(bundle,'manifest.json'),JSON.stringify(m));assert.throws(()=>importBundle(bundle,path.join(f.root,'new')),/UNEXPECTED_BUNDLE_FILE/)
})
