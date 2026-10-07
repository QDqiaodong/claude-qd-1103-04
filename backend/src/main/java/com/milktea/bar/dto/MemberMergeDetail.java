package com.milktea.bar.dto;

import java.util.List;

import com.milktea.bar.entity.MemberContactAudit;
import com.milktea.bar.entity.MemberMergeEvent;
import com.milktea.bar.entity.MemberMergeItem;
import com.milktea.bar.entity.MemberMergeOrder;
import com.milktea.bar.entity.MemberMergeRequest;

/**
 * 合并单详情：申请本体 + 成员前后快照 + 订单迁移 + 生命周期审计事件，
 * 页面据此展示合并前后差异与完整审计记录。
 */
public class MemberMergeDetail {

    public MemberMergeRequest request;

    public List<MemberMergeItem> items;

    public List<MemberMergeOrder> orders;

    public List<MemberMergeEvent> events;

    /** 撤销合并后，主档与各副档当前状态/积分（便于页面展示恢复结果） */
    public List<CurrentMember> currentMembers;

    /** 与本次合并相关的联系方式审计（一般为空，预留审计联动） */
    public List<MemberContactAudit> contactAudits;

    public static class CurrentMember {
        public Long memberId;
        public String role;
        public String phone;
        public String name;
        public Integer points;
        public String level;
        public String status;
        public Long mergedToId;
    }
}
