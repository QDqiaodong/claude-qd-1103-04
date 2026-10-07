package com.milktea.bar.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 店员把重复会员标记为待合并的请求：选择主档 + 至少一个副档，必填合并原因。
 */
public class MemberMergeCreateRequest {

    @NotNull(message = "必须选择保留的主档会员")
    public Long masterMemberId;

    /** 待并入主档的副档会员 id（至少一个） */
    @NotNull(message = "至少选择一个待合并的重复会员")
    public List<Long> aliasMemberIds;

    @NotBlank(message = "合并原因不能为空")
    public String reason;

    /** 操作人（店员），由页面顶部身份带入 */
    @NotBlank(message = "操作人不能为空")
    public String operator;
}
