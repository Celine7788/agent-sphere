export const ErrorCategory = {
  NOT_FOUND: 'not_found',
  // 目标存在但当前不可操作（disabled / 隐藏 / 已从文档脱离）。
  // 与 not_found 分开：前者该「等它可用或换目标」，后者是「找不到，去别处找」。
  // （工具描述的 Rules 早已声明这个分类，但此前没有任何地方真的产出过。）
  NOT_INTERACTABLE: 'not_interactable',
  CSP_BLOCKED: 'csp_blocked',
  DETACHED: 'detached',
  INJECT_FAILED: 'inject_failed',
  TIMEOUT: 'timeout',
  NO_TAB: 'no_tab',
  WRONG_SITE: 'wrong_site',
  AMBIGUOUS: 'ambiguous',
  // 点击/悬停坐标处命中的是别的元素（sticky header、弹层、透明遮罩）。
  // 与 not_found 区分开：换 selector/scope 或先关弹层，而不是等元素出现。
  OCCLUDED: 'occluded',
  UNKNOWN: 'unknown',
};

export function okResult(data, method) {
  const r = { success: true, data };
  if (method) r.method = method;
  return r;
}

export function noReturn(method) {
  return {
    success: true,
    data: '__NO_RETURN__',
    _resultType: 'void',
    ...(method ? { method } : {}),
  };
}

// Success with a warning (e.g. execution may have been blocked / result not serializable).
export function okWarning(method, warning, data, resultType) {
  return {
    success: true,
    data: data === undefined ? null : data,
    _resultType: resultType || (data === undefined ? 'void' : typeof data),
    ...(method ? { method } : {}),
    warning,
  };
}

export function failResult(message, category, extra) {
  const r = { success: false, error: message, errorCategory: category || ErrorCategory.UNKNOWN };
  if (extra) Object.assign(r, extra);
  return r;
}
