/**
 * 扩展的后台脚本。
 *
 * <p>当前版本很薄：点扩展图标弹出侧窗，所有交互都在弹窗里完成，
 * 所以这里只负责在安装时给一个默认服务地址，避免用户第一次打开就看到空白输入框。
 */
chrome.runtime.onInstalled.addListener(async () => {
  const stored = await chrome.storage.local.get('baseUrl');
  if (!stored.baseUrl) {
    await chrome.storage.local.set({ baseUrl: 'http://127.0.0.1:9090' });
  }
});
