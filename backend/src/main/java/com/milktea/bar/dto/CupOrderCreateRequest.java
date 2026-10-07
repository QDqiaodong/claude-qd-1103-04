package com.milktea.bar.dto;

import java.util.List;

/**
 * 出杯下单请求：可空会员 + 支付方式 + 配方明细（配方 id 与杯数）。
 */
public class CupOrderCreateRequest {

    /** 会员 id，散客下单为 null */
    public Long memberId;

    /** 支付方式：微信 / 支付宝 / 现金 */
    public String payType;

    /** 出杯明细 */
    public List<CupOrderItemInput> items;

    public static class CupOrderItemInput {

        public Long recipeId;

        public Integer qty;
    }
}
