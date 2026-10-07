package com.milktea.bar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 手工积分调整（区别于消费/退款的系统积分），记流水与事由。
 */
public class MemberPointsAdjustRequest {

    @NotNull(message = "积分变动值不能为空")
    public Integer delta;

    @NotBlank(message = "调整原因不能为空")
    public String reason;

    @NotBlank(message = "操作人不能为空")
    public String operator;
}
