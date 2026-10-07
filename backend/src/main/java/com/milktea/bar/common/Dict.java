package com.milktea.bar.common;

import java.math.BigDecimal;
import java.util.List;

/**
 * 奶茶饮品店 · 原料库存与配方出单 业务词典与规则参数表。
 *
 * <p>集中存放状态集合、流程、阈值等规则常量，Service 层引用此处做校验与判定，
 * 便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 原料 ingredient ----------------
    /** 原料类别：茶底 / 奶 / 小料 / 糖浆 */
    public static final List<String> INGREDIENT_CATEGORIES = List.of("茶底", "奶", "小料", "糖浆");

    /** 原料状态：启用 / 停用（停用原料不能进配方） */
    public static final List<String> INGREDIENT_STATUS = List.of("启用", "停用");

    // ---------------- 会员 member ----------------
    public static final List<String> MEMBER_LEVELS = List.of("普通", "银卡", "金卡");

    /** 会员档案状态：有效 / 已合并（已合并档只读保留别名） */
    public static final String MEMBER_ACTIVE = "有效";

    public static final String MEMBER_MERGED = "已合并";

    public static final List<String> MEMBER_STATUS = List.of(MEMBER_ACTIVE, MEMBER_MERGED);

    /** 操作人员角色：店员可发起合并，只有负责人能撤销合并 */
    public static final String ROLE_STAFF = "店员";

    public static final String ROLE_OWNER = "负责人";

    // ---------------- 积分流水 member_points_ledger ----------------
    public static final String LEDGER_OPENING = "期初";

    public static final String LEDGER_EARN = "消费获得";

    public static final String LEDGER_REFUND = "退款回退";

    public static final String LEDGER_MANUAL = "手工调整";

    public static final List<String> LEDGER_TYPES =
            List.of(LEDGER_OPENING, LEDGER_EARN, LEDGER_REFUND, LEDGER_MANUAL);

    // ---------------- 会员合并 member_merge_request ----------------
    /** 合并单状态：待处理 / 冲突待处理 / 已合并 / 已撤销 */
    public static final String MERGE_PENDING = "待处理";

    public static final String MERGE_CONFLICT = "冲突待处理";

    public static final String MERGE_MERGED = "已合并";

    public static final String MERGE_REVOKED = "已撤销";

    public static final List<String> MERGE_STATUS =
            List.of(MERGE_PENDING, MERGE_CONFLICT, MERGE_MERGED, MERGE_REVOKED);

    /** 合并角色：保留的主档 / 并入的副档 */
    public static final String MERGE_ROLE_MASTER = "主档";

    public static final String MERGE_ROLE_ALIAS = "副档";

    /** 合并迁移的订单状态：已迁移 / 撤销后已恢复 */
    public static final String MERGE_ORDER_MIGRATED = "已迁移";

    public static final String MERGE_ORDER_RESTORED = "已恢复";

    /** 合并单审计事件 */
    public static final List<String> MERGE_EVENTS =
            List.of("创建", "冲突待处理", "合并成功", "撤销合并", "重新检查");

    /** 冲突类型（可并列，用「、」连接） */
    public static final String CONFLICT_PHONE = "手机号冲突";

    public static final String CONFLICT_POINTS = "积分来源冲突";

    // ---------------- 联系方式变更审计 member_contact_audit ----------------
    public static final String FIELD_PHONE = "手机号";

    public static final String FIELD_NAME = "姓名";

    /** 会员累计积分升级阈值（达到即升级） */
    public static final int SILVER_POINTS = 500;

    public static final int GOLD_POINTS = 2000;

    /** 消费 1 元累计的积分（出杯时按金额累加、退款时反向回退） */
    public static final int POINTS_PER_YUAN = 1;

    // ---------------- 配方 recipe ----------------
    /** 配方售价下限：必须大于该值（0.01 元） */
    public static final BigDecimal RECIPE_MIN_PRICE = new BigDecimal("0.01");

    /** 每出一杯固定消耗的茶底量（茶底用量未在配方中配置，按此统一口径扣减） */
    public static final int BASE_TEA_PER_CUP = 10;

    // ---------------- 出杯记录 cup_order ----------------
    /** 支付方式 */
    public static final List<String> PAY_TYPES = List.of("微信", "支付宝", "现金");

    /** 订单状态：已出杯 / 已退款 */
    public static final List<String> CUP_ORDER_STATUS = List.of("已出杯", "已退款");

    // ---------------- 取值方法 ----------------

    public static boolean isValidIngredientCategory(String category) {
        return category != null && INGREDIENT_CATEGORIES.contains(category);
    }

    public static boolean isValidIngredientStatus(String status) {
        return status != null && INGREDIENT_STATUS.contains(status);
    }

    public static boolean isValidMemberLevel(String level) {
        return level != null && MEMBER_LEVELS.contains(level);
    }

    public static boolean isValidMemberStatus(String status) {
        return status != null && MEMBER_STATUS.contains(status);
    }

    public static boolean isValidMergeStatus(String status) {
        return status != null && MERGE_STATUS.contains(status);
    }

    /** 按累计积分自动判定会员等级 */
    public static String decideMemberLevel(int points) {
        if (points >= GOLD_POINTS) {
            return "金卡";
        }
        if (points >= SILVER_POINTS) {
            return "银卡";
        }
        return "普通";
    }

    public static boolean isValidPayType(String payType) {
        return payType != null && PAY_TYPES.contains(payType);
    }

    public static boolean isValidCupOrderStatus(String status) {
        return status != null && CUP_ORDER_STATUS.contains(status);
    }

    /** 校验小料份数必须为正整数 */
    public static boolean isValidToppingQty(Integer qty) {
        return qty != null && qty > 0;
    }
}
