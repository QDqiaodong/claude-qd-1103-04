package com.milktea.bar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.milktea.bar.common.ApiResponse;
import com.milktea.bar.dto.MemberMergeCreateRequest;
import com.milktea.bar.dto.MemberMergeDetail;
import com.milktea.bar.dto.MemberMergeExecuteRequest;
import com.milktea.bar.dto.MemberMergePreview;
import com.milktea.bar.dto.MemberMergeRevokeRequest;
import com.milktea.bar.entity.MemberMergeRequest;
import com.milktea.bar.service.MemberMergeService;

import jakarta.validation.Valid;

/**
 * 会员档案合并：待处理/冲突待处理/已合并/已撤销筛选与详情，
 * 合并预览、创建（标记待合并）、执行/冲突后重新检查、负责人撤销。
 */
@RestController
@RequestMapping("/api/member-merges")
public class MemberMergeController {

    private final MemberMergeService mergeService;

    public MemberMergeController(MemberMergeService mergeService) {
        this.mergeService = mergeService;
    }

    /** 合并单列表：status=待处理 / 冲突待处理 / 已合并 / 已撤销 */
    @GetMapping
    public ApiResponse<List<MemberMergeRequest>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(mergeService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<MemberMergeDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(mergeService.detail(id));
    }

    /** 合并预览：两边手机号/姓名/积分/历史订单并排，预演手机号与积分来源检查（只读） */
    @PostMapping("/preview")
    public ApiResponse<MemberMergePreview> preview(@RequestParam Long masterId,
                                                   @RequestBody List<Long> aliasIds) {
        return ApiResponse.success(mergeService.preview(masterId, aliasIds));
    }

    /** 店员标记重复会员为待合并（选择主档、填原因）；有冲突直接落「冲突待处理」 */
    @PostMapping
    public ApiResponse<MemberMergeRequest> create(@Valid @RequestBody MemberMergeCreateRequest req) {
        MemberMergeRequest saved = mergeService.create(req);
        String message = "冲突待处理".equals(saved.status)
                ? "已标记为重复会员，但检查发现冲突，已进入「冲突待处理」"
                : "已标记为待合并";
        return ApiResponse.success(message, saved);
    }

    /** 执行合并；冲突待处理单解决问题后同一入口重新检查（幂等，重复执行返回已合并结果） */
    @PostMapping("/{id}/execute")
    public ApiResponse<MemberMergeDetail> execute(@PathVariable Long id,
                                                  @Valid @RequestBody MemberMergeExecuteRequest req) {
        return ApiResponse.success("合并成功：历史订单与积分流水已归入主档",
                mergeService.execute(id, req.operator));
    }

    /** 撤销合并：仅负责人可发起，订单与积分按来源恢复、不重复计算 */
    @PostMapping("/{id}/revoke")
    public ApiResponse<MemberMergeDetail> revoke(@PathVariable Long id,
                                                 @Valid @RequestBody MemberMergeRevokeRequest req) {
        return ApiResponse.success("已撤销合并，订单与积分归属已恢复（未重复计算积分）",
                mergeService.revoke(id, req));
    }
}
