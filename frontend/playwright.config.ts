import {defineConfig,devices} from '@playwright/test';
export default defineConfig({
 testDir:'./tests',
 timeout:30000,
 expect:{timeout:7000},
 use:{baseURL:'http://127.0.0.1:5173',viewport:{width:1380,height:860},trace:'retain-on-failure',screenshot:'only-on-failure'},
 webServer:{command:'npm run dev',url:'http://127.0.0.1:5173',reuseExistingServer:false,timeout:60000},
 projects:[{name:'chromium',use:{...devices['Desktop Chrome'],viewport:{width:1380,height:860}}}]
});
