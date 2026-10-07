// 金额统一展示口径：服务端保证合法售价/订单金额均为两位小数落库与返回，
// 前端所有页面（列表 / 编辑表单 / 出杯选择器 / 订单预览 / 会员订单）只经此函数渲染，
// 保证同一配方、同一订单在任何页面显示同一个结果。
export function fmtMoney(v) {
  if (v === null || v === undefined || v === '') return '—'
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return n.toFixed(2)
}

// 配方售价展示：priceStatus=「异常」（历史遗留多位小数/空值/越界）时给出降级结果，
// 原样展示原始值并附说明，绝不静默四舍五入。
export function recipePriceLabel(recipe) {
  if (recipe && recipe.priceStatus === '异常') {
    return {
      abnormal: true,
      text: recipe.price === null || recipe.price === undefined ? '—' : String(recipe.price),
      issue: recipe.priceIssue || '售价数据异常，请编辑修正'
    }
  }
  return { abnormal: false, text: fmtMoney(recipe ? recipe.price : null), issue: '' }
}

// 售价输入校验（与服务端同一套规则，仅用于即时提示；最终以服务端校验为准）。
// 返回 '' 表示合法，否则为错误提示。输入原样保留，不做任何静默改写。
export function priceProblem(text) {
  const t = String(text ?? '').trim()
  if (!t) return '请填写售价'
  if (!/^-?\d+(\.\d+)?$/.test(t)) return '售价必须是数字（最多两位小数）'
  const decimals = (t.split('.')[1] || '').length
  if (decimals > 2) return '售价最多保留两位小数'
  const n = Number(t)
  if (n < 0.01) return '售价必须大于 0（最低 0.01 元）'
  if (n > 99999999.99) return '售价不能超过 99999999.99 元'
  return ''
}
