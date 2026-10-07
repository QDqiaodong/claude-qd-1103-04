package com.milktea.bar.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 会员联系方式（手机号/姓名）变更：必填变更原因与操作人，服务端保留前后值审计。
 * 字段为 null 表示该项本次不修改。
 */
public class MemberContactChangeRequest {

    public String phone;

    public String name;

    @NotBlank(message = "变更原因不能为空")
    public String reason;

    @NotBlank(message = "操作人不能为空")
    public String operator;
}
