package com.milktea.bar.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.dto.CupOrderCreateRequest;
import com.milktea.bar.dto.CupOrderCreateRequest.CupOrderItemInput;
import com.milktea.bar.dto.CupOrderPreview;
import com.milktea.bar.dto.CupOrderPreview.DemandLine;
import com.milktea.bar.entity.CupOrder;
import com.milktea.bar.entity.CupOrderFailure;
import com.milktea.bar.entity.CupOrderItem;
import com.milktea.bar.entity.Ingredient;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.Recipe;
import com.milktea.bar.entity.StockDeductionSnapshot;
import com.milktea.bar.repository.CupOrderFailureRepository;
import com.milktea.bar.repository.CupOrderRepository;
import com.milktea.bar.repository.IngredientRepository;
import com.milktea.bar.repository.MemberRepository;
import com.milktea.bar.repository.RecipeRepository;

@Service
public class CupOrderService {

    private final CupOrderRepository cupOrderRepository;
    private final CupOrderFailureRepository failureRepository;
    private final RecipeRepository recipeRepository;
    private final MemberRepository memberRepository;
    private final IngredientRepository ingredientRepository;
    private final CupOrderFailureRecorder failureRecorder;
    private final PointsLedgerService ledgerService;

    public CupOrderService(CupOrderRepository cupOrderRepository,
                           CupOrderFailureRepository failureRepository,
                           RecipeRepository recipeRepository,
                           MemberRepository memberRepository,
                           IngredientRepository ingredientRepository,
                           CupOrderFailureRecorder failureRecorder,
                           PointsLedgerService ledgerService) {
        this.cupOrderRepository = cupOrderRepository;
        this.failureRepository = failureRepository;
        this.recipeRepository = recipeRepository;
        this.memberRepository = memberRepository;
        this.ingredientRepository = ingredientRepository;
        this.failureRecorder = failureRecorder;
        this.ledgerService = ledgerService;
    }

    public List<CupOrder> list(String status) {
        if (status != null) {
            return cupOrderRepository.findByStatusOrderByCreatedAtDescIdDesc(status);
        }
        return cupOrderRepository.findAllByOrderByCreatedAtDescIdDesc();
    }

    public List<CupOrderFailure> listFailures() {
        return failureRepository.findAllByOrderByCreatedAtDescIdDesc();
    }

    // ============================ 出杯预览 ============================

    /**
     * 出杯前预览：合并多条配方/重复明细，汇总每个原料的本单需求，
     * 返回预计扣减、不足/停用原料与全部问题。仅用于页面提示，不落任何数据。
     */
    public CupOrderPreview preview(CupOrderCreateRequest req) {
        Plan plan = buildPlan(req, false);

        CupOrderPreview dto = new CupOrderPreview();
        dto.total = plan.total;
        dto.problems = plan.problems;
        dto.feasible = plan.problems.isEmpty();
        dto.pointsToEarn = (plan.member != null && dto.feasible)
                ? plan.total.multiply(BigDecimal.valueOf(Dict.POINTS_PER_YUAN)).intValue()
                : 0;

        List<DemandLine> lines = new ArrayList<>();
        for (MergedLine line : plan.needByName.values()) {
            Ingredient ing = line.ingredient;
            lines.add(new DemandLine(
                    line.name,
                    ing == null ? null : ing.category,
                    ing == null ? null : ing.unit,
                    line.need,
                    ing == null ? null : ing.stock,
                    ing == null ? null : ing.status,
                    ing == null || !"启用".equals(ing.status),
                    ing != null && "启用".equals(ing.status) && ing.stock < line.need));
        }
        dto.demands = lines;
        return dto;
    }

    // ============================ 出杯下单 ============================

    /**
     * 出杯下单（单事务）：
     * <ol>
     *   <li>合并重复明细，按配方的茶底 + 小料汇总本单原料需求；</li>
     *   <li>对涉及原料行、会员行加悲观写锁，以最新库存复核
     *       （不足 / 停用 / 配方引用失效任一不满足即整单回滚：订单、明细、库存、积分互不半扣）；</li>
     *   <li>全部满足时同事务扣减库存、保存原料扣减快照、保存订单明细、累加会员积分。</li>
     * </ol>
     * 校验失败只额外写入一条 cup_order_failure 审计记录（独立事务），订单本体不落库。
     */
    @Transactional(rollbackFor = Exception.class)
    public CupOrder create(CupOrderCreateRequest req) {
        List<String> basic = basicProblems(req);
        if (!basic.isEmpty()) {
            // 畸形请求（空明细/支付方式非法/杯数非法）：直接 400，不做库存判定也不记失败单
            throw new BizException(String.join("；", basic));
        }

        Plan plan = buildPlan(req, true);
        if (!plan.problems.isEmpty()) {
            String reason = String.join("；", plan.problems);
            // 独立事务落审计：主事务随即回滚，失败记录依然可追溯
            failureRecorder.record(plan.memberId, plan.memberLabel, plan.payType, plan.requestItems, reason);
            throw new BizException(reason);
        }

        // —— 全部满足：库存、快照、订单、积分在同一事务内提交 ——
        List<StockDeductionSnapshot> snapshots = new ArrayList<>();
        for (MergedLine line : plan.needByName.values()) {
            Ingredient ing = line.ingredient;
            if (ing.stock < line.need) {
                // 兜底：加锁后仍不足则拒绝（理论上不会发生）
                throw new BizException("原料「" + line.name + "」库存不足（当前 " + ing.stock + "，需 " + line.need + "）");
            }
            ing.stock = ing.stock - line.need;
            StockDeductionSnapshot snap = new StockDeductionSnapshot();
            snap.ingredientId = ing.id;
            snap.ingredientName = ing.name;
            snap.unit = ing.unit;
            snap.usedQty = line.need;
            snapshots.add(snap);
        }
        ingredientRepository.saveAll(
                plan.needByName.values().stream().map(l -> l.ingredient).toList());

        // —— 先落订单（会员积分流水需要订单号作为关联），全部在同一事务内提交 ——
        int pointsEarned = 0;
        if (plan.member != null) {
            pointsEarned = plan.total.multiply(BigDecimal.valueOf(Dict.POINTS_PER_YUAN)).intValue();
        }

        CupOrder order = new CupOrder();
        order.memberId = plan.memberId;
        order.payType = plan.payType;
        order.total = plan.total;
        order.status = "已出杯";
        order.pointsEarned = pointsEarned;
        order.createdAt = LocalDateTime.now();
        for (Map.Entry<Long, Integer> e : plan.recipeCups.entrySet()) {
            CupOrderItem item = new CupOrderItem();
            item.recipeId = e.getKey();
            item.qty = e.getValue();
            item.order = order;
            order.items.add(item);
        }
        for (StockDeductionSnapshot snap : snapshots) {
            snap.order = order;
            order.deductions.add(snap);
        }
        cupOrderRepository.save(order);

        if (plan.member != null) {
            // 积分与流水同事务落库（会员行已在 buildPlan 中加锁），余额恒等于流水合计
            ledgerService.append(plan.member, pointsEarned, Dict.LEDGER_EARN, "订单", order.id,
                    null, null);
            plan.member.level = Dict.decideMemberLevel(plan.member.points);
            memberRepository.save(plan.member);
        }
        return order;
    }

    // ============================ 退款 ============================

    /**
     * 退款（单事务 + 订单行写锁）：仅「已出杯」可退且只退一次。
     * 有快照的新订单严格按快照逐行精确回补原料、并逐行登记 refund_qty；
     * 历史无快照旧订单只回退积分，不凭空回补库存。
     * 重复点击 / 并发退款因订单状态已变而得到稳定拒绝，不会重复回补。
     */
    @Transactional(rollbackFor = Exception.class)
    public CupOrder refund(Long id) {
        CupOrder order = cupOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("出杯记录不存在：" + id));
        if (!"已出杯".equals(order.status)) {
            throw new BizException("订单当前状态为「" + order.status + "」，仅「已出杯」订单可以退款，请勿重复操作");
        }

        int restoredKinds = 0;
        if (!order.deductions.isEmpty()) {
            // 按原料 id 升序逐个加写锁，与下单保持一致的加锁顺序，避免交叉死锁
            List<StockDeductionSnapshot> snaps = order.deductions.stream()
                    .sorted(Comparator.comparing(s -> s.ingredientId))
                    .toList();
            for (StockDeductionSnapshot snap : snaps) {
                if (snap.refundQty != null) {
                    // 同一订单内出现已回补行，说明此前已退过：整单中止，不重复回补
                    throw new BizException("该订单已退款，请勿重复操作");
                }
                // SELECT ... FOR UPDATE，与下单路径同一加锁顺序
                Ingredient ing = ingredientRepository.findByIdForUpdate(snap.ingredientId).orElse(null);
                if (ing == null) {
                    // 原料被物理删除时无法保证精确回补，宁可拒绝也不得凭空回补
                    throw new BizException("快照原料「" + snap.ingredientName
                            + "」已不存在，无法精确回补库存，退款中止");
                }
                ing.stock = ing.stock + snap.usedQty;
                ingredientRepository.save(ing);
                snap.refundQty = snap.usedQty;
                restoredKinds++;
            }
        }

        int rollback = (order.pointsEarned == null) ? 0 : order.pointsEarned;
        if (order.memberId != null && rollback > 0) {
            Member member = memberRepository.findByIdForUpdate(order.memberId);
            if (member == null) {
                throw new BizException("关联会员不存在：" + order.memberId);
            }
            // 退款回退记流水；若历史余额不足则拒绝，避免出现负积分
            if (member.points - rollback < 0) {
                throw new BizException("会员当前积分 " + member.points + " 不足回退 "
                        + rollback + "，退款中止（请人工核实积分来源）");
            }
            ledgerService.append(member, -rollback, Dict.LEDGER_REFUND, "订单", order.id,
                    null, null);
            member.points = Math.max(0, member.points);
            member.level = Dict.decideMemberLevel(member.points);
            memberRepository.save(member);
            memberRepository.save(member);
        }

        order.status = "已退款";
        CupOrder saved = cupOrderRepository.save(order);

        if (restoredKinds == 0) {
            saved.refundNote = "历史旧订单无原料扣减快照，仅回退积分 " + rollback + "，未回补库存";
        } else {
            saved.refundNote = "退款成功：已按快照精确回补原料 " + restoredKinds
                    + " 项，并回退积分 " + rollback;
        }
        return saved;
    }

    // ============================ 需求汇总（预览 / 下单共用） ============================

    /** 畸形请求校验：空明细、非法支付方式、空配方/非法杯数 —— 这类不算“出杯失败”。 */
    private List<String> basicProblems(CupOrderCreateRequest req) {
        List<String> problems = new ArrayList<>();
        if (req == null || req.items == null || req.items.isEmpty()) {
            problems.add("出杯明细不能为空");
            return problems;
        }
        if (!Dict.isValidPayType(req.payType)) {
            problems.add("支付方式必须是：微信 / 支付宝 / 现金");
        }
        for (CupOrderItemInput it : req.items) {
            if (it.recipeId == null || it.qty == null || it.qty <= 0) {
                problems.add("出杯明细的配方或杯数不合法");
                break;
            }
        }
        return problems;
    }

    /**
     * 汇总本单需求：多条配方、重复明细先按配方合并杯数，
     * 再把每个配方的茶底（每杯 {@link Dict#BASE_TEA_PER_CUP}）与小料用量汇总到原料维度。
     *
     * @param locking true=下单路径，对原料行、会员行加悲观写锁并以最新库存判定；
     *                false=预览路径，普通读，仅作提示
     */
    private Plan buildPlan(CupOrderCreateRequest req, boolean locking) {
        Plan plan = new Plan();
        if (req == null) {
            plan.problems.add("出杯明细不能为空");
            return plan;
        }
        plan.payType = req.payType;
        plan.memberId = req.memberId;

        // 1) 重复明细合并：同一 recipeId 杯数累加，保持首次出现顺序
        if (req.items != null) {
            for (CupOrderItemInput it : req.items) {
                if (it == null || it.recipeId == null || it.qty == null || it.qty <= 0) {
                    continue; // basicProblems 已兜住非法明细
                }
                plan.recipeCups.merge(it.recipeId, it.qty, Integer::sum);
            }
        }

        // 2) 解析配方：失效配方记问题、不参与需求；有效配方累加金额与原料需求
        Map<Long, Recipe> recipeCache = new LinkedHashMap<>();
        for (Long recipeId : plan.recipeCups.keySet()) {
            Recipe recipe = recipeRepository.findById(recipeId).orElse(null);
            if (recipe == null) {
                plan.problems.add("配方引用失效（配方#" + recipeId + " 已不存在），整单无法出杯");
                continue;
            }
            recipeCache.put(recipeId, recipe);
            int cups = plan.recipeCups.get(recipeId);
            plan.total = plan.total.add(recipe.price.multiply(BigDecimal.valueOf(cups)));
            plan.need(recipe.baseTea, Dict.BASE_TEA_PER_CUP * cups);
            for (Map.Entry<String, Integer> t : recipe.toppings.entrySet()) {
                Integer perCup = t.getValue();
                if (perCup != null && perCup > 0) {
                    plan.need(t.getKey(), perCup * cups);
                }
            }
        }
        plan.requestItems = describeItems(plan.recipeCups, recipeCache);

        // 3) 解析会员（下单加锁）
        if (req.memberId != null) {
            Member member = locking
                    ? memberRepository.findByIdForUpdate(req.memberId)
                    : memberRepository.findById(req.memberId).orElse(null);
            if (member == null) {
                plan.problems.add("关联会员不存在：" + req.memberId);
                plan.memberLabel = "会员#" + req.memberId + "（已失效）";
            } else if ("已合并".equals(member.status)) {
                plan.problems.add("会员#" + req.memberId + " 已合并到主档会员#"
                        + member.mergedToId + "，请选择主档下单");
                plan.memberLabel = member.name + "（" + member.phone + "，已合并）";
            } else {
                plan.member = member;
                plan.memberLabel = member.name + "（" + member.phone + "）";
            }
        } else {
            plan.memberLabel = "散客";
        }

        // 4) 解析并（下单时）锁定全部涉及原料 —— 按 id 升序逐个加锁，
        //    与退款路径保持相同加锁顺序，杜绝并发交叉死锁
        Map<String, Ingredient> byName = new TreeMap<>();
        if (locking) {
            // 关键：先只取 id（不加载实体、不进一级缓存），再逐行 FOR UPDATE 首次加载，
            // 保证拿到锁点最新库存，杜绝并发丢失更新
            List<Long> ids = ingredientRepository.findIdsByNameIn(plan.needByName.keySet())
                    .stream().sorted().toList();
            for (Long ingId : ids) {
                Ingredient locked = ingredientRepository.findByIdForUpdate(ingId)
                        .orElseThrow(() -> new BizException("原料已不存在：" + ingId));
                byName.put(locked.name, locked);
            }
        } else {
            for (Ingredient ing : ingredientRepository.findByNameIn(plan.needByName.keySet())) {
                byName.put(ing.name, ing);
            }
        }
        for (MergedLine line : plan.needByName.values()) {
            line.ingredient = byName.get(line.name);
            if (line.ingredient == null) {
                plan.problems.add("原料「" + line.name + "」引用失效（已不存在），整单无法出杯");
            } else if (!"启用".equals(line.ingredient.status)) {
                plan.problems.add("原料「" + line.name + "」已停用，整单无法出杯");
            } else if (line.ingredient.stock < line.need) {
                plan.problems.add("原料「" + line.name + "」库存不足（当前 "
                        + line.ingredient.stock + line.ingredient.unit + "，需 "
                        + line.need + line.ingredient.unit + "）");
            }
        }
        return plan;
    }

    private String describeItems(Map<Long, Integer> recipeCups, Map<Long, Recipe> recipes) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : recipeCups.entrySet()) {
            Recipe r = recipes.get(e.getKey());
            parts.add((r == null ? "配方#" + e.getKey() : r.name) + "×" + e.getValue());
        }
        String text = String.join("，", parts);
        return text.length() > 250 ? text.substring(0, 250) : text;
    }

    // ---------------- 汇总用内部结构 ----------------

    private static class MergedLine {
        final String name;
        int need;
        Ingredient ingredient;

        MergedLine(String name, int need) {
            this.name = name;
            this.need = need;
        }
    }

    private static class Plan {
        String payType;
        Long memberId;
        String memberLabel;
        /** 配方 id -> 合并后杯数（保持首次出现顺序） */
        final Map<Long, Integer> recipeCups = new LinkedHashMap<>();
        /** 原料名 -> 合并需求（TreeMap 按名排序，输出稳定） */
        final Map<String, MergedLine> needByName = new TreeMap<>();
        BigDecimal total = BigDecimal.ZERO;
        Member member;
        String requestItems = "";
        final List<String> problems = new ArrayList<>();

        void need(String name, int qty) {
            needByName.computeIfAbsent(name, k -> new MergedLine(k, 0)).need += qty;
        }
    }
}
