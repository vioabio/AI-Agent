import {defineConfig} from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
const output=process.env.VIO_MODEL_DEMO_OUTPUT
if(!output||fs.existsSync(path.join(output,'playwright.json')))throw Error('Fresh model-demo output required')
export default defineConfig({testDir:'./e2e-live',testMatch:'demo-preview-recording.spec.js',workers:1,retries:0,timeout:360000,expect:{timeout:15000},outputDir:path.join(output,'browser'),reporter:[['list'],['json',{outputFile:path.join(output,'playwright.json')}]],use:{baseURL:'http://127.0.0.1:13059',channel:'msedge',trace:'off',video:'off',screenshot:'off',serviceWorkers:'block'}})
