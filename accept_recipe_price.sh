#!/usr/bin/env bash
# =====================================================================
# 配方售价统一金额口径 · 验收脚本
# 用法：先 sh start.sh 起好后端，再 bash accept_recipe_price.sh
# 覆盖：边界金额、精度转换、旧数据（多位小数/空售价）降级回显、重复请求、
#       连续/并发操作后的最终状态、订单金额计算与多接口显示一致性。
# 需要 curl、python3 与 mysql 客户端（MYSQL_* 变量取自 .env）。
# =====================================================================
set -u
cd "$(dirname "$0")"
[ -f .env ] && { set -a; . ./.env; set +a; }

BASE="${BASE:-http://127.0.0.1:${BACKEND_PORT:-8413}}/api"
SFX="$$"   # 本次运行后缀，保证可重复执行
PASS=0; FAIL=0

ok()   { echo "✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "❌ $*"; FAIL=$((FAIL+1)); }

# 从 JSON 响应里取 data 的字段（参数为 python 表达式，变量名 d）
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
put()  { curl -s -H 'Content-Type: application/json' -X PUT  "$BASE$1" -d "$2"; }

msql() { mysql -h127.0.0.1 -P"${MYSQL_PORT:-3613}" -u"${MYSQL_USER:-admin}" -p"${MYSQL_PASSWORD:-password}" "${MYSQL_DATABASE:-milktea_bar}" -N -e "$1" 2>/dev/null; }

# 新建配方（小料固定 珍珠×1，茶底可指定），返回 id
mkrecipe() { # name baseTea priceJson
  post /recipes "{\"name\":\"$1\",\"baseTea\":\"$2\",\"price\":$3,\"toppings\":{\"珍珠\":1}}" | jqr "d['id']"
}

# 记录本脚本创建的配方 id，收尾清理
CREATED_RECIPES=""
track() { CREATED_RECIPES="$CREATED_RECIPES $1"; }

echo "== 1. 边界金额：0.01 / 0 / 负数 / 上限 / 超上限 / 空 =="
R_MIN=$(mkrecipe "边界下限_$SFX" 绿茶 0.01)
[ -n "$R_MIN" ] && { ok "0.01 可创建（id=$R_MIN）"; track "$R_MIN"; } || bad "0.01 创建失败"
post /recipes "{\"name\":\"零值_$SFX\",\"baseTea\":\"绿茶\",\"price\":0,\"toppings\":{\"珍珠\":1}}" \
  | grep -q '必须大于 0' && ok "零值被稳定拒绝" || bad "零值未拒绝"
post /recipes "{\"name\":\"负数_$SFX\",\"baseTea\":\"绿茶\",\"price\":-3,\"toppings\":{\"珍珠\":1}}" \
  | grep -q '必须大于 0' && ok "负数被稳定拒绝" || bad "负数未拒绝"
R_MAX=$(mkrecipe "边界上限_$SFX" 绿茶 99999999.99)
[ -n "$R_MAX" ] && { ok "99999999.99 可创建（id=$R_MAX）"; track "$R_MAX"; } || bad "上限金额创建失败"
post /recipes "{\"name\":\"超上限_$SFX\",\"baseTea\":\"绿茶\",\"price\":100000000,\"toppings\":{\"珍珠\":1}}" \
  | grep -q '不能超过' && ok "超上限被稳定拒绝（非 500）" || bad "超上限未拒绝"
post /recipes "{\"name\":\"空售价_$SFX\",\"baseTea\":\"绿茶\",\"toppings\":{\"珍珠\":1}}" \
  | grep -q '不能为空' && ok "缺省售价被稳定拒绝" || bad "缺省售价未拒绝"
post /recipes "{\"name\":\"空串售价_$SFX\",\"baseTea\":\"绿茶\",\"price\":\"\",\"toppings\":{\"珍珠\":1}}" \
  | grep -q '不能为空' && ok "空串售价被稳定拒绝" || bad "空串售价未拒绝"

echo "== 2. 精度转换：超两位小数拒绝；合法值统一规范化为两位小数 =="
post /recipes "{\"name\":\"三位小数_$SFX\",\"baseTea\":\"绿茶\",\"price\":12.345,\"toppings\":{\"珍珠\":1}}" \
  | grep -q '两位小数' && ok "12.345 被稳定拒绝" || bad "12.345 未拒绝"
post /recipes "{\"name\":\"三位小数字符串_$SFX\",\"baseTea\":\"绿茶\",\"price\":\"12.345\",\"toppings\":{\"珍珠\":1}}" \
  | grep -q '两位小数' && ok "\"12.345\" 字符串同样被拒绝" || bad "字符串三位小数未拒绝"
R_P=$(mkrecipe "精度规范_$SFX" 绿茶 12.5)
track "$R_P"
[ -n "$R_P" ] && ok "12.5 可创建（id=$R_P）" || bad "12.5 创建失败"
# 创建响应即规范化：12.50（而不是 12.5）
post /recipes "{\"name\":\"精度规范2_$SFX\",\"baseTea\":\"绿茶\",\"price\":12.5,\"toppings\":{\"珍珠\":1}}" \
  | grep -q '"price":12.50' && ok "创建响应即返回规范值 12.50" || bad "创建响应未规范化"
R_P2=$(get /recipes | jqr "[r['id'] for r in d if r['name']=='精度规范2_$SFX'][0]")
track "$R_P2"
# 列表 / 详情一致：同一配方任何入口都是 12.50
get /recipes | python3 -c "
import sys, json
rs = json.load(sys.stdin)['data']
r = [x for x in rs if x['id'] == $R_P][0]
assert r['price'] == 12.5 and r['priceStatus'] == '正常', r
print('ok')" >/dev/null && ok "列表中 12.5 显示为规范值且状态正常" || bad "列表规范值异常"
get /recipes | grep -o '"price":12.50' | head -1 | grep -q '12.50' \
  && ok "列表 JSON 原始精度为两位小数（12.50）" || bad "列表 JSON 精度不统一"
# 编辑路径同样规范化：12.500 → 12.50；7 → 7.00
put "/recipes/$R_P" '{"price":"12.500"}' | grep -q '"price":12.50' \
  && ok "编辑 12.500 规范化为 12.50（等值规范化，非四舍五入）" || bad "编辑 12.500 未规范化"
put "/recipes/$R_P" '{"price":7}' | grep -q '"price":7.00' \
  && ok "编辑 7 规范化为 7.00" || bad "编辑 7 未规范化"
put "/recipes/$R_P" '{"price":12.345}' | grep -q '两位小数' \
  && ok "编辑路径同样拒绝三位小数" || bad "编辑路径未拒绝三位小数"

echo "== 3. 旧数据回显：多位小数 / 空售价 → 明确降级，不静默四舍五入 =="
msql "INSERT INTO recipe (name, base_tea, price, created_at) VALUES ('遗留多位小数_$SFX', '红茶', 12.345, NOW());"
msql "INSERT INTO recipe (name, base_tea, price, created_at) VALUES ('遗留空售价_$SFX', '红茶', NULL, NOW());"
LEG1=$(msql "SELECT id FROM recipe WHERE name='遗留多位小数_$SFX';")
LEG2=$(msql "SELECT id FROM recipe WHERE name='遗留空售价_$SFX';")
msql "INSERT INTO recipe_topping (recipe_id, topping_name, qty) VALUES ($LEG1, '珍珠', 1), ($LEG2, '珍珠', 1);"
[ -n "$LEG1" ] && [ -n "$LEG2" ] && ok "已造历史遗留数据（#$LEG1=12.345, #$LEG2=NULL）" || bad "遗留数据构造失败"
LIST=$(get /recipes)
echo "$LIST" | python3 -c "
import sys, json
rs = json.load(sys.stdin)['data']
a = [x for x in rs if x['id'] == $LEG1][0]
b = [x for x in rs if x['id'] == $LEG2][0]
assert a['priceStatus'] == '异常' and abs(a['price'] - 12.345) < 1e-9 and a['priceIssue'], a
assert b['priceStatus'] == '异常' and b['price'] is None and b['priceIssue'], b
print('ok')" >/dev/null && ok "遗留数据标记「异常」降级状态并保留原始值" || bad "遗留数据未降级标记"
echo "$LIST" | grep -q '12.345' && ok "原始值 12.345 原样返回（未静默四舍五入为 12.35）" || bad "原始值被静默改写"
# 出杯预览/下单对异常配方稳定拒绝（而不是 500 或静默按错误价格计算）
PREV=$(post /cup-orders/preview "{\"payType\":\"微信\",\"items\":[{\"recipeId\":$LEG1,\"qty\":1}]}")
echo "$PREV" | grep -q '"feasible":false' && echo "$PREV" | grep -q '售价数据异常' \
  && ok "预览对异常售价配方给出降级问题提示" || bad "预览未处理异常售价"
post /cup-orders "{\"payType\":\"微信\",\"items\":[{\"recipeId\":$LEG1,\"qty\":1}]}" \
  | grep -q '售价数据异常' && ok "下单对异常售价配方稳定 400 拒绝" || bad "下单未拒绝异常售价"
get /cup-orders/failures | grep -q '售价数据异常' \
  && ok "失败原因已留痕（售价数据异常）" || bad "失败记录缺失"
# 修正后恢复正常
put "/recipes/$LEG1" '{"price":12.34}' | grep -q '"priceStatus":"正常"' \
  && ok "编辑修正后状态恢复「正常」（12.34）" || bad "修正后状态异常"
post /cup-orders/preview "{\"payType\":\"微信\",\"items\":[{\"recipeId\":$LEG1,\"qty\":1}]}" \
  | grep -q '"feasible":true' && ok "修正后可正常出杯预览" || bad "修正后预览仍不可行"

echo "== 4. 重复请求：重复创建 / 重复提交编辑 =="
DUP_PAYLOAD="{\"name\":\"重复提交_$SFX\",\"baseTea\":\"绿茶\",\"price\":9.9,\"toppings\":{\"珍珠\":1}}"
R_DUP=$(post /recipes "$DUP_PAYLOAD" | jqr "d['id']")
[ -n "$R_DUP" ] && { ok "首次创建成功（id=$R_DUP）"; track "$R_DUP"; } || bad "首次创建失败"
post /recipes "$DUP_PAYLOAD" | grep -q '已存在' && ok "重复创建被稳定拒绝（名称已存在）" || bad "重复创建未拒绝"
put "/recipes/$R_DUP" '{"price":9.90}' >/dev/null
put "/recipes/$R_DUP" '{"price":9.90}' >/dev/null
FINAL_DUP=$(get /recipes | jqr "[r['price'] for r in d if r['id']==$R_DUP][0]")
[ "$FINAL_DUP" = "9.9" ] && ok "重复相同编辑幂等，最终值稳定 9.90" || bad "重复编辑后值异常：$FINAL_DUP"

echo "== 5. 连续 / 并发操作后的最终状态一致 =="
R_SEQ=$(mkrecipe "连续操作_$SFX" 绿茶 11.11)
track "$R_SEQ"
put "/recipes/$R_SEQ" '{"price":22.22}' >/dev/null
put "/recipes/$R_SEQ" '{"price":33.33}' >/dev/null
API_V=$(get /recipes | jqr "[r['price'] for r in d if r['id']==$R_SEQ][0]")
DB_V=$(msql "SELECT price FROM recipe WHERE id=$R_SEQ;")
[ "$API_V" = "33.33" ] && [ "$DB_V" = "33.3300" ] \
  && ok "连续编辑后 API=33.33、DB=33.3300（同一规范值）" || bad "连续编辑后不一致：API=$API_V DB=$DB_V"
# 出杯预览金额跟随最新售价（33.33×2=66.66）
post /cup-orders/preview "{\"payType\":\"微信\",\"items\":[{\"recipeId\":$R_SEQ,\"qty\":2}]}" \
  | grep -q '"total":66.66' && ok "预览合计按最新售价计算（66.66）" || bad "预览合计未跟随最新售价"
# 并发编辑两个不同值：最终 API 与 DB 一致，且是两者之一
put "/recipes/$R_SEQ" '{"price":44.44}' >/dev/null &
put "/recipes/$R_SEQ" '{"price":55.55}' >/dev/null &
wait
API_C=$(get /recipes | jqr "[r['price'] for r in d if r['id']==$R_SEQ][0]")
DB_C=$(msql "SELECT price FROM recipe WHERE id=$R_SEQ;")
python3 -c "
api, db = float('$API_C'), float('$DB_C')
assert api == db and api in (44.44, 55.55), (api, db)
print('ok')" >/dev/null && ok "并发编辑后 API 与 DB 一致（$API_C），无中间态" || bad "并发编辑后不一致：API=$API_C DB=$DB_C"

echo "== 6. 订单金额计算：预览 / 下单 / 列表 / 积分一致 =="
R_A=$(mkrecipe "金额甲_$SFX" 绿茶 12.05); track "$R_A"
R_B=$(mkrecipe "金额乙_$SFX" 乌龙茶 7.90); track "$R_B"
# 12.05×2 + 7.90×1 = 32.00（BigDecimal 精确计算，无浮点误差）
PREV=$(post /cup-orders/preview "{\"memberId\":6,\"payType\":\"微信\",\"items\":[{\"recipeId\":$R_A,\"qty\":2},{\"recipeId\":$R_B,\"qty\":1}]}")
echo "$PREV" | grep -q '"total":32.00' && ok "预览合计 32.00（精确到分）" || bad "预览合计异常：$(echo "$PREV" | jqr "d['total']")"
echo "$PREV" | grep -q '"pointsToEarn":32' && ok "预览积分 +32" || bad "预览积分异常"
# 重复明细合并后金额不变（甲×1 + 甲×1 + 乙×1 与 甲×2 + 乙×1 同价）
ORDER=$(post /cup-orders "{\"memberId\":6,\"payType\":\"微信\",\"items\":[{\"recipeId\":$R_A,\"qty\":1},{\"recipeId\":$R_A,\"qty\":1},{\"recipeId\":$R_B,\"qty\":1}]}")
OID=$(echo "$ORDER" | jqr "d['id']")
echo "$ORDER" | grep -q '"total":32.00' && ok "下单金额 32.00（重复明细已合并）" || bad "下单金额异常"
[ -n "$OID" ] && ok "出杯单已生成（id=$OID）" || bad "出杯单生成失败"
get /cup-orders | python3 -c "
import sys, json
o = [x for x in json.load(sys.stdin)['data'] if x['id'] == $OID][0]
assert abs(o['total'] - 32.00) < 1e-9 and o['pointsEarned'] == 32, o
print('ok')" >/dev/null && ok "订单列表金额/积分与预览一致（32.00 / +32）" || bad "订单列表金额不一致"
PTS=$(get /members/6 | jqr "d['member']['points']")
[ "$PTS" = "82" ] && ok "会员积分 50 → 82（按金额累加）" || bad "会员积分异常：$PTS"
# 退款回退：积分恢复、库存回补，金额展示不受影响
post "/cup-orders/$OID/refund" '{}' | grep -q '已退款' && ok "退款成功" || bad "退款失败"
PTS2=$(get /members/6 | jqr "d['member']['points']")
[ "$PTS2" = "50" ] && ok "退款后积分回退到 50" || bad "退款后积分异常：$PTS2"

echo "== 7. 收尾清理本脚本造数 =="
for rid in $CREATED_RECIPES $LEG1 $LEG2; do
  [ -n "$rid" ] && msql "DELETE FROM recipe_topping WHERE recipe_id=$rid; DELETE FROM recipe WHERE id=$rid;"
done
[ -n "$OID" ] && msql "DELETE FROM cup_order_stock_snapshot WHERE order_id=$OID;
                       DELETE FROM cup_order_item WHERE order_id=$OID;
                       DELETE FROM cup_order WHERE id=$OID;
                       DELETE FROM member_points_ledger WHERE ref_type='订单' AND ref_id=$OID;"
msql "DELETE FROM cup_order_failure WHERE request_items LIKE '%_$SFX%';"
LEFT=$(msql "SELECT COUNT(*) FROM recipe WHERE name LIKE '%_$SFX%';")
[ "$LEFT" = "0" ] && ok "测试配方已清理" || bad "测试配方残留 $LEFT 条"

echo
echo "================ 结果：PASS=$PASS FAIL=$FAIL ================"
exit $FAIL
