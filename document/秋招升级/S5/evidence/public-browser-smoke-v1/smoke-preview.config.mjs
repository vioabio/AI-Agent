import {mergeConfig} from 'vite';
import base from './vite.config.js';
export default mergeConfig(base,{preview:{host:'127.0.0.1',port:13059,strictPort:true,proxy:{'/api':{target:'http://127.0.0.1:18259',changeOrigin:false}}}});
