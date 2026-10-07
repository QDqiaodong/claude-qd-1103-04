package com.milktea.bar.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 执行/重新检查合并单的请求（冲突解决后用同一入口重新检查，天然幂等）。
 */
public class MemberMergeExecuteRequest {

    @NotBlank(message = "操作人不能为空")
    public String operator;
}
