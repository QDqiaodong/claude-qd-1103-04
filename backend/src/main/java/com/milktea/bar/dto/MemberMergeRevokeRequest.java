package com.milktea.bar.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销合并请求：只有负责人可发起，必填撤销原因。
 */
public class MemberMergeRevokeRequest {

    @NotBlank(message = "撤销操作人不能为空")
    public String operator;

    /** 负责人 / 店员 —— 仅负责人通过 */
    @NotBlank(message = "操作人角色不能为空")
    public String operatorRole;

    @NotBlank(message = "撤销原因不能为空")
    public String reason;
}
