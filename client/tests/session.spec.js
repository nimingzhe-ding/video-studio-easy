import { test, expect } from '@playwright/test';
async function mockWorkspace(page) {
  let loggedIn=false;
  await page.route('**/api/**',async route => {
    const path=new URL(route.request().url()).pathname;
    let data=null,status=200;
    if(path==='/api/auth/csrf') data={token:'csrf-fixture',headerName:'X-XSRF-TOKEN',registrationEnabled:false};
    else if(path==='/api/auth/me') { status=loggedIn?200:401; data={id:7,username:'editor',role:'USER'}; }
    else if(path==='/api/auth/login') {
      expect(route.request().headers()['x-xsrf-token']).toBe('csrf-fixture');
      loggedIn=true; data={id:7,username:'editor',role:'USER'};
    } else if(path==='/api/auth/logout') { loggedIn=false; }
    else if(path==='/api/jobs/runtime') data={aiEnabled:true,model:'configured-model',objectStorage:'s3',objectStorageEnabled:true,vectorStore:'qdrant',vectorStoreEnabled:true};
    else if(path==='/api/media' || path==='/api/jobs') data=[];
    else { status=404; }
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify({ok:status<400,data,message:status===401?'请先登录':null})});
  });
}
test('login uses CSRF, opens the workspace and logs out',async ({page},info) => {
  const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  await mockWorkspace(page); await page.goto('/');
  await expect(page.getByRole('heading',{name:'回到你的档案'})).toBeVisible();
  await page.getByLabel('用户名',{exact:true}).fill('editor');
  await page.getByLabel('密码',{exact:true}).fill('correct-password');
  await page.getByRole('button',{name:'进入工作空间'}).click();
  await expect(page.getByRole('button',{name:'退出账户 editor'})).toBeVisible();
  await page.getByRole('button',{name:'退出账户 editor'}).click();
  await expect(page.getByRole('heading',{name:'回到你的档案'})).toBeVisible();
  await page.screenshot({path:info.outputPath('login-desktop.png'),fullPage:true});
  expect(errors).toEqual([]);
});
test('login fits a mobile viewport',async ({page},info) => {
  await page.setViewportSize({width:390,height:844}); await mockWorkspace(page); await page.goto('/');
  await expect(page.getByRole('button',{name:'进入工作空间'})).toBeVisible();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await page.screenshot({path:info.outputPath('login-mobile.png'),fullPage:true});
});
test('report HTML cannot inject scripts or event handlers',async ({page}) => {
  await mockWorkspace(page); await page.goto('/');
  const html=await page.evaluate(async()=>{
    const {renderMarkdown}=await import('/src/markdown.js');
    return renderMarkdown('<script>alert(1)</script><img src=x onerror=alert(1)><a href="javascript:alert(1)">click</a> [01:23]');
  });
  expect(html).not.toMatch(/<script|onerror|javascript:/i);
  expect(html).toContain('data-ts="01:23"');
});
