import {mergeConfig} from 'vite'
import base from './vite.config.js'

// A separate loopback-only workspace; does not change the daily 3000/8123 setup.
const local={host:'127.0.0.1',port:13059,strictPort:true,
  proxy:{'/api':{target:'http://127.0.0.1:18259',changeOrigin:false}}}
export default mergeConfig(base,{server:local,preview:local})
