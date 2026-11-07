import test from 'node:test'
import assert from 'node:assert/strict'
import { fetchPokemonBuildOptions } from '../src/api/index.js'
import { NATURE_LABELS } from '../src/api/pokemon-natures.js'

test('all 25 persisted nature keys have Chinese display names', () => {
  assert.equal(Object.keys(NATURE_LABELS).length,25)
  for(const name of Object.values(NATURE_LABELS)) assert.match(name,/^[\u4e00-\u9fff]+$/)
  assert.equal(NATURE_LABELS.careful,'慎重')
})

test('missing candidate route explains backend restart and configuration instead of endless generic retry', async () => {
  const previous=globalThis.fetch
  globalThis.fetch=async()=>new Response('{}',{status:404})
  try {
    await assert.rejects(fetchPokemonBuildOptions({kind:'item',dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet'}),error=>error.status===404&&error.message.includes('重启后端')&&error.message.includes('local'))
  } finally {globalThis.fetch=previous}
})

test('build candidate searches retain session, cancellation, member/version and pagination parameters', async () => {
  const previous=globalThis.fetch, calls=[], controller=new AbortController()
  globalThis.fetch=async(url,options)=>{calls.push({url,options});return new Response(JSON.stringify({items:[],nextOffset:null}),{status:200})}
  try {
    const request={kind:'ability',pokemonId:445,dataReleaseId:'pokeapi-reference-v1',gameReleaseId:'pokemon-scarlet-violet',query:'粗糙',offset:0}
    await fetchPokemonBuildOptions(request,{signal:controller.signal})
    const url=new URL(calls[0].url,'http://localhost')
    assert.equal(url.searchParams.get('query'),'粗糙')
    assert.equal(url.searchParams.get('pokemonId'),'445')
    assert.equal(url.searchParams.get('dataReleaseId'),request.dataReleaseId)
    assert.equal(calls[0].options.credentials,'include')
    assert.equal(calls[0].options.signal,controller.signal)
    await fetchPokemonBuildOptions({...request,kind:'item',offset:50})
    const items=new URL(calls[1].url,'http://localhost')
    assert.equal(items.searchParams.has('pokemonId'),false)
    assert.equal(items.searchParams.get('offset'),'50')
  } finally {globalThis.fetch=previous}
})
