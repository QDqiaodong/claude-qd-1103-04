// 金额统一口径：所有页面（配方列表 / 编辑表单 / 出杯选择器 / 订单预览 / 订单列表）
// 都只能通过这里显示金额，杜绝同一配方在不同页面出现 12、12.0、12.00 的不一致。

/** 规范金额展示：12 -> "12.00"；null/undefined -> "—"（降级态由调用方另给提示） */
export function formatMoney(value) {
  if (value === null || value === undefined || value === '') return '—'
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return '—'
  return n.toFixed(2)
}

/** 带人民币符号的展示 */
export function formatYuan(value) {
  return '¥' + formatMoney(value)
}

/**
 * 校验用户在编辑表单里输入的售价。
 * 规则与服务端完全一致（服务端仍会再次校验，这里只负责即时提示与保留输入）：
 * 必填、必须是数字、大于 0、最多两位小数、不超过 99999999.99。
 * 返回 { ok, value }；不 ok 时 value 为 null，error 为稳定中文提示。
 */
export function validatePriceInput(raw) {
  const text = String(raw ?? '').trim()
  if (!text) return { ok: false, error: '售价不能为空' }
  if (!/^\d+(\.\d+)?$/.test(text)) {
    return { ok: false, error: '售价必须是大于 0 的数字，不能为负数或非数字' }
  }
  const parts = text.split('.')
  if (parts.length === 2 && parts[1].length > 2) {
    return { ok: false, error: '售价最多保留两位小数（系统不会自动四舍五入），请直接改成两位小数' }
  }
  const n = Number(text)
  if (!Number.isFinite(n) || n <= 0) return { ok: false, error: '售价必须大于 0' }
  if (n > 99999999.99) return { ok: false, error: '售价不能超过 99999999.99 元' }
  // 以两位小数字符串提交（12 -> "12.00"），落库与回显口径一致
  return { ok: true, value: n.toFixed(2) }
}
