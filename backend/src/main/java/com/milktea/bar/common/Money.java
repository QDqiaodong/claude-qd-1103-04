package com.milktea.bar.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额（配方售价 / 订单金额）统一口径：
 * <ul>
 *   <li>全系统金额一律按「分」精度落库与返回：{@code DECIMAL(10,2)}，两位小数；</li>
 *   <li>校验在服务端最新数据上进行：null / 0 / 负数 / 超过两位小数 / 超过上限
 *       都得到稳定的中文提示，绝不静默四舍五入；</li>
 *   <li>数据库中历史遗留的多位小数 / 空价格不参与运算，读取时由调用方显式降级。</li>
 * </ul>
 */
public final class Money {

    /** 金额统一小数位数：分 */
    public static final int PRICE_SCALE = 2;

    private Money() {
    }

    /**
     * 校验并规范化新写入的配方售价。
     *
     * @return 统一为两位小数的规范金额（只补零，不做任何进位取舍）
     * @throws BizException 金额为空、为零、为负、超过两位小数或超出范围时，携带稳定提示
     */
    public static BigDecimal validateRecipePrice(BigDecimal price) {
        if (price == null) {
            throw new BizException("配方售价不能为空");
        }
        int sign = price.signum();
        if (sign == 0) {
            throw new BizException("配方售价必须大于 0（当前为 0）");
        }
        if (sign < 0) {
            throw new BizException("配方售价必须大于 0，不能为负数（当前 "
                    + price.stripTrailingZeros().toPlainString() + "）");
        }
        if (price.stripTrailingZeros().scale() > PRICE_SCALE) {
            throw new BizException("配方售价最多保留两位小数，请勿提交 "
                    + price.stripTrailingZeros().toPlainString() + "（系统不会自动四舍五入）");
        }
        if (price.compareTo(Dict.RECIPE_MAX_PRICE) > 0) {
            throw new BizException("配方售价不能超过 " + format(Dict.RECIPE_MAX_PRICE) + " 元");
        }
        // 到这里去尾零后 scale 一定 ≤ 2，setScale 只补零、不进位（UNNECESSARY 遇到会进位的输入会直接报错，而非静默四舍五入）
        return price.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY);
    }

    /**
     * 读取历史遗留的原始售价，判断其精度/取值是否符合现行口径。
     * 不抛异常、不四舍五入：{@code null} 或不符合口径时返回 false，由调用方显式降级。
     */
    public static boolean isCanonical(BigDecimal rawPrice) {
        if (rawPrice == null || rawPrice.signum() <= 0) {
            return false;
        }
        if (rawPrice.stripTrailingZeros().scale() > PRICE_SCALE) {
            return false;
        }
        return rawPrice.compareTo(Dict.RECIPE_MAX_PRICE) <= 0;
    }

    /** 金额格式化为两位小数字符串，如 12 → "12.00"；null 显示为 "—"。 */
    public static String format(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        return value.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    /**
     * 订单合计等内部金额统一收口到两位小数后再落库。
     * 参与计算的配方售价本身已校验为两位小数、杯数为整数，合计天然为两位小数，
     * 这里用 UNNECESSARY 只做精度收口，绝不替业务做进位取舍。
     */
    public static BigDecimal canonical(BigDecimal value) {
        if (value == null) {
            throw new BizException("金额不能为空");
        }
        return value.setScale(PRICE_SCALE, RoundingMode.UNNECESSARY);
    }
}
