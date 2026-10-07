package com.milktea.bar.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 出杯前原料需求预览：按本单全部配方（茶底 + 小料）合并汇总后的预计扣减。
 * 页面据此展示预计扣减与不足原料；仅为提示，真正扣减时服务端以最新加锁库存为准。
 */
public class CupOrderPreview {

    /** 全部原料校验是否通过（不足 / 停用 / 引用失效 / 会员失效 时为 false） */
    public boolean feasible;

    /** 本单金额合计 */
    public BigDecimal total;

    /** 若关联会员，预计增加的积分 */
    public Integer pointsToEarn;

    /** 合并后的原料需求（每个原料一行） */
    public List<DemandLine> demands;

    /** 无法下单的全部原因 */
    public List<String> problems;

    public static class DemandLine {

        public String ingredientName;

        public String category;

        public String unit;

        /** 本单合并后的总需求 */
        public Integer needQty;

        /** 服务端当前库存 */
        public Integer stock;

        /** 启用 / 停用 */
        public String status;

        public boolean disabled;

        public boolean insufficient;

        public DemandLine() {
        }

        public DemandLine(String ingredientName, String category, String unit,
                          Integer needQty, Integer stock, String status,
                          boolean disabled, boolean insufficient) {
            this.ingredientName = ingredientName;
            this.category = category;
            this.unit = unit;
            this.needQty = needQty;
            this.stock = stock;
            this.status = status;
            this.disabled = disabled;
            this.insufficient = insufficient;
        }
    }
}
