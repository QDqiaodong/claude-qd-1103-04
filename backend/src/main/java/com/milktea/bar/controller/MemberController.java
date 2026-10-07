package com.milktea.bar.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.milktea.bar.common.ApiResponse;
import com.milktea.bar.dto.MemberContactChangeRequest;
import com.milktea.bar.dto.MemberPointsAdjustRequest;
import com.milktea.bar.dto.MemberProfileDetail;
import com.milktea.bar.entity.Member;
import com.milktea.bar.service.MemberService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 会员列表：默认仅有效会员；可按等级/状态（含已合并别名）/手机号姓名关键字筛选 */
    @GetMapping
    public ApiResponse<List<Member>> list(@RequestParam(required = false) String level,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        return ApiResponse.success(memberService.list(level, status, keyword));
    }

    /** 会员档案详情：积分流水、历史订单、联系方式变更审计、主档/别名关系 */
    @GetMapping("/{id}")
    public ApiResponse<MemberProfileDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(memberService.detail(id));
    }

    @PostMapping
    public ApiResponse<Member> create(@RequestBody Member input) {
        return ApiResponse.success(memberService.create(input));
    }

    /** 编辑积分/等级；手机号与姓名必须走联系方式变更接口（带原因与审计） */
    @PutMapping("/{id}")
    public ApiResponse<Member> update(@PathVariable Long id, @RequestBody Member input) {
        return ApiResponse.success(memberService.update(id, input));
    }

    /** 联系方式变更（手机号/姓名）：记录前后值、原因、生效时间与操作人 */
    @PostMapping("/{id}/contact")
    public ApiResponse<Member> changeContact(@PathVariable Long id,
                                             @Valid @RequestBody MemberContactChangeRequest req) {
        return ApiResponse.success("联系方式已变更并记录审计", memberService.changeContact(id, req));
    }

    /** 手工调整积分：必填原因，记积分流水 */
    @PostMapping("/{id}/points")
    public ApiResponse<Member> adjustPoints(@PathVariable Long id,
                                            @Valid @RequestBody MemberPointsAdjustRequest req) {
        return ApiResponse.success("积分已调整", memberService.adjustPoints(id, req));
    }
}
