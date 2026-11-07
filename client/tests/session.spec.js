import { test, expect } from '@playwright/test';
async function mockWorkspace(page, { role = 'USER', deliveries = [] } = {}) {
  let loggedIn=false;
  const actions=[];
  await page.route('**/api/**',async route => {
    const path=new URL(route.request().url()).pathname;
    let data=null,status=200;
    if(path==='/api/auth/csrf') data={token:'csrf-fixture',headerName:'X-XSRF-TOKEN',registrationEnabled:false};
    else if(path==='/api/auth/me') { status=loggedIn?200:401; data={id:7,username:'editor',role}; }
    else if(path==='/api/auth/login') {
      expect(route.request().headers()['x-xsrf-token']).toBe('csrf-fixture');
      loggedIn=true; data={id:7,username:'editor',role};
    } else if(path==='/api/auth/logout') { loggedIn=false; }
    else if(path==='/api/jobs/runtime') data={aiEnabled:true,model:'configured-model',objectStorage:'s3',objectStorageEnabled:true,vectorStore:'qdrant',vectorStoreEnabled:true};
    else if(path==='/api/media' || path==='/api/jobs') data=[];
    else if(path==='/api/admin/failed-deliveries') data=deliveries;
    else if(/^\/api\/admin\/failed-deliveries\/\d+\/(resolve|replay)$/.test(path)) {
      expect(route.request().method()).toBe('POST');
      expect(route.request().headers()['x-xsrf-token']).toBe('csrf-fixture');
      actions.push(path);
      deliveries=deliveries.filter(item=>item.id!==Number(path.split('/')[4]));
      data={resolved:true,replayed:true};
    }
    else { status=404; }
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify({ok:status<400,data,message:status===401?'请先登录':null})});
  });
  return actions;
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

test('admin can resolve invalid messages and replay task failures using distinct actions',async ({page}) => {
  const actions=await mockWorkspace(page,{role:'ADMIN',deliveries:[
    {id:11,jobId:0,generation:0,failureKind:'INVALID_MESSAGE',replayable:false,reason:'消息格式错误',createdAt:new Date().toISOString()},
    {id:12,jobId:7,generation:2,failureKind:'TASK_FAILURE',replayable:true,reason:'重试耗尽',createdAt:new Date().toISOString()},
  ]});
  await page.goto('/');
  await page.getByLabel('用户名',{exact:true}).fill('editor');
  await page.getByLabel('密码',{exact:true}).fill('correct-password');
  await page.getByRole('button',{name:'进入工作空间'}).click();
  const invalid=page.locator('.delivery-item').filter({hasText:'异常队列消息'});
  await expect(invalid).toBeVisible();
  await expect(invalid.getByRole('button',{name:'重投',exact:true})).toHaveCount(0);
  await invalid.getByRole('button',{name:'标记已处理'}).click();
  await expect(invalid).toHaveCount(0);
  await page.getByRole('button',{name:'重投',exact:true}).click();
  await expect(page.getByText('没有待处理的失败投递。')).toBeVisible();
  expect(actions).toEqual(['/api/admin/failed-deliveries/11/resolve','/api/admin/failed-deliveries/12/replay']);
});
