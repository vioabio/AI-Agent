import {runScenario} from './build-check-scenario.mjs'
const [base,out,...extra]=process.argv.slice(2)
if(!base||!out||extra.length)throw Error('Usage: node tools/campus/learning-review-scenario.mjs ISOLATED_LOCAL_API NEW_OUTPUT')
await runScenario(base,out,'evaluation/campus/learning-review-v1/plan.json')
