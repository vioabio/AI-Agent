import test from 'node:test'
import assert from 'node:assert/strict'
import { fetchPokemonBuildOptions } from '../src/api/index.js'

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
