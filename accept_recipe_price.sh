#!/usr/bin/env bash
# =====================================================================
# 配方售价精度 / 边界 / 旧数据降级 / 重复与连续操作 / 订单金额 · 验收脚本
# 用法：先 sh start.sh（需 --build 重建以应用新 schema）起好服务，
#       再 bash accept_recipe_price.sh
# 覆盖：
#   1. 规范售价统一两位小数落库 + 返回（列表/详情/出杯选择器同口径）
#   2. 边界金额 0.01 / 99999999.99 可过；0 / 负数 / 超两位小数 / 超上限稳定拒绝
#   3. 历史遗留多位小数(10.125) 与空价格：明确降级状态、不静默四舍五入、不可出杯、可修复
#   4. 重复提交（并发双击）只产生一条且结果唯一；连续快速操作最终状态一致
#   5. 多页面交替读取/编辑：过期版本稳定拒绝，载入最新后成功
#   6. 订单预览与落库金额 = 各配方规范售价 × 杯数（两位小数）
# 需要 curl 与 mysql 客户端（MYSQL_* 变量取自 .env）。
# =====================================================================
set -u
BASE="${BASE:-http://127.0.0.1:${BACKEND_PORT:-8413}}/api"
MPORT="${MYSQL_PORT:-3613}"
MUSER="${MYSQL_USER:-admin}"
MPASS="${MYSQL_PASSWORD:-password}"
MDB="${MYSQL_DATABASE:-milktea_bar}"
PASS=0; FAIL=0

ok()  { echo "✅ $*"; PASS=$((PASS+1)); }
bad() { echo "❌ $*"; FAIL=$((FAIL+1)); }

jqr() {
  python3 -c "import sys,json
d=json.load(sys.stdin)
d=d.get('data', d)
try:
    print($1)
except Exception:
    print('')"
}

get()  { curl -s "$BASE$1"; }
post() { curl -s -H 'Content-Type: application/json' -X POST "$BASE$1" -d "$2"; }
put()  { curl -s -H 'Content-Type: application/json' -X PUT "$BASE$1" -d "$2"; }

msql() { mysql -h127.0.0.1 -P"$MPORT" -u"$MUSER" -p"$MPASS" "$MDB" -N -e "$1" 2>/dev/null; }

# 给配方补一种启用小料（用已存在的「珍珠」），保证创建/更新不会因小料校验失败
mkbody() {
  # $1=name $2=price(数字或null) $3=version（省略则不带 version 字段，用于修复旧数据）
  local price="$2" verpart=""
  if [ "$#" -ge 3 ]; then verpart=",\"version\":$3"; fi
  printf '{"name":"%s","baseTea":"红茶","price":%s,"toppings":{"珍珠":1}%s}' \
    "$1" "$price" "$verpart"
}

echo "== 0. 基础数据与统一返回口径 =="
LIST=$(get /recipes)
echo "$LIST" | grep -q '"ok":true' && ok "配方列表可查" || bad "配方列表失败"
# 种子 1~5 全部 ok，且 JSON 中价格以两位小数数字 12.00 形式序列化（非科学计数法、非 12）
echo "$LIST" | grep -q '"price":12.00' && ok "列表 JSON 中价格保留两位小数（\"price\":12.00）" || bad "列表 JSON 价格序列化异常"
echo "$LIST" | python3 -c "
import sys,json
from decimal import Decimal
rs=json.load(sys.stdin, parse_float=Decimal)['data']
normal=[r for r in rs if r['id'] in (1,2,3,4,5)]
assert len(normal)==5 and all(r['priceStatus']=='ok' for r in normal), '种子配方状态异常'
prices={r['id']: r['price'] for r in normal}
assert prices[1]==Decimal('12.00') and prices[2]==Decimal('11.00'), prices
assert all(r['price'] == r['price'].quantize(Decimal('0.01')) for r in normal)
print('ok')" && ok "规范配方列表/详情价格统一为两位小数（12.00、11.00…）" \
           || bad "列表价格口径不统一"

# 详情接口与列表必须是同一个值（Decimal 比较，12.00 与 12.0 视为同值，展示口径由前端两位小数保证）
get /recipes/1 | python3 -c "
import sys,json
from decimal import Decimal
p=json.load(sys.stdin, parse_float=Decimal)['data']['price']
assert p==Decimal('12.00'), p
print('ok')" && ok "详情接口价格 = 列表价格（12.00）" || bad "详情价格不一致"

echo
echo "== 1. 边界金额 =="
LID=$(post /recipes "$(mkbody 边界下限001 0.01)" | jqr "d['id']")
[ -n "$LID" ] && ok "下边界 0.01 创建成功 #$LID" || bad "0.01 被错误拒绝"
get "/recipes/$LID" | grep -q '"price":0.01' && ok "0.01 落库与返回一致" || bad "0.01 落库异常"

HID=$(post /recipes "$(mkbody 边界上限999 99999999.99)" | jqr "d['id']")
[ -n "$HID" ] && ok "上边界 99999999.99 创建成功 #$HID" || bad "99999999.99 被错误拒绝"
get "/recipes/$HID" | grep -q '"price":99999999.99' && ok "上边界落库与返回一致" || bad "上边界落库异常"

# 0、负数、超两位小数、超上限 —— 稳定中文提示，且库里不多数据
BEFORE_CNT=$(msql "SELECT COUNT(*) FROM recipe;")
post /recipes "$(mkbody 拒绝零值000 0)"          | grep -q "必须大于 0"      && ok "零值稳定拒绝" || bad "零值未拒绝"
post /recipes "$(mkbody 拒绝负数001 -3.5)"      | grep -q "必须大于 0"      && ok "负数稳定拒绝" || bad "负数未拒绝"
post /recipes "$(mkbody 拒绝小数三位 10.125)"   | grep -q "两位小数"        && ok "超两位小数稳定拒绝（不四舍五入）" || bad "三位小数未拒绝"
post /recipes "$(mkbody 拒绝小数四位 10.1234)"  | grep -q "两位小数"        && ok "四位小数稳定拒绝" || bad "四位小数未拒绝"
post /recipes "$(mkbody 拒绝空价格 null)"       | grep -q "不能为空"        && ok "空价格稳定拒绝" || bad "空价格未拒绝"
post /recipes "$(mkbody 拒绝超上限 100000000)"  | grep -q "不能超过"        && ok "超上限稳定拒绝" || bad "超上限未拒绝"
post /recipes "$(mkbody 拒绝科学计数 1e9)"       | grep -q "不能超过"        && ok "科学计数超大数稳定拒绝" || bad "科学计数超大数未拒绝"
AFTER_CNT=$(msql "SELECT COUNT(*) FROM recipe;")
[ "$BEFORE_CNT" = "$AFTER_CNT" ] && ok "全部非法提交均未落库（数量保持 $AFTER_CNT）" || bad "非法价格落库了：$BEFORE_CNT -> $AFTER_CNT"

# 编辑路径同样校验（带当前版本，价格非法时整次提交失败、数据不变）
LV=$(get "/recipes/$LID" | jqr "d['version']")
put "/recipes/$LID" "$(mkbody 边界下限001 0.005 "$LV")" | grep -q "两位小数" && ok "编辑时三位小数同样被拒" || bad "编辑三位小数未拒绝"
get "/recipes/$LID" | grep -q '"price":0.01' && ok "非法编辑未落任何数据（仍为 0.01）" || bad "非法编辑后数据被改动"

echo
echo "== 2. 精度转换：整数 / 一位小数输入 → 两位小数落库 =="
TID=$(post /recipes "$(mkbody 精度整数12 12)" | jqr "d['id']")
get "/recipes/$TID" | grep -q '"price":12.00' && ok "整数 12 落库返回 12.00" || bad "整数精度转换失败"
DBT=$(msql "SELECT price FROM recipe WHERE id=$TID;")
[ "$DBT" = "12.00" ] && ok "数据库实存 12.00（DECIMAL(10,2)）" || bad "数据库实存异常：$DBT"
TID2=$(post /recipes "$(mkbody 精度一位12.5 12.5)" | jqr "d['id']")
get "/recipes/$TID2" | grep -q '"price":12.50' && ok "一位小数 12.5 落库返回 12.50" || bad "一位小数精度转换失败"

echo
echo "== 3. 历史遗留旧数据：多位小数 / 空价格显式降级 =="
# 种子里 6=10.1250 多位小数，7=空价格
LEGACY=$(get /recipes)
echo "$LEGACY" | python3 -c "
import sys,json
from decimal import Decimal
rs={r['id']: r for r in json.load(sys.stdin, parse_float=Decimal)['data']}
r6, r7 = rs[6], rs[7]
assert r6['price'] is None and r6['priceStatus']=='legacy-multi-decimal', r6
assert r6['priceRaw']==Decimal('10.1250'), r6
assert '两位小数' in r6['priceNote'] and '四舍五入' in r6['priceNote'], r6
assert r7['price'] is None and r7['priceStatus']=='legacy-empty' and r7['priceRaw'] is None, r7
assert '空价格' in r7['priceNote'], r7
print('ok')" && ok "多位小数(10.1250)/空价格均显式降级，原始值原样回显、未四舍五入" \
             || bad "旧数据降级状态不正确"
# 降级配方不可出杯：服务端预览/下单都拒绝
PV=$(post /cup-orders/preview '{"payType":"微信","items":[{"recipeId":6,"qty":1}]}')
echo "$PV" | grep -q '"feasible":false' && echo "$PV" | grep -q "两位小数" \
  && ok "多位小数配方预览被拦且给出降级原因" || bad "多位小数配方竟然可预览通过"
OR=$(post /cup-orders '{"payType":"微信","items":[{"recipeId":6,"qty":2}]}')
echo "$OR" | grep -q "两位小数" && ok "多位小数配方下单被拒（整单不落库）" || bad "多位小数配方下单未拒绝"
PV7=$(post /cup-orders/preview '{"payType":"微信","items":[{"recipeId":7,"qty":1}]}')
echo "$PV7" | grep -q '"feasible":false' && echo "$PV7" | grep -q "空价格" \
  && ok "空价格配方预览被拦" || bad "空价格配方预览未拦截"
# 出杯失败记录可追溯（下单失败留痕，记录请求明细配方名）
get /cup-orders/failures | grep -q "黑糖珍珠厚乳" && ok "降级配方下单失败已入失败记录" || bad "失败记录缺失"

# 修复旧数据：补填规范价后恢复 ok
FIX=$(put /recipes/6 "$(mkbody 黑糖珍珠厚乳（历史价） 10.50 0)")
echo "$FIX" | grep -q '"priceStatus":"ok"' && echo "$FIX" | grep -q '"price":10.50' \
  && ok "多位小数配方修复为 10.50 后恢复规范（人工确认，非系统四舍五入）" || bad "旧数据修复失败：$FIX"
FIX7=$(put /recipes/7 "$(mkbody 蜂蜜椰椰（历史空价） 9.90 0)")
echo "$FIX7" | grep -q '"price":9.90' && ok "空价格配方修复为 9.90 后恢复规范" || bad "空价格修复失败：$FIX7"
# 修复后可正常下单计价
post /cup-orders/preview '{"payType":"微信","items":[{"recipeId":6,"qty":1}]}' | grep -q '"feasible":true' \
  && ok "修复后的配方可正常预览出杯" || bad "修复后仍无法出杯"

echo
echo "== 4. 重复点击：同一表单并发双提交，只能成功一次 =="
DB_BEFORE=$(msql "SELECT COUNT(*) FROM recipe WHERE name='并发双击唯一';")
(
  post /recipes "$(mkbody 并发双击唯一 18.88)" > /tmp/d1.json
  post /recipes "$(mkbody 并发双击唯一 18.88)" > /tmp/d2.json
) &
(
  post /recipes "$(mkbody 并发双击唯一 18.88)" > /tmp/d3.json
  post /recipes "$(mkbody 并发双击唯一 18.88)" > /tmp/d4.json
) &
wait
DB_AFTER=$(msql "SELECT COUNT(*) FROM recipe WHERE name='并发双击唯一';")
[ "$DB_AFTER" = "1" ] && ok "并发 4 次同名提交最终只落 1 条（DB 计数 1）" || bad "重复提交产生 $DB_AFTER 条"
OKCNT=$(grep -l '"ok":true' /tmp/d1.json /tmp/d2.json /tmp/d3.json /tmp/d4.json 2>/dev/null | wc -l)
[ "$OKCNT" = "1" ] && ok "并发提交只有 1 个成功响应（其余被唯一约束稳定拒绝）" || bad "成功响应数异常：$OKCNT"

echo
echo "== 5. 连续快速操作后的最终状态 =="
CID=$(post /recipes "$(mkbody 连续操作目标 12.00)" | jqr "d['id']")
# 连续 5 次改名/改价，逐个带最新 version；最终值必须是最后一次
VID="$CID"; VVER=$(get "/recipes/$VID" | jqr "d['version']")
for i in 1 2 3 4 5; do
  RESP=$(put "/recipes/$VID" "$(mkbody 连续操作目标 12.0$i "$VVER")")
  VVER=$(echo "$RESP" | jqr "d['version']")
done
FINAL=$(get "/recipes/$CID")
echo "$FINAL" | grep -q '"price":12.05' && ok "连续 5 次更新后最终价格 = 12.05" || bad "连续操作最终价格错误"
VDB=$(msql "SELECT price, version FROM recipe WHERE id=$CID;")
echo "$VDB" | grep -q "12.05" && [ "$(echo "$VDB" | awk '{print $2}')" = "5" ] \
  && ok "数据库最终状态 12.05 / version=5" || bad "DB 最终状态异常：$VDB"

echo
echo "== 6. 多页面交替编辑：过期版本稳定拒绝，不后写覆盖先写 =="
XID=$(post /recipes "$(mkbody 版本冲突目标 20.00)" | jqr "d['id']")
V0=$(get "/recipes/$XID" | jqr "d['version']")
# 页面 A 先成功保存
RA=$(put "/recipes/$XID" "$(mkbody 版本冲突目标 21.00 "$V0")")
echo "$RA" | grep -q '"price":21.00' && ok "页面 A 首次保存成功（21.00）" || bad "页面 A 保存失败"
# 页面 B 仍持旧版本 v0 提交：必须稳定拒绝，且不得把 21.00 覆盖掉
RB=$(put "/recipes/$XID" "$(mkbody 版本冲突目标 22.00 "$V0")")
echo "$RB" | grep -q "其他页面" && ok "页面 B 过期版本被稳定拒绝" || bad "过期版本未拒绝：$RB"
get "/recipes/$XID" | grep -q '"price":21.00' && ok "拒绝过期提交后服务端仍为先写的 21.00" || bad "先写结果被覆盖"
# B 载入最新版本后重试成功
VN=$(get "/recipes/$XID" | jqr "d['version']")
put "/recipes/$XID" "$(mkbody 版本冲突目标 22.00 "$VN")" | grep -q '"price":22.00' \
  && ok "B 重新载入最新版本后保存成功（22.00）" || bad "B 重载后仍失败"

echo
echo "== 7. 订单金额：预览合计与落库总金额按规范售价精确计算 =="
# 配方1=12.00（2杯）+ 配方4=15.00（1杯）= 39.00
PT=$(post /cup-orders/preview '{"payType":"微信","items":[{"recipeId":1,"qty":2},{"recipeId":4,"qty":1}]}')
echo "$PT" | grep -q '"total":39.00' && ok "预览合计 12.00×2 + 15.00 = 39.00" || bad "预览合计错误：$(echo "$PT" | jqr "d['total']")"
# 重复明细合并：配方1 ×1 与 ×1 两条 = 24.00
post /cup-orders/preview '{"payType":"现金","items":[{"recipeId":1,"qty":1},{"recipeId":1,"qty":1}]}' \
  | grep -q '"total":24.00' && ok "重复明细合并计价 24.00" || bad "重复明细计价错误"
# 真实下单，校验落库 total 与种子历史订单金额仍为两位小数（配方1×2 用红茶 20g+珍珠4份+奶2份，库存充足）
OID=$(post /cup-orders '{"payType":"微信","items":[{"recipeId":1,"qty":2}]}' | jqr "d['id']")
[ -n "$OID" ] && ok "订单创建成功 #$OID" || bad "订单创建失败"
ODB=$(msql "SELECT total FROM cup_order WHERE id=$OID;")
[ "$ODB" = "24.00" ] && ok "订单落库合计 12.00×2 = 24.00（DECIMAL(10,2)，与预览一致）" || bad "订单落库金额异常：$ODB"
get /cup-orders | python3 -c "
import sys,json
from decimal import Decimal
os=json.load(sys.stdin, parse_float=Decimal)['data']
o=[x for x in os if x['id']==$OID][0]
assert o['total']==Decimal('24.00'), o
# 所有订单金额都是两位小数（含种子历史订单）
assert all(x['total'] == x['total'].quantize(Decimal('0.01')) for x in os)
print('ok')" && ok "订单列表金额返回口径与预览/落库完全一致" || bad "订单列表金额口径不一致"
# 会员积分 = 金额整数（12.00 → 12）；用新会员避免种子积分干扰
MNEW=$(post /members '{"phone":"13911112222","name":"金额积分验收","points":0}' | jqr "d['id']")
post /cup-orders "{\"memberId\":$MNEW,\"payType\":\"微信\",\"items\":[{\"recipeId\":1,\"qty\":1}]}" >/dev/null
msql "SELECT points FROM member WHERE id=$MNEW;" | grep -q "^12$" \
  && ok "会员订单积分按金额 12.00 计 12 分" || bad "积分计算异常"

echo
echo "================ 结果：PASS=$PASS FAIL=$FAIL ================"
exit $FAIL
