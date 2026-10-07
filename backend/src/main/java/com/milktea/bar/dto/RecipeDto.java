package com.milktea.bar.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;

import com.milktea.bar.common.Dict;
import com.milktea.bar.common.Money;
import com.milktea.bar.entity.Recipe;

/**
 * 配方对外视图。金额相关字段一律走这里，不直接暴露实体：
 * <ul>
 *   <li>{@link #price}：规范售价（两位小数，如 12.00），列表 / 编辑表单 /
 *       出杯选择器 / 订单预览对同一配方取到的永远是同一个值；</li>
 *   <li>{@link #priceStatus}：{@code ok} / {@code legacy-empty} /
 *       {@code legacy-multi-decimal} / {@code legacy-invalid}；</li>
 *   <li>{@link #priceRaw} + {@link #priceNote}：历史遗留空价格 / 多位小数 / 非正价格的
 *       原始值与明确降级提示，绝不静默四舍五入。降级配方不可出杯，需在编辑表单修复。</li>
 * </ul>
 */
public class RecipeDto {

    /** 价格状态：规范可出杯 */
    public static final String PRICE_OK = "ok";
    /** 价格状态：历史遗留空价格 */
    public static final String PRICE_LEGACY_EMPTY = "legacy-empty";
    /** 价格状态：历史遗留多位小数 */
    public static final String PRICE_LEGACY_MULTI_DECIMAL = "legacy-multi-decimal";
    /** 价格状态：历史遗留 0 / 负数 / 超上限等不合法价格 */
    public static final String PRICE_LEGACY_INVALID = "legacy-invalid";

    public Long id;
    public String name;
    public String baseTea;

    /** 规范售价（两位小数）；降级态为 null，前端按 priceStatus/priceNote 展示 */
    public BigDecimal price;

    /** 价格状态，见本类常量 */
    public String priceStatus;

    /** 降级态的历史原始价格（可空），仅用于回显，不参与金额计算 */
    public BigDecimal priceRaw;

    /** 降级原因 / 修复指引，ok 状态为 null */
    public String priceNote;

    public Map<String, Integer> toppings;
    public Integer version;
    public LocalDateTime createdAt;

    /**
     * 实体 → 对外视图：历史遗留的空价格 / 多位小数 / 非正价格原样回显并给出明确降级状态，
     * 不做任何四舍五入；降级配方的 price 为 null，由出杯侧拒绝参与金额计算。
     */
    public static RecipeDto of(Recipe r) {
        RecipeDto dto = new RecipeDto();
        dto.id = r.id;
        dto.name = r.name;
        dto.baseTea = r.baseTea;
        dto.toppings = r.toppings;
        dto.version = r.version;
        dto.createdAt = r.createdAt;

        if (r.price != null && Money.isCanonical(r.price)) {
            dto.price = r.price.setScale(Money.PRICE_SCALE, RoundingMode.UNNECESSARY);
            dto.priceStatus = PRICE_OK;
            return dto;
        }

        BigDecimal raw = r.priceRaw != null ? r.priceRaw : r.price;
        dto.priceRaw = raw;
        String shown = raw == null ? null : raw.stripTrailingZeros().toPlainString();
        if (raw == null) {
            dto.priceStatus = PRICE_LEGACY_EMPTY;
            dto.priceNote = "历史遗留空价格，无法计价与出杯，请在编辑表单补填两位小数的售价";
        } else if (raw.signum() == 0) {
            dto.priceStatus = PRICE_LEGACY_INVALID;
            dto.priceNote = "历史遗留价格为 0，请在编辑表单修正为大于 0 的两位小数售价";
        } else if (raw.signum() < 0) {
            dto.priceStatus = PRICE_LEGACY_INVALID;
            dto.priceNote = "历史遗留价格为负数（" + shown + " 元），请在编辑表单修正为大于 0 的两位小数售价";
        } else if (raw.stripTrailingZeros().scale() > Money.PRICE_SCALE) {
            dto.priceStatus = PRICE_LEGACY_MULTI_DECIMAL;
            dto.priceNote = "历史遗留价格 " + shown + " 元超过两位小数，系统未做四舍五入，请在编辑表单确认规范售价";
        } else if (raw.compareTo(Dict.RECIPE_MAX_PRICE) > 0) {
            dto.priceStatus = PRICE_LEGACY_INVALID;
            dto.priceNote = "历史遗留价格 " + shown + " 元超出上限，请在编辑表单修正";
        } else {
            // 规范列为空但原始值本身可规范化（迁移中间态）：同样显式降级，等待人工确认
            dto.priceStatus = PRICE_LEGACY_MULTI_DECIMAL;
            dto.priceNote = "历史遗留价格 " + shown + " 元未规范化，请在编辑表单确认售价";
        }
        return dto;
    }
}
