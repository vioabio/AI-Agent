import { readFileSync } from 'node:fs'
import { resolve, relative, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { frozenFileDigest } from './evaluation.mjs'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..')
export function auditCorpus(corpus,workspace=root) {
  if(corpus.schemaVersion!==2 || !/^[a-z0-9][a-z0-9-]{0,99}$/.test(corpus.catalogId)
      || !Array.isArray(corpus.documents) || !corpus.documents.length || corpus.documents.length>200)throw Error('Invalid corpus envelope')
  const ids=new Set(),texts=new Set(),codeFiles=new Set(),urls=new Set()
  for(const doc of corpus.documents) {
    if(!doc.id || ids.has(doc.id) || !doc.title || !doc.content?.trim() || texts.has(doc.content)
        || doc.author!=='VIO project' || doc.language!=='zh-CN' || doc.gameReleaseId!=='pokemon-scarlet-violet'
        || doc.battleFormatId!=='casual-singles' || doc.rulesetVersionId!=='casual-v1')throw Error('Invalid/duplicate source identity or scope')
    ids.add(doc.id);texts.add(doc.content)
    const p=doc.provenance
    if(p?.kind!=='PROJECT_NOTE' || p.contentUsage!=='ORIGINAL_NO_UPSTREAM_COPY' || p.reviewStatus!=='PENDING_HUMAN_REVIEW'
        || !p.acquiredAt || !Array.isArray(p.codeReferences) || !p.codeReferences.length || !Array.isArray(p.references))throw Error('Missing original-note attribution')
    for(const r of p.codeReferences) {
      if(!/^(src\/main\/java\/|tools\/pokemon-engine\/)[A-Za-z0-9_./-]+$/.test(r.path) || r.path.includes('..'))throw Error('Unsafe code reference')
      const path=resolve(workspace,r.path)
      if(relative(workspace,path).startsWith('..'+sep) || frozenFileDigest(path)!==r.sha256)throw Error(`Stale code reference: ${r.path}`)
      codeFiles.add(r.path)
    }
    for(const r of p.references) {
      const url=new URL(r.url)
      if(!['https:','http:'].includes(url.protocol) || url.username || url.password || !r.title || !r.publisher
          || !['API_FIELD_REFERENCE','ENGINE_INTERFACE_REFERENCE'].includes(r.relation))throw Error('Invalid reference-only URL')
      urls.add(r.url)
    }
  }
  return {catalogId:corpus.catalogId,documentCount:ids.size,uniqueCodeFiles:codeFiles.size,upstreamReferenceUrls:[...urls].sort(),
    humanReview:'pending',scope:'Metadata and source-code identity audit; not semantic fact checking or external article permission.'}
}
if(process.argv[1] && resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  if(process.argv.length>2)throw Error('Usage: node tools/campus/audit-s3-corpus.mjs')
  const path=resolve(root,'src/main/resources/pokemon-guides/expanded-v1.json')
  console.log(JSON.stringify({...auditCorpus(JSON.parse(readFileSync(path,'utf8'))),corpusSha256:frozenFileDigest(path)},null,2))
}
