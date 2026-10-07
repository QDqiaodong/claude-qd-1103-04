package com.milktea.bar.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 配方售价的统一金额规则：写入校验 + 规范化、读取降级判定。
 *
 * <p>所有入口（配方新增/编辑、出杯预览/下单）都走这里的同一套规则，
 * 保证列表、编辑表单、出杯选择器、订单预览对同一配方得到同一个结果：</p>
 * <ul>
 *   <li>写入：空 / 零 / 负数 / 超两位小数 / 超上限都给稳定的中文提示（400），
 *       合法值一律规范化为 scale=2 再落库，落库值与返回值口径一致；</li>
 *   <li>读取：兼容库里历史遗留的多位小数或 NULL 售价 —— 不静默四舍五入，
 *       而是标记「异常」降级状态并附原始值说明，出杯链路对异常配方稳定拒绝。</li>
 * </ul>
 */
public final class PriceSupport {

    private PriceSupport() {
    }

    /** 售价状态：正常 */
    public static final String STATUS_OK = "正常";

    /** 售价状态：异常（历史遗留多位小数 / 空值 / 越界），需编辑修正 */
    public static final String STATUS_ABNORMAL = "异常";

    /**
     * 写入校验 + 规范化：非法抛 {@link BizException}（稳定文案），合法返回 scale=2 的金额。
     * 形如 12.500 的值与 12.50 等值，属合法输入，规范化为 12.50（不改变数值，不是四舍五入）。
     */
    public static BigDecimal requireValidForWrite(BigDecimal raw) {
        if (raw == null) {
            throw new BizException("配方售价不能为空");
        }
        if (raw.compareTo(Dict.RECIPE_MIN_PRICE) < 0) {
            throw new BizException("配方售价必须大于 0（最低 "
                    + Dict.RECIPE_MIN_PRICE.toPlainString() + " 元，当前：" + raw.toPlainString() + "）");
        }
        if (raw.compareTo(Dict.RECIPE_MAX_PRICE) > 0) {
            throw new BizException("配方售价不能超过 "
                    + Dict.RECIPE_MAX_PRICE.toPlainString() + " 元（当前：" + raw.toPlainString() + "）");
        }
        try {
            return raw.setScale(Dict.RECIPE_PRICE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BizException("配方售价最多保留两位小数（当前：" + raw.toPlainString() + "）");
        }
    }

    /**
     * 读取规范化：合法值返回 scale=2 的金额；历史遗留的 NULL / 多位小数 / 越界值返回 null，
     * 调用方据此标记降级状态，绝不静默四舍五入。
     */
    public static BigDecimal normalizeOnRead(BigDecimal raw) {
        if (raw == null) {
            return null;
        }
        if (raw.compareTo(Dict.RECIPE_MIN_PRICE) < 0 || raw.compareTo(Dict.RECIPE_MAX_PRICE) > 0) {
            return null;
        }
        try {
            return raw.setScale(Dict.RECIPE_PRICE_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** 读取降级说明：描述原始值为何异常（raw 原样展示，不做任何舍入） */
    public static String issueOf(BigDecimal raw) {
        if (raw == null) {
            return "售价缺失（NULL），请编辑修正为两位小数金额";
        }
        if (raw.compareTo(Dict.RECIPE_MIN_PRICE) < 0) {
            return "售价不大于 0（原始值 " + raw.toPlainString() + "），请编辑修正";
        }
        if (raw.compareTo(Dict.RECIPE_MAX_PRICE) > 0) {
            return "售价超过上限 " + Dict.RECIPE_MAX_PRICE.toPlainString()
                    + "（原始值 " + raw.toPlainString() + "），请编辑修正";
        }
        return "售价小数位超过两位（原始值 " + raw.toPlainString() + "），请编辑修正";
    }
}
