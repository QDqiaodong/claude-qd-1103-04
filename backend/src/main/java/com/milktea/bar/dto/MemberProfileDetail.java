package com.milktea.bar.dto;

import java.util.List;

import com.milktea.bar.entity.CupOrder;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberContactAudit;
import com.milktea.bar.entity.MemberPointsLedger;

/**
 * 会员档案详情：当前档案（已合并别名带主档信息）、积分流水、历史订单、
 * 联系方式变更审计、以及（若是主档）名下的只读别名列表。
 */
public class MemberProfileDetail {

    public Member member;

    public Long masterMemberId;

    public Member master;

    public List<MemberPointsLedger> ledgers;

    public List<CupOrder> orders;

    public List<MemberContactAudit> contactAudits;

    /** 主档名下的只读别名（副档快照信息） */
    public List<Member> aliases;
}
