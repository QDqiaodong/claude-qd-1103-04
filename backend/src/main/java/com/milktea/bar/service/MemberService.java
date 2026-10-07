package com.milktea.bar.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.dto.MemberContactChangeRequest;
import com.milktea.bar.dto.MemberPointsAdjustRequest;
import com.milktea.bar.dto.MemberProfileDetail;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberContactAudit;
import com.milktea.bar.entity.MemberPhoneBinding;
import com.milktea.bar.repository.CupOrderRepository;
import com.milktea.bar.repository.MemberContactAuditRepository;
import com.milktea.bar.repository.MemberPhoneBindingRepository;
import com.milktea.bar.repository.MemberPointsLedgerRepository;
import com.milktea.bar.repository.MemberRepository;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final MemberPhoneBindingRepository bindingRepository;
    private final MemberContactAuditRepository contactAuditRepository;
    private final MemberPointsLedgerRepository ledgerRepository;
    private final CupOrderRepository cupOrderRepository;
    private final PointsLedgerService ledgerService;

    public MemberService(MemberRepository memberRepository,
                         MemberPhoneBindingRepository bindingRepository,
                         MemberContactAuditRepository contactAuditRepository,
                         MemberPointsLedgerRepository ledgerRepository,
                         CupOrderRepository cupOrderRepository,
                         PointsLedgerService ledgerService) {
        this.memberRepository = memberRepository;
        this.bindingRepository = bindingRepository;
        this.contactAuditRepository = contactAuditRepository;
        this.ledgerRepository = ledgerRepository;
        this.cupOrderRepository = cupOrderRepository;
        this.ledgerService = ledgerService;
    }

    // ============================ 查询 ============================

    /**
     * 会员列表：默认只列有效会员（含被合并手机号解析到的主档标记）；
     * 传 status 可显式查「已合并」别名档。
     */
    public List<Member> list(String level, String status, String keyword) {
        List<Member> all;
        if (keyword != null && !keyword.isBlank()) {
            all = memberRepository.search(keyword.trim());
        } else if (status != null) {
            all = memberRepository.findByStatusOrderByIdAsc(status);
        } else {
            all = memberRepository.findByStatusOrderByIdAsc(Dict.MEMBER_ACTIVE);
        }
        List<Member> result = new ArrayList<>();
        for (Member m : all) {
            if (level != null && !level.equals(m.level)) {
                continue;
            }
            decorate(m);
            result.add(m);
        }
        return result;
    }

    /** 会员详情：有效档/只读别名都可查；别名与历史订单解析到主档 */
    public MemberProfileDetail detail(Long id) {
        Member member = memberRepository.findById(id)
                .orElseThrow(() -> new BizException("会员不存在：" + id));
        decorate(member);

        MemberProfileDetail dto = new MemberProfileDetail();
        dto.member = member;
        dto.masterMemberId = Dict.MEMBER_MERGED.equals(member.status) ? member.mergedToId : member.id;
        dto.master = Dict.MEMBER_MERGED.equals(member.status)
                ? memberRepository.findById(member.mergedToId).orElse(null)
                : null;
        // 历史订单统一看主档（合并时订单已改挂主档，撤销后挂回）
        dto.orders = cupOrderRepository.findByMemberIdOrderByIdAsc(dto.masterMemberId);
        dto.ledgers = ledgerRepository.findByMemberIdOrderByIdAsc(dto.masterMemberId);
        dto.contactAudits = contactAuditRepository.findByMemberIdOrderByIdDesc(id);
        if (Dict.MEMBER_ACTIVE.equals(member.status)) {
            dto.aliases = memberRepository.findByMergedToId(id).stream().map(a -> {
                decorate(a);
                return a;
            }).toList();
        } else {
            dto.aliases = List.of();
        }
        return dto;
    }

    /** 填充查询辅助字段：别名标记与解析后的主档 id */
    private void decorate(Member m) {
        if (Dict.MEMBER_MERGED.equals(m.status)) {
            m.alias = true;
            m.masterId = m.mergedToId;
        } else {
            m.alias = false;
            m.masterId = m.id;
        }
    }

    // ============================ 新增 ============================

    /**
     * 新增会员（单事务）：对目标手机号绑定行加写锁后再判定唯一性，
     * 并发建档同号时后到事务稳定拒绝，不靠先查后插。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member create(Member input) {
        if (input.phone == null || input.phone.isBlank()) {
            throw new BizException("手机号不能为空");
        }
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("昵称不能为空");
        }
        if (input.points != null && input.points < 0) {
            throw new BizException("积分不能为负");
        }

        // 以最新绑定复核手机号唯一性（FOR UPDATE 串行化并发）
        MemberPhoneBinding binding = bindingRepository.findByPhoneForUpdate(input.phone);
        if (binding != null) {
            Member owner = memberRepository.findById(binding.memberId).orElse(null);
            String label = owner == null ? String.valueOf(binding.memberId)
                    : owner.name + "（" + owner.phone + "）";
            throw new BizException("手机号已绑定有效会员：" + label + "，同一手机号不能绑定两个有效会员");
        }

        Member saved = new Member();
        saved.phone = input.phone;
        saved.name = input.name;
        saved.points = input.points == null ? 0 : input.points;
        saved.level = (input.level == null) ? Dict.decideMemberLevel(saved.points) : input.level;
        if (!Dict.isValidMemberLevel(saved.level)) {
            throw new BizException("会员等级必须是：普通 / 银卡 / 金卡");
        }
        saved.status = Dict.MEMBER_ACTIVE;
        saved.createdAt = LocalDateTime.now();
        memberRepository.save(saved);

        // 建档带期初积分时补一条「期初」流水，保证余额恒等于流水合计（合并前积分来源检查依赖此不变量）
        if (saved.points > 0) {
            ledgerService.append(saved, saved.points, Dict.LEDGER_OPENING, null, null,
                    "建档期初积分", "系统");
            memberRepository.save(saved);
        }

        MemberPhoneBinding b = new MemberPhoneBinding();
        b.phone = saved.phone;
        b.memberId = saved.id;
        b.boundAt = LocalDateTime.now();
        bindingRepository.save(b);
        return saved;
    }

    // ============================ 编辑（积分/等级，不再裸改手机号姓名） ============================

    /**
     * 编辑会员的积分/等级（管理用途）。手机号与姓名不在此静默修改：
     * 必须走联系方式变更接口并填写原因，保留前后值审计。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member update(Long id, Member input) {
        Member existing = memberRepository.findByIdForUpdate(id);
        if (existing == null) {
            throw new BizException("会员不存在：" + id);
        }
        if (Dict.MEMBER_MERGED.equals(existing.status)) {
            throw new BizException("该会员档案已合并（只读别名，主档会员#" + existing.mergedToId
                    + "），请在主档上操作");
        }
        if (input.phone != null && !input.phone.equals(existing.phone)) {
            throw new BizException("手机号变更必须使用「变更联系方式」并填写原因，不允许在此静默修改");
        }
        if (input.name != null && !input.name.equals(existing.name)) {
            throw new BizException("姓名变更必须使用「变更联系方式」并填写原因，不允许在此静默修改");
        }
        if (input.points != null) {
            if (input.points < 0) {
                throw new BizException("积分不能为负");
            }
            int delta = input.points - existing.points;
            if (delta != 0) {
                ledgerService.append(existing, delta, Dict.LEDGER_MANUAL, null, null,
                        "管理端直接设置积分", "管理员");
            }
            existing.level = Dict.decideMemberLevel(existing.points);
        }
        if (input.level != null) {
            if (!Dict.isValidMemberLevel(input.level)) {
                throw new BizException("会员等级必须是：普通 / 银卡 / 金卡");
            }
            existing.level = input.level;
        }
        return memberRepository.save(existing);
    }

    // ============================ 联系方式变更（带审计） ============================

    /**
     * 修改手机号/姓名（单事务）：
     * <ol>
     *   <li>会员行加写锁、目标手机号绑定行加写锁，以最新状态校验；</li>
     *   <li>同一手机号不能绑定两个有效会员 —— 目标号已属其他有效会员则稳定拒绝；</li>
     *   <li>改手机号：写审计（前值/后值/原因/生效时间/操作人），绑定行改指本会员；</li>
     *   <li>改姓名：同样写审计。已合并只读别名不允许修改。</li>
     * </ol>
     * 并发改同号在绑定行锁上串行化，不可能两个会员都成功。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member changeContact(Long id, MemberContactChangeRequest req) {
        if (req == null) {
            throw new BizException("请求不能为空");
        }
        if ((req.phone == null || req.phone.isBlank()) && (req.name == null || req.name.isBlank())) {
            throw new BizException("手机号与姓名至少修改一项");
        }
        Member existing = memberRepository.findByIdForUpdate(id);
        if (existing == null) {
            throw new BizException("会员不存在：" + id);
        }
        if (Dict.MEMBER_MERGED.equals(existing.status)) {
            throw new BizException("该档案为已合并只读别名（主档会员#" + existing.mergedToId
                    + "），不能修改联系方式");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean phoneChanged = req.phone != null && !req.phone.isBlank()
                && !req.phone.equals(existing.phone);
        boolean nameChanged = req.name != null && !req.name.isBlank()
                && !req.name.equals(existing.name);

        if (phoneChanged) {
            // 旧号/新号绑定行统一按手机号升序加锁，避免两笔改号交叉等锁形成死锁；
            // 目标号已属其他有效会员则稳定拒绝（以锁点最新归属为准）
            List<String> phones = new ArrayList<>(List.of(existing.phone, req.phone));
            java.util.Collections.sort(phones);
            Map<String, MemberPhoneBinding> locked = new LinkedHashMap<>();
            for (String p : phones) {
                locked.put(p, bindingRepository.findByPhoneForUpdate(p));
            }
            MemberPhoneBinding target = locked.get(req.phone);
            if (target != null && !target.memberId.equals(id)) {
                Member owner = memberRepository.findById(target.memberId).orElse(null);
                String label = owner == null ? String.valueOf(target.memberId)
                        : owner.name + "（" + owner.phone + "）";
                throw new BizException("手机号 " + req.phone + " 已绑定有效会员：" + label
                        + "，同一手机号不能绑定两个有效会员");
            }
            // 原号绑定删除，再登记新号绑定：本会员名下只保留一个有效号
            MemberPhoneBinding old = locked.get(existing.phone);
            if (old != null && old.memberId.equals(id)) {
                bindingRepository.delete(old);
            }
            existing.phone = req.phone;
            if (target == null) {
                MemberPhoneBinding b = new MemberPhoneBinding();
                b.phone = req.phone;
                b.memberId = id;
                b.boundAt = now;
                bindingRepository.save(b);
            }
            saveContactAudit(id, Dict.FIELD_PHONE, old == null ? null : old.phone, req.phone,
                    req.reason, req.operator, now);
        }

        if (nameChanged) {
            String oldName = existing.name;
            existing.name = req.name.trim();
            saveContactAudit(id, Dict.FIELD_NAME, oldName, existing.name,
                    req.reason, req.operator, now);
        }

        return memberRepository.save(existing);
    }

    private void saveContactAudit(Long memberId, String field, String oldValue, String newValue,
                                  String reason, String operator, LocalDateTime effectiveAt) {
        MemberContactAudit audit = new MemberContactAudit();
        audit.memberId = memberId;
        audit.field = field;
        audit.oldValue = oldValue;
        audit.newValue = newValue;
        audit.reason = reason;
        audit.effectiveAt = effectiveAt;
        audit.operator = operator;
        audit.createdAt = LocalDateTime.now();
        contactAuditRepository.save(audit);
    }

    // ============================ 手工调整积分（带流水） ============================

    /**
     * 手工调整积分（充值补录/差错修正）：会员行写锁，记「手工调整」流水，
     * 余额恒等于流水合计，并按累计积分重算等级。
     */
    @Transactional(rollbackFor = Exception.class)
    public Member adjustPoints(Long id, MemberPointsAdjustRequest req) {
        if (req == null || req.delta == null) {
            throw new BizException("积分变动值不能为空");
        }
        if (req.delta == 0) {
            throw new BizException("积分变动值不能为 0");
        }
        Member existing = memberRepository.findByIdForUpdate(id);
        if (existing == null) {
            throw new BizException("会员不存在：" + id);
        }
        if (Dict.MEMBER_MERGED.equals(existing.status)) {
            throw new BizException("该档案为已合并只读别名，积分已归入主档会员#" + existing.mergedToId);
        }
        int newPoints = existing.points + req.delta;
        if (newPoints < 0) {
            throw new BizException("调整后积分不能为负（当前 " + existing.points + "，变动 " + req.delta + "）");
        }
        ledgerService.append(existing, req.delta, Dict.LEDGER_MANUAL, null, null,
                req.reason, req.operator);
        existing.level = Dict.decideMemberLevel(existing.points);
        return memberRepository.save(existing);
    }
}
