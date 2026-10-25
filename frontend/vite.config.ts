import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
export default defineConfig({plugins:[vue()],server:{proxy:{'/api':'http://127.0.0.1:8080','/mcp':'http://127.0.0.1:8080'}},build:{outDir:'../src/main/resources/static',emptyOutDir:true}});