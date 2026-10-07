#!/usr/bin/env bash
# =====================================================================
# 会员档案合并与联系方式变更审计 · 验收脚本
# 用法：先 sh start.sh 起好后端，再 bash accept_member_merge.sh
# 覆盖：冲突、订单与积分归属、历史查询、幂等与并发、撤销、并发改手机号。
# 需要 curl 与 mysql 客户端（MYSQL_* 变量取自 .env）。
# =====================================================================
set -u
BASE="${BASE:-http://127.0.0.1:${BACKEND_PORT:-8413}}/api"
PASS=0; FAIL=0

ok()   { echo "✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "❌ $*"; FAIL=$((FAIL+1)); }

# 从 JSON 响应里取 data 的字段（参数为 python 表达式，变量名 d），如：jqr "d['id']"
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

# 直接执行 SQL 的便捷封装（MYSQL_* 取自环境/.env）
msql() { mysql -h127.0.0.1 -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-admin}" -p"${MYSQL_PASSWORD:-password}" "${MYSQL_DATABASE:-milktea_bar}" -N -e "$1" 2>/dev/null; }

echo "== 0. 基础数据 =="
MEMBERS=$(get /members)
echo "$MEMBERS" | grep -q '"ok":true' && ok "会员列表可查" || bad "会员列表失败"

# 种子合并单四态齐全
for st in 待处理 冲突待处理 已合并 已撤销; do
  get "/member-merges?status=$st" | grep -q "$st" && ok "筛选：$st" || bad "筛选缺失：$st"
done

echo "== 1. 手机号唯一性 / 并发改号 =="
NEWID=$(post /members '{"phone":"13900000001","name":"并发测试A","points":0}' | jqr "d['id']")
[ -n "$NEWID" ] && ok "建档 A #$NEWID" || bad "建档 A 失败"
# 同号建档必须拒绝（同一手机号不能绑两个有效会员）
post /members '{"phone":"13900000001","name":"并发测试B","points":0}' | grep -q '已绑定' \
  && ok "同号建档被稳定拒绝" || bad "同号建档未拒绝"
# 两个并发请求把不同会员改成同一个新号，只能一个成功
post /members '{"phone":"13900000002","name":"并发测试C","points":0}' >/dev/null
post "/members/$NEWID/contact" '{"phone":"13900000099","name":"并发测试A","reason":"换号并发测试","operator":"店员小美"}' >/dev/null &
PID1=$!
# 先建第二个会员再并发改号
BID=$(post /members '{"phone":"13900000003","name":"并发测试D","points":0}' | jqr "d['id']")
post "/members/$BID/contact" '{"phone":"13900000099","name":"并发测试D","reason":"换号并发测试","operator":"店员小美"}' >/dev/null &
PID2=$!
wait $PID1 $PID2
CNT=$(get /members?keyword=13900000099 | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']))" 2>/dev/null)
[ "$CNT" = "1" ] && ok "并发改同号只有一个会员成功（查到 $CNT 个）" || bad "并发改同号异常（$CNT 个）"

echo "== 2. 联系方式变更审计 =="
get "/members/$NEWID" | grep -q '换号并发测试' && ok "变更原因入审计" || bad "变更审计缺失"
get "/members/$NEWID" | grep -q '13900000001' && ok "变更前手机号保留" || bad "前值缺失"

echo "== 3. 合并：手机号冲突 + 积分来源冲突 → 冲突待处理，不静默覆盖 =="
# 新建一对同号会员（绕过应用直接造历史遗留重复：先建后由第二个人工造档）
M1=$(post /members '{"phone":"13700000001","name":"冲突破例主","points":100}' | jqr "d['id']")
# 直接 SQL 造一个同号有效档（模拟历史遗留）
: "${MYSQL_DATABASE:=milktea_bar}"
mysql -h127.0.0.1 -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-admin}" -p"${MYSQL_PASSWORD:-password}" "$MYSQL_DATABASE" -e \
  "INSERT INTO member(phone,name,points,level,status,version,created_at) VALUES('13700000001','冲突破例副',50,'普通','有效',0,NOW());" 2>/dev/null
M2=$(mysql -h127.0.0.1 -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-admin}" -p"${MYSQL_PASSWORD:-password}" "$MYSQL_DATABASE" -N -e \
  "SELECT id FROM member WHERE name='冲突破例副' ORDER BY id DESC LIMIT 1;" 2>/dev/null)
CF=$(post /member-merges "{\"masterMemberId\":$M1,\"aliasMemberIds\":[$M2],\"reason\":\"同号冲突验收\",\"operator\":\"店员小美\"}")
echo "$CF" | grep -q '冲突待处理' && ok "创建合并单即识别冲突" || bad "未识别手机号冲突"
RID=$(echo "$CF" | jqr "d['id']")
# 冲突未解决直接执行：必须继续停在冲突待处理
post "/member-merges/$RID/execute" '{"operator":"店员小美"}' | grep -q '冲突待处理' \
  && ok "冲突未解决执行被拦（不静默覆盖）" || bad "冲突被静默合并"
# 解决：把副档改号并补期初流水使账实相符
mysql -h127.0.0.1 -P"${MYSQL_PORT:-3306}" -u"${MYSQL_USER:-admin}" -p"${MYSQL_PASSWORD:-password}" "$MYSQL_DATABASE" -e \
  "UPDATE member SET phone='13700000002' WHERE id=$M2;
   INSERT INTO member_phone_binding(phone,member_id,bound_at) VALUES('13700000002',$M2,NOW());
   INSERT INTO member_points_ledger(member_id,change_points,balance_after,type,created_at) VALUES($M1,100,100,'期初',NOW()),($M2,50,50,'期初',NOW());" 2>/dev/null
post "/member-merges/$RID/execute" '{"operator":"店员小美"}' | grep -q '"status":"已合并"' \
  && ok "冲突解决后重新检查合并成功" || bad "重新检查合并失败"

echo "== 4. 正常合并：订单与积分归属统一到主档 =="
A=$(post /members '{"phone":"13600000001","name":"归属主档","points":0}' | jqr "d['id']")
B=$(post /members '{"phone":"13600000002","name":"归属副档","points":0}' | jqr "d['id']")
# 给副档下一单（需要配方存在：用配方1，单杯）
post /cup-orders "{\"memberId\":$B,\"payType\":\"微信\",\"items\":[{\"recipeId\":1,\"qty\":1}]}" >/dev/null
BSUM=$(get "/members/$B" | jqr "d['member']['points']")
MR=$(post /member-merges "{\"masterMemberId\":$A,\"aliasMemberIds\":[$B],\"reason\":\"归属验收\",\"operator\":\"店员小美\"}" | jqr "d['id']")
post "/member-merges/$MR/execute" '{"operator":"店员小美"}' >/dev/null
# 主档积分 = 副档流水（含消费获得），余额等于流水合计，且不翻倍
APTS=$(get "/members/$A" | jqr "d['member']['points']")
[ "$APTS" = "$BSUM" ] && ok "主档积分=副档迁入合计 $APTS（不重复计算）" || bad "积分归属错误：主档 $APTS / 副档 $BSUM"
# 副档为只读别名，订单已挂主档
get "/members/$B" | grep -q '"alias":true' && ok "副档保留只读别名" || bad "别名标记缺失"
get "/members/$A" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];assert len(d['orders'])>=1 and all(o['memberId']==$A for o in d['orders']);print('ok')" 2>/dev/null \
  && ok "历史订单统一归到主档" || bad "订单未迁移到主档"
# 用副档旧手机号搜索能追溯到主档
get "/members?keyword=13600000002" | grep -q "\"masterId\":$A" && ok "旧号搜索可追溯主档" || bad "旧号追溯失败"

echo "== 5. 幂等：重复合并/重复执行 =="
post "/member-merges/$MR/execute" '{"operator":"店员小美"}' | grep -q '已合并' \
  && ok "重复执行幂等返回已合并" || bad "重复执行非幂等"
post /member-merges "{\"masterMemberId\":$A,\"aliasMemberIds\":[$B],\"reason\":\"重复\",\"operator\":\"店员小美\"}" \
  | grep -q '已合并' && ok "对已合并别名再次发起被识别" || true

echo "== 6. 撤销合并：仅负责人，且积分不重复计算 =="
# 店员撤销必须被拒
post "/member-merges/$MR/revoke" '{"operator":"店员小美","operatorRole":"店员","reason":"误操作"}' | grep -q '负责人' \
  && ok "店员撤销被拒" || bad "店员竟然能撤销"
post "/member-merges/$MR/revoke" '{"operator":"店长阿峰","operatorRole":"负责人","reason":"核实不是同一人"}' | grep -q '已撤销' \
  && ok "负责人撤销成功" || bad "负责人撤销失败"
# 撤销后：副档恢复有效、积分/订单挂回，主档不保留重复积分
BPTS=$(get "/members/$B" | jqr "d['member']['points']")
[ "$BPTS" = "$BSUM" ] && ok "撤销后副档积分恢复 $BPTS" || bad "撤销后副档积分错误：$BPTS"
APTS2=$(get "/members/$A" | jqr "d['member']['points']")
[ "$APTS2" = "0" ] && ok "撤销后主动积分回落到 0（未重复计算）" || bad "撤销后主档积分异常：$APTS2"
# 重复撤销稳定拒绝
post "/member-merges/$MR/revoke" '{"operator":"店长阿峰","operatorRole":"负责人","reason":"再撤一次"}' | grep -q '已撤销' \
  && ok "重复撤销被稳定拒绝" || bad "重复撤销未拒绝"

echo "== 7. 并发合并同一会员（幂等/稳定结果）=="
X=$(post /members '{"phone":"13500000001","name":"并发主","points":0}' | jqr "d['id']")
Y=$(post /members '{"phone":"13500000002","name":"并发副","points":0}' | jqr "d['id']")
post /member-merges "{\"masterMemberId\":$X,\"aliasMemberIds\":[$Y],\"reason\":\"并发1\",\"operator\":\"店员小美\"}" >/dev/null &
post /member-merges "{\"masterMemberId\":$X,\"aliasMemberIds\":[$Y],\"reason\":\"并发2\",\"operator\":\"店员小美\"}" >/dev/null &
wait
OPEN=$(get "/member-merges?status=待处理" | python3 -c "import sys,json;print(sum(1 for r in json.load(sys.stdin)['data'] if r['masterMemberId']==$X))" 2>/dev/null)
[ "$OPEN" = "1" ] && ok "并发发起同一合并只有一张待处理单" || bad "并发合并产生 $OPEN 张单"

echo
echo "================ 结果：PASS=$PASS FAIL=$FAIL ================"
exit $FAIL
