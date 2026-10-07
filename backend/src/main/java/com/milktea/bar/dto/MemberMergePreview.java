package com.milktea.bar.dto;

import java.util.List;

/**
 * 合并预览：两边会员的手机号、姓名、积分、等级、历史订单数并排展示，
 * 并预演执行前检查（手机号冲突 / 积分来源冲突）。只读，不落数据。
 */
public class MemberMergePreview {

    public Long masterMemberId;

    public Side master;

    public List<Side> aliases;

    /** 合并后主档预计积分（各档流水合计；存在冲突时仍给出，供店员核对） */
    public Integer projectedPoints;

    public boolean conflict;

    public List<String> conflictTypes;

    public String conflictDetail;

    /** 单边会员快照 */
    public static class Side {
        public Long memberId;
        public String phone;
        public String name;
        public Integer points;
        public String level;
        public String status;
        public int orderCount;
        /** 积分流水合计（与 points 不一致即积分来源冲突） */
        public int ledgerSum;
    }
}
