/**
 * TabManager — per-run tab state, frames, content-script injection and the
 * "AgentSphere" tab group that aggregates every tab the plugin operates on.
 */
import { loadSessionState, saveSessionState, clearSessionState } from './session-state.js';

const CONTENT_FILES = ['content-locator.js', 'content.js'];

// content script sendMessage 可能在 tab 导航/重建时永不回包 → 加超时，避免命令悬挂。
function withTimeout(promise, ms, error) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(error), ms);
    promise.then(
      (v) => { clearTimeout(timer); resolve(v); },
      (e) => { clearTimeout(timer); reject(e); },
    );
  });
}

export class TabManager {
  constructor() {
    this.controlledTabId = null;
    this.tabFollowPending = null;
    this.tabFollowResolve = null;
    this.pluginGroupId = null;
    // 已注入 content scripts 的 tab 缓存（避免每次命令都重注入 + 300ms 停顿）
    this.injectedTabs = new Set();

    // Follow tabs opened by the controlled tab (target=_blank / window.open).
    chrome.tabs.onCreated.addListener((tab) => {
      if (tab.openerTabId === this.controlledTabId) {
        this.controlledTabId = tab.id;
        saveSessionState({ controlledTabId: tab.id });
        this.injectContentScript(tab.id);
        this.groupTab(tab.id);
        this.tabFollowPending = {
          newTabId: tab.id,
          url: tab.pendingUrl || tab.url || '',
          time: Date.now(),
        };
        if (this.tabFollowResolve) {
          this.tabFollowResolve();
          this.tabFollowResolve = null;
        }
      }
    });

    // 页面导航/重新加载后 content scripts 失效 → 失效注入缓存
    chrome.tabs.onUpdated.addListener((tabId, info) => {
      if (info.status === 'loading') {
        this.injectedTabs.delete(tabId);
      }
    });

    chrome.tabs.onRemoved.addListener((tabId) => {
      this.injectedTabs.delete(tabId);
      if (tabId === this.controlledTabId) {
        this.controlledTabId = null;
        clearSessionState('controlledTabId');
      }
    });

    chrome.tabGroups.onRemoved.addListener((groupId) => {
      if (groupId === this.pluginGroupId) {
        this.pluginGroupId = null;
        clearSessionState('pluginGroupId');
      }
    });
  }

  setControlled(tabId) {
    this.controlledTabId = tabId;
    saveSessionState({ controlledTabId: tabId });
  }

  getControlled() {
    return this.controlledTabId;
  }

  /**
   * 把受控 tab 切到前台（其所在窗口内的活动 tab）。
   *
   * <p>后台 tab 会被 Chrome 节流：`requestAnimationFrame` 暂停、`setTimeout` 钳到 ≥1s
   * （隐藏满 5 分钟进一步降到约 1 次/分钟）。而本插件的几个稳定性等待全部依赖 rAF +
   * setTimeout —— 元素几何收敛帧（waitStableRect）、光标到达动画 —— 后台下每条写动作
   * 都要白白多花数秒，叠加后频繁撞上后端的动作超时。
   *
   * <p>刻意只 `tabs.update({active:true})`、不 `windows.update({focused:true})`：
   * 窗口可见但非聚焦时，活动 tab 通常已算 `visible`，既解除节流又不抢用户的 OS 焦点。
   */
  async ensureForeground(tabId) {
    if (!tabId) return;
    try {
      const tab = await chrome.tabs.get(tabId);
      if (!tab || tab.active) return;
      await chrome.tabs.update(tabId, { active: true });
    } catch (e) {
      // tab 已关闭或不归本窗口管：不阻断调用方，后续动作各自报错即可
    }
  }

  /**
   * Normalise a URL for equality comparison when reusing tabs.
   * The old check was a raw `tab.url === params.url` string compare, which missed
   * every tab differing only by hash / protocol / www / trailing slash.
   */
  normalizeUrl(u) {
    try {
      const url = new URL(u);
      url.hash = '';
      url.protocol = 'https:';
      url.hostname = url.hostname.replace(/^www\./, '');
      if (url.pathname.length > 1) url.pathname = url.pathname.replace(/\/+$/, '');
      return url.toString().toLowerCase();
    } catch (e) {
      return String(u || '').toLowerCase();
    }
  }

  /**
   * Find an already-open tab showing `url`, preferring the controlled tab, then any
   * tab inside the AgentSphere group, then anything else. Never returns non-http(s)
   * tabs (chrome://, the Web Store, PDF viewer) — those can't be operated on.
   */
  async findReusableTab(url) {
    const want = this.normalizeUrl(url);
    if (!want) return null;
    const st = await loadSessionState();
    const controlled = st.controlledTabId ?? this.controlledTabId;
    const gid = this.pluginGroupId;
    const tabs = await chrome.tabs.query({}).catch(() => []);
    const usable = tabs.filter((t) => t.id != null && /^https?:/.test(t.url || t.pendingUrl || ''));
    const ranked = [
      ...usable.filter((t) => t.id === controlled),
      ...usable.filter((t) => gid != null && t.groupId === gid),
      ...usable,
    ];
    for (const t of ranked) {
      if (this.normalizeUrl(t.url || t.pendingUrl) === want) return t;
    }
    return null;
  }

  /**
   * Re-aggregate the plugin's tabs after a service-worker restart, which wipes
   * pluginGroupId. Without this, every plugin tab left open by a previous worker
   * would fall outside the group the user sees.
   */
  async regroupControlledTabs() {
    const st = await loadSessionState();
    this.pluginGroupId = st.pluginGroupId ?? null;
    const tid = st.controlledTabId ?? this.controlledTabId;
    if (tid == null) return;
    const tab = await chrome.tabs.get(tid).catch(() => null);
    if (!tab) return;
    await this.groupTab(tab.id);
    const all = await chrome.tabs.query({}).catch(() => []);
    for (const t of all) {
      if (t.id === tid) continue;
      if (t.openerTabId === tid) await this.groupTab(t.id);
      else if (t.groupId != null && t.groupId === this.pluginGroupId) await this.groupTab(t.id);
    }
  }

  async injectContentScript(tabId) {
    if (this.injectedTabs.has(tabId)) return true;
    try {
      await chrome.scripting.executeScript({
        target: { tabId, allFrames: true },
        files: CONTENT_FILES,
      });
      this.injectedTabs.add(tabId);
      return true;
    } catch (e) {
      // chrome://, extension store, PDF viewer, etc.
      console.warn('[AgentSphere] Content script injection failed for tab', tabId, e?.message);
      return false;
    }
  }

  // Navigation can finish before executeScript is allowed to run. Confirm the
  // top-frame listener is alive instead of reporting a false-ready tab.
  async ensureContentScript(tabId, attempts = 3) {
    for (let i = 0; i < attempts; i++) {
      await this.injectContentScript(tabId);
      try {
        const response = await chrome.tabs.sendMessage(
          tabId,
          { type: 'agent_sphere_ping' },
          { frameId: 0 },
        );
        if (response?.ready) return true;
      } catch (e) {
        this.injectedTabs.delete(tabId);
      }
      if (i < attempts - 1) await new Promise((resolve) => setTimeout(resolve, 300));
    }
    return false;
  }

  // Send a message to a tab's content script, re-injecting when the receiver is missing.
  // frameId 语义：undefined/null=全部框架广播；0=定向顶层 document；>0=定向子框架。
  async askContent(tabId, msg, maxRetries = 3, frameId = 0, timeoutMs = 15000) {
    const sendOpts =
      frameId === undefined || frameId === null ? undefined : { frameId };
    for (let i = 0; i < maxRetries; i++) {
      try {
        return await withTimeout(
          chrome.tabs.sendMessage(tabId, msg, sendOpts),
          timeoutMs,
          new Error('Content script sendMessage timed out'),
        );
      } catch (e) {
        if ((String(e.message).includes('Receiving end does not exist')
            || String(e.message).includes('timed out'))
          && i < maxRetries - 1
          && timeoutMs > 1000) {
          this.injectedTabs.delete(tabId);
          const injected = await this.injectContentScript(tabId);
          if (!injected) break;
          await new Promise((r) => setTimeout(r, 300));
          continue;
        }
        throw e;
      }
    }
    throw new Error('Content script unavailable in tab ' + tabId);
  }

  // Aggregate the tab into the single "AgentSphere" group (creates it once).
  // The group id is persisted because the service worker restarts and loses it —
  // without that, every restart created a NEW group and the tabs visibly split.
  async groupTab(tabId) {
    try {
      if (this.pluginGroupId == null) {
        const st = await loadSessionState();
        this.pluginGroupId = st.pluginGroupId ?? null;
      }
      let gid = this.pluginGroupId;
      if (gid != null) {
        // A remembered group id can be stale (user ungrouped/closed it) — verify.
        const g = await chrome.tabGroups.get(gid).catch(() => null);
        if (!g) {
          gid = null;
          this.pluginGroupId = null;
          await clearSessionState('pluginGroupId');
        }
      }
      if (gid != null) {
        await chrome.tabs.group({ tabIds: [tabId], groupId: gid });
        return true;
      }
      const newId = await chrome.tabs.group({ tabIds: [tabId] });
      this.pluginGroupId = newId;
      await saveSessionState({ pluginGroupId: newId });
      try {
        await chrome.tabGroups.update(newId, { title: 'AgentSphere', color: 'cyan' });
      } catch (e) {
        /* group styling failure is non-fatal */
      }
      return true;
    } catch (e) {
      // Tabs that cannot be grouped (chrome://, extension pages) land here — as can a
      // genuine failure. Callers may report; swallowing silently hides both.
      console.warn('[AgentSphere] groupTab failed for tab', tabId, e?.message);
      return false;
    }
  }

  waitForTabComplete(tabId, timeoutMs = 20000) {
    return new Promise((resolve) => {
      let settled = false;
      const listener = (id, info) => {
        if (id === tabId && info.status === 'complete') {
          finish();
        }
      };
      const finish = () => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        chrome.tabs.onUpdated.removeListener(listener);
        resolve();
      };
      const timer = setTimeout(() => {
        finish();
      }, timeoutMs);
      chrome.tabs.onUpdated.addListener(listener);
      // The complete event may have fired before the listener was installed.
      chrome.tabs.get(tabId).then((tab) => {
        if (tab?.status === 'complete') finish();
      }).catch(() => finish());
    });
  }
}

export const tabManager = new TabManager();
