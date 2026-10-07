package com.milktea.bar.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.milktea.bar.common.BizException;
import com.milktea.bar.common.Dict;
import com.milktea.bar.dto.MemberMergeCreateRequest;
import com.milktea.bar.dto.MemberMergeDetail;
import com.milktea.bar.dto.MemberMergePreview;
import com.milktea.bar.dto.MemberMergeRevokeRequest;
import com.milktea.bar.entity.Member;
import com.milktea.bar.entity.MemberContactAudit;
import com.milktea.bar.entity.MemberMergeEvent;
import com.milktea.bar.entity.MemberMergeItem;
import com.milktea.bar.entity.MemberMergeOrder;
import com.milktea.bar.entity.MemberMergeRequest;
import com.milktea.bar.entity.MemberPhoneBinding;
import com.milktea.bar.repository.CupOrderRepository;
import com.milktea.bar.repository.MemberContactAuditRepository;
import com.milktea.bar.repository.MemberMergeEventRepository;
import com.milktea.bar.repository.MemberMergeItemRepository;
import com.milktea.bar.repository.MemberMergeOrderRepository;
import com.milktea.bar.repository.MemberMergeRequestRepository;
import com.milktea.bar.repository.MemberPhoneBindingRepository;
import com.milktea.bar.repository.MemberPointsLedgerRepository;
import com.milktea.bar.repository.MemberRepository;

/**
 * 会员档案合并与撤销（全程单事务 + 悲观行锁 + 乐观版本兜底）。
 *
 * <p>流程：店员把重复会员标记为「待处理」并选择主档 → 执行前检查手机号冲突与积分来源，
 * 有冲突进入「冲突待处理」绝不静默覆盖 → 店员解决后重新检查（同一执行入口，天然幂等）→
 * 检查通过则历史订单与积分流水统一改挂主档，副档转只读别名 → 仅负责人可撤销，
 * 撤销时订单与流水按来源整批挂回，不重复计算积分。</p>
 */
@Service
public class MemberMergeService {

    private final MemberMergeRequestRepository requestRepository;
    private final MemberMergeItemRepository itemRepository;
    private final MemberMergeOrderRepository mergeOrderRepository;
    private final MemberMergeEventRepository eventRepository;
    private final MemberRepository memberRepository;
    private final MemberPhoneBindingRepository bindingRepository;
    private final MemberPointsLedgerRepository ledgerRepository;
    private final CupOrderRepository cupOrderRepository;
    private final MemberContactAuditRepository contactAuditRepository;
    private final PointsLedgerService ledgerService;

    public MemberMergeService(MemberMergeRequestRepository requestRepository,
                              MemberMergeItemRepository itemRepository,
                              MemberMergeOrderRepository mergeOrderRepository,
                              MemberMergeEventRepository eventRepository,
                              MemberRepository memberRepository,
                              MemberPhoneBindingRepository bindingRepository,
                              MemberPointsLedgerRepository ledgerRepository,
                              CupOrderRepository cupOrderRepository,
                              MemberContactAuditRepository contactAuditRepository,
                              PointsLedgerService ledgerService) {
        this.requestRepository = requestRepository;
        this.itemRepository = itemRepository;
        this.mergeOrderRepository = mergeOrderRepository;
        this.eventRepository = eventRepository;
        this.memberRepository = memberRepository;
        this.bindingRepository = bindingRepository;
        this.ledgerRepository = ledgerRepository;
        this.cupOrderRepository = cupOrderRepository;
        this.contactAuditRepository = contactAuditRepository;
        this.ledgerService = ledgerService;
    }

    // ============================ 列表 / 详情 ============================

    public List<MemberMergeRequest> list(String status) {
        if (status != null && !status.isBlank()) {
            if (!Dict.isValidMergeStatus(status)) {
                throw new BizException("筛选状态必须是：待处理 / 冲突待处理 / 已合并 / 已撤销");
            }
            return requestRepository.findByStatusOrderByIdDesc(status);
        }
        return requestRepository.findAllByOrderByIdDesc();
    }

    /** 合并单详情：成员前后快照、订单归属迁移、生命周期审计事件、双方当前档案 */
    public MemberMergeDetail detail(Long id) {
        MemberMergeRequest request = requestRepository.findById(id)
                .orElseThrow(() -> new BizException("合并单不存在：" + id));
        MemberMergeDetail dto = new MemberMergeDetail();
        dto.request = request;
        dto.items = itemRepository.findByRequestIdOrderByIdAsc(id);
        dto.orders = mergeOrderRepository.findByRequestIdOrderByIdAsc(id);
        dto.events = eventRepository.findByRequestIdOrderByIdAsc(id);

        List<MemberMergeDetail.CurrentMember> currents = new ArrayList<>();
        for (MemberMergeItem item : dto.items) {
            Member m = memberRepository.findById(item.memberId).orElse(null);
            if (m != null) {
                MemberMergeDetail.CurrentMember cm = new MemberMergeDetail.CurrentMember();
                cm.memberId = m.id;
                cm.role = item.role;
                cm.phone = m.phone;
                cm.name = m.name;
                cm.points = m.points;
                cm.level = m.level;
                cm.status = m.status;
                cm.mergedToId = m.mergedToId;
                currents.add(cm);
            }
        }
        dto.currentMembers = currents;
        List<MemberContactAudit> audits = new ArrayList<>();
        for (MemberMergeItem item : dto.items) {
            audits.addAll(contactAuditRepository.findByMemberIdOrderByIdDesc(item.memberId));
        }
        audits.sort((a, b) -> b.id.compareTo(a.id));
        dto.contactAudits = audits;
        return dto;
    }

    // ============================ 预览（只读，不落数据） ============================

    /**
     * 合并预览：对主档+副档逐行加锁读取最新手机号、姓名、积分与历史订单数，
     * 并排展示，并预演手机号/积分来源检查。
     */
    @Transactional(readOnly = true)
    public MemberMergePreview preview(Long masterId, List<Long> aliasIds) {
        List<Long> ids = normalize(masterId, aliasIds);
        // 只读加锁：预览看到的就是执行时要复核的最新版本
        Map<Long, Member> members = loadMembersLocked(ids);

        MemberMergePreview dto = new MemberMergePreview();
        dto.masterMemberId = masterId;
        dto.master = toSide(members.get(masterId));
        dto.aliases = new ArrayList<>();
        int total = dto.master.ledgerSum;
        for (Long aliasId : new LinkedHashSet<>(aliasIds)) {
            MemberMergePreview.Side side = toSide(members.get(aliasId));
            dto.aliases.add(side);
            total += side.ledgerSum;
        }
        dto.projectedPoints = total;

        List<String> conflicts = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        detectPhoneConflicts(masterId, new LinkedHashSet<>(aliasIds), members, conflicts, detail);
        detectPointsConflicts(members, conflicts, detail);
        dto.conflictTypes = conflicts;
        dto.conflict = !conflicts.isEmpty();
        dto.conflictDetail = detail.length() == 0 ? null : detail.toString();
        return dto;
    }

    // ============================ 创建（标记为待合并） ============================

    /**
     * 店员把重复会员标记为待合并（单事务）：
     * 校验主档/副档均为有效、互不为已合并别名、不存在同一批人的未终结合并单；
     * 立即做一次执行前检查，有冲突直接落「冲突待处理」，无冲突落「待处理」。
     * 重复对同一批会员发起会被稳定拒绝（不能重复标记）。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeRequest create(MemberMergeCreateRequest req) {
        List<Long> ids = normalize(req.masterMemberId, req.aliasMemberIds);
        Map<Long, Member> members = loadMembersLocked(ids);
        Member master = members.get(req.masterMemberId);
        Set<Long> aliasSet = new LinkedHashSet<>(req.aliasMemberIds);

        for (Long id : ids) {
            Member m = members.get(id);
            if (!Dict.MEMBER_ACTIVE.equals(m.status)) {
                throw new BizException("会员#" + id + " 当前为「已合并」别名档（主档会员#"
                        + m.mergedToId + "），不能再次参与合并");
            }
        }
        // 任一成员已挂在未终结合并单（待处理/冲突待处理）中 → 拒绝重复合并
        List<String> open = List.of(Dict.MERGE_PENDING, Dict.MERGE_CONFLICT);
        if (requestRepository.countByMasterMemberIdInAndStatusIn(ids, open) > 0) {
            throw new BizException("所选会员中已有作为主档的待处理/冲突待处理合并单，请勿重复发起合并");
        }
        for (Long id : ids) {
            if (requestRepository.countOpenAsAlias(id, open) > 0) {
                throw new BizException("会员#" + id + " 已存在待处理/冲突待处理的合并单，请勿重复发起合并");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        MemberMergeRequest request = new MemberMergeRequest();
        request.masterMemberId = req.masterMemberId;
        request.reason = req.reason;
        request.createdBy = req.operator;
        request.createdAt = now;
        // 先以最新状态预检查
        List<String> conflicts = new ArrayList<>();
        StringBuilder detail = new StringBuilder();
        detectPhoneConflicts(req.masterMemberId, aliasSet, members, conflicts, detail);
        detectPointsConflicts(members, conflicts, detail);
        if (conflicts.isEmpty()) {
            request.status = Dict.MERGE_PENDING;
        } else {
            request.status = Dict.MERGE_CONFLICT;
            request.conflictType = String.join("、", conflicts);
            request.conflictDetail = detail.toString();
            request.resolvedAt = now;
        }
        requestRepository.save(request);

        for (Long id : ids) {
            Member m = members.get(id);
            MemberMergeItem item = new MemberMergeItem();
            item.requestId = request.id;
            item.memberId = id;
            item.role = id.equals(req.masterMemberId) ? Dict.MERGE_ROLE_MASTER : Dict.MERGE_ROLE_ALIAS;
            item.snapshotPhone = m.phone;
            item.snapshotName = m.name;
            item.snapshotPoints = m.points;
            item.snapshotLevel = m.level;
            item.snapshotOrderCount = cupOrderRepository.findIdsByMemberId(id).size();
            itemRepository.save(item);
        }

        recordEvent(request.id, "创建",
                "店员标记重复会员，选择会员#" + req.masterMemberId + " 为主档，副档 "
                        + aliasSet.stream().map(a -> "#" + a).reduce((a, b) -> a + "、" + b).orElse(""),
                req.operator);
        if (!conflicts.isEmpty()) {
            recordEvent(request.id, "冲突待处理", detail.toString(), req.operator);
        }
        return request;
    }

    // ============================ 执行 / 冲突后重新检查 ============================

    /**
     * 执行合并；冲突待处理单在店员处理完手机号/积分问题后调用同一入口重新检查。
     * 幂等：已是「已合并」直接返回详情（不重复执行）；「已撤销」拒绝；
     * 仍冲突则保持「冲突待处理」并返回最新冲突，不静默覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeDetail execute(Long id, String operator) {
        // 合并单行先加锁：重复合并/并发合并只有一个事务继续
        MemberMergeRequest request = requestRepository.findByIdForUpdate(id);
        if (request == null) {
            throw new BizException("合并单不存在：" + id);
        }
        if (Dict.MERGE_REVOKED.equals(request.status)) {
            throw new BizException("合并单#" + id + " 已撤销，不能再执行合并");
        }
        if (Dict.MERGE_MERGED.equals(request.status)) {
            // 幂等：重复执行直接返回已合并结果
            recordEvent(id, "重新检查", "合并已完成，重复执行被忽略（幂等）", operator);
            return detail(id);
        }

        List<MemberMergeItem> items = itemRepository.findByRequestIdOrderByIdAsc(id);
        List<Long> memberIds = items.stream().map(i -> i.memberId).sorted().toList();
        Map<Long, Member> members = loadMembersLocked(memberIds);
        Member master = members.get(request.masterMemberId);
        Set<Long> aliasIds = new LinkedHashSet<>();
        for (MemberMergeItem item : items) {
            if (Dict.MERGE_ROLE_ALIAS.equals(item.role)) {
                aliasIds.add(item.memberId);
            }
        }

        // 以锁点最新版本校验：成员仍须有效，主档不能已是别名
        for (Long mid : memberIds) {
            Member m = members.get(mid);
            if (!Dict.MEMBER_ACTIVE.equals(m.status)) {
                throw new BizException("会员#" + mid + " 当前不是有效档（状态：" + m.status
                        + "），合并单已失效，请刷新后处理");
            }
        }

        // —— 执行前检查：手机号冲突 + 积分来源冲突，冲突进入「冲突待处理」，绝不静默覆盖 ——
        List<String> conflicts = new ArrayList<>();
        StringBuilder conflictDetail = new StringBuilder();
        detectPhoneConflicts(request.masterMemberId, aliasIds, members, conflicts, conflictDetail);
        detectPointsConflicts(members, conflicts, conflictDetail);
        // 组内同号集合（执行改指绑定用：同号只保留主档绑定，不重复补登）
        Set<String> duplicatePhonesInGroup = new HashSet<>();
        Map<String, Long> seenPhone = new LinkedHashMap<>();
        for (Long mid : memberIds) {
            String p = members.get(mid).phone;
            if (seenPhone.containsKey(p)) {
                duplicatePhonesInGroup.add(p);
            }
            seenPhone.put(p, mid);
        }
        if (!conflicts.isEmpty()) {
            request.status = Dict.MERGE_CONFLICT;
            request.conflictType = String.join("、", conflicts);
            request.conflictDetail = conflictDetail.toString();
            request.resolvedAt = LocalDateTime.now();
            requestRepository.save(request);
            recordEvent(id, "冲突待处理", conflictDetail.toString(), operator);
            throw new BizException("合并前检查未通过，已转入「冲突待处理」：" + conflictDetail);
        }

        // —— 检查通过：订单、积分流水、手机号绑定统一归到主档，副档转只读别名 ——
        LocalDateTime now = LocalDateTime.now();
        int migratedOrders = 0;
        for (Long aliasId : aliasIds) {
            String aliasPhone = members.get(aliasId).phone;

            // 1) 历史订单整批改挂主档，并逐笔登记迁移记录（撤销可逐笔挂回）
            List<Long> orderIds = cupOrderRepository.findIdsByMemberId(aliasId);
            if (!orderIds.isEmpty()) {
                mergeOrderRepository.moveOrders(aliasId, master.id);
                for (Long orderId : orderIds) {
                    MemberMergeOrder mo = new MemberMergeOrder();
                    mo.requestId = id;
                    mo.orderId = orderId;
                    mo.fromMemberId = aliasId;
                    mo.toMemberId = master.id;
                    mo.status = Dict.MERGE_ORDER_MIGRATED;
                    mo.migratedAt = now;
                    mergeOrderRepository.save(mo);
                    migratedOrders++;
                }
            }

            // 2) 积分流水整批改挂主档（不复制、不新增，杜绝重复计算），写 origin 与合并单标记
            ledgerRepository.reassign(id, aliasId, master.id);

            // 3) 手机号有效绑定改指主档（显式 FOR UPDATE 复核最新归属）。
            //    组内同号场景该号绑定已指主档，无需再动；无绑定行且号不冲突时补登指主档。
            MemberPhoneBinding binding = bindingRepository.findByPhoneForUpdate(aliasPhone);
            if (binding != null && binding.memberId.equals(aliasId)) {
                binding.memberId = master.id;
                binding.boundAt = now;
                bindingRepository.save(binding);
            } else if (binding == null && !duplicatePhonesInGroup.contains(aliasPhone)) {
                MemberPhoneBinding reborn = new MemberPhoneBinding();
                reborn.phone = aliasPhone;
                reborn.memberId = master.id;
                reborn.boundAt = now;
                bindingRepository.save(reborn);
            }

            // 4) 副档转只读别名：原手机号/姓名保留，记原因/操作人/时间
            Member aliasRow = memberRepository.findById(aliasId).orElseThrow();
            aliasRow.status = Dict.MEMBER_MERGED;
            aliasRow.mergedToId = master.id;
            aliasRow.mergeReason = request.reason;
            aliasRow.mergedBy = operator;
            aliasRow.mergedAt = now;
            aliasRow.points = 0;
            memberRepository.save(aliasRow);
        }

        // 5) 主档余额以流水合计重算（批量 UPDATE 带 clearAutomatically，此处读到的是最新状态）
        Member masterRow = memberRepository.findById(master.id).orElseThrow();
        int masterPoints = ledgerService.sum(masterRow.id);
        masterRow.points = masterPoints;
        masterRow.level = Dict.decideMemberLevel(masterPoints);
        memberRepository.save(masterRow);

        // 6) 回填成员合并后积分快照（实体在批量 UPDATE clearAutomatically 后已脱管，逐体重载）
        for (MemberMergeItem item : items) {
            MemberMergeItem row = itemRepository.findById(item.id).orElseThrow();
            row.finalPoints = Dict.MERGE_ROLE_MASTER.equals(row.role) ? masterPoints : 0;
            itemRepository.save(row);
        }

        request.status = Dict.MERGE_MERGED;
        request.executedBy = operator;
        request.executedAt = now;
        request.conflictType = null;
        request.conflictDetail = null;
        requestRepository.save(request);
        recordEvent(id, "合并成功",
                "副档 " + aliasIds.stream().map(a -> "#" + a).reduce((a, x) -> a + "、" + x).orElse("")
                        + " 的 " + migratedOrders + " 笔历史订单与积分流水归入主档会员#"
                        + master.id + "，主档积分合计 " + masterPoints,
                operator);
        return detail(id);
    }

    // ============================ 撤销合并（仅负责人） ============================

    /**
     * 撤销合并（单事务，仅负责人）：
     * <ol>
     *   <li>合并单行锁，仅「已合并」可撤销，重复撤销在状态层面稳定拒绝；</li>
     *   <li>会员行按 id 升序加锁；订单按迁移记录逐笔挂回原会员并置「已恢复」；</li>
     *   <li>积分流水按 mergeRequestId + origin 整批挂回副档，主档自身流水不动；</li>
     *   <li>手机号绑定按合并前归属指回；主档/副档余额各自按流水合计重算 —— 不重复计算；</li>
     *   <li>副档恢复「有效」并清空合并留痕字段，合并单置「已撤销」（记录仍可查）。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public MemberMergeDetail revoke(Long id, MemberMergeRevokeRequest req) {
        if (req == null) {
            throw new BizException("撤销请求不能为空");
        }
        if (!Dict.ROLE_OWNER.equals(req.operatorRole)) {
            throw new BizException("只有负责人可以撤销合并（当前角色：" + req.operatorRole + "）");
        }
        MemberMergeRequest request = requestRepository.findByIdForUpdate(id);
        if (request == null) {
            throw new BizException("合并单不存在：" + id);
        }
        if (Dict.MERGE_REVOKED.equals(request.status)) {
            // 重复撤销：稳定拒绝（幂等安全，不恢复第二次）
            throw new BizException("合并单#" + id + " 已撤销，请勿重复操作");
        }
        if (!Dict.MERGE_MERGED.equals(request.status)) {
            throw new BizException("合并单#" + id + " 当前为「" + request.status
                    + "」，只有已合并的合并单可以撤销");
        }

        List<MemberMergeItem> items = itemRepository.findByRequestIdOrderByIdAsc(id);
        List<Long> memberIds = items.stream().map(i -> i.memberId).sorted().toList();
        Map<Long, Member> members = loadMembersLocked(memberIds);
        Member master = members.get(request.masterMemberId);
        LocalDateTime now = LocalDateTime.now();

        // 1) 订单逐笔挂回（以迁移记录为准，只动本合并单迁过的订单；批量更新后实体重载）
        List<MemberMergeOrder> mos = mergeOrderRepository.findByRequestIdOrderByIdAsc(id);
        int restoredOrders = 0;
        for (MemberMergeOrder mo : mos) {
            if (!Dict.MERGE_ORDER_MIGRATED.equals(mo.status)) {
                continue;
            }
            mergeOrderRepository.restoreOrder(mo.orderId, mo.fromMemberId, mo.toMemberId);
            MemberMergeOrder row = mergeOrderRepository.findById(mo.id).orElseThrow();
            row.status = Dict.MERGE_ORDER_RESTORED;
            row.restoredAt = now;
            mergeOrderRepository.save(row);
            restoredOrders++;
        }

        // 2) 积分流水按来源整批挂回；主档自身流水与合并后新增流水不动
        for (MemberMergeItem item : items) {
            if (Dict.MERGE_ROLE_ALIAS.equals(item.role)) {
                ledgerRepository.restoreToOrigin(id, item.memberId, master.id);
            }
        }

        // 3) 副档恢复有效：手机号绑定按快照指回，清空合并留痕；余额按流水重算
        for (MemberMergeItem item : items) {
            if (!Dict.MERGE_ROLE_ALIAS.equals(item.role)) {
                continue;
            }
            Member alias = memberRepository.findById(item.memberId).orElseThrow();
            // 当前挂在主档名下的别名原号绑定（以创建时快照号为准）指回本副档
            MemberPhoneBinding binding = bindingRepository.findByPhoneForUpdate(item.snapshotPhone);
            if (binding != null && binding.memberId.equals(master.id)) {
                binding.memberId = alias.id;
                binding.boundAt = now;
                bindingRepository.save(binding);
            } else if (binding == null) {
                MemberPhoneBinding reborn = new MemberPhoneBinding();
                reborn.phone = item.snapshotPhone;
                reborn.memberId = alias.id;
                reborn.boundAt = now;
                bindingRepository.save(reborn);
            }
            alias.status = Dict.MEMBER_ACTIVE;
            alias.mergedToId = null;
            alias.mergeReason = null;
            alias.mergedBy = null;
            alias.mergedAt = null;
            alias.phone = item.snapshotPhone;
            alias.name = item.snapshotName;
            alias.level = item.snapshotLevel;
            alias.points = ledgerService.sum(alias.id);
            memberRepository.save(alias);
        }

        // 4) 主档余额重算（批量挂回已 clearAutomatically，按最新流水合计回写，不重复计算）
        Member masterRow = memberRepository.findById(master.id).orElseThrow();
        int masterPoints = ledgerService.sum(masterRow.id);
        masterRow.points = masterPoints;
        masterRow.level = Dict.decideMemberLevel(masterPoints);
        memberRepository.save(masterRow);

        request.status = Dict.MERGE_REVOKED;
        request.revokedBy = req.operator;
        request.revokedAt = now;
        request.revokeReason = req.reason;
        requestRepository.save(request);
        recordEvent(id, "撤销合并",
                "负责人撤销合并：" + restoredOrders + " 笔订单按来源挂回，积分流水整批恢复原归属"
                        + "（主档会员#" + master.id + " 积分 " + masterPoints + "），未重复计算",
                req.operator);
        return detail(id);
    }

    // ============================ 执行前检查（手机号冲突 + 积分来源冲突） ============================

    /**
     * 手机号冲突检查（不加提示性“主档号会保留”，凡是系统无法静默决定的情况都算冲突）：
     * <ul>
     *   <li>合并成员之间手机号相同（无法判断保留哪个号）；</li>
     *   <li>某成员手机号在绑定表指向了合并组之外的有效会员（被别人占用）；</li>
     *   <li>成员手机号没有绑定行，但存在另一个有效会员档也登记了同号（历史遗留重复号）。</li>
     * </ul>
     */
    private void detectPhoneConflicts(Long masterId, Set<Long> aliasIds, Map<Long, Member> members,
                                      List<String> conflicts, StringBuilder detail) {
        Set<Long> groupIds = new HashSet<>(aliasIds);
        groupIds.add(masterId);

        // 组内是否有同号
        Map<String, List<Long>> phoneToMembers = new LinkedHashMap<>();
        for (Long id : groupIds) {
            phoneToMembers.computeIfAbsent(members.get(id).phone, k -> new ArrayList<>()).add(id);
        }
        Set<String> conflictPhones = new TreeSet<>();
        for (Map.Entry<String, List<Long>> e : phoneToMembers.entrySet()) {
            if (e.getValue().size() > 1) {
                conflictPhones.add(e.getKey());
            }
        }

        // 对每个成员的号加锁核对当前有效归属（锁点最新绑定）
        List<String> allPhones = groupIds.stream().map(id -> members.get(id).phone)
                .distinct().sorted().toList();
        for (String phone : allPhones) {
            MemberPhoneBinding binding = bindingRepository.findByPhoneForUpdate(phone);
            if (binding != null && !groupIds.contains(binding.memberId)) {
                conflictPhones.add(phone);
            }
            if (binding == null) {
                // 无绑定行：查同号的其他有效会员（历史遗留重复档）
                List<Member> samePhoneActive = memberRepository.search(phone).stream()
                        .filter(m -> Dict.MEMBER_ACTIVE.equals(m.status) && !groupIds.contains(m.id))
                        .toList();
                if (!samePhoneActive.isEmpty()) {
                    conflictPhones.add(phone);
                }
            }
        }

        if (!conflictPhones.isEmpty()) {
            conflicts.add(Dict.CONFLICT_PHONE);
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("手机号冲突：以下手机号在合并成员间重复或已被其他有效会员占用，需先确定保留哪个号、解绑/改号后再合并：")
                    .append(String.join("、", conflictPhones));
        }
    }

    /**
     * 积分来源检查：会员余额必须与其积分流水合计一致；
     * 不一致说明存在未落流水的积分调整，来源不清，进入「冲突待处理」由店员核实补记。
     */
    private void detectPointsConflicts(Map<Long, Member> members,
                                       List<String> conflicts, StringBuilder detail) {
        List<String> drifts = new ArrayList<>();
        for (Member m : members.values()) {
            int ledgerSum = ledgerService.sum(m.id);
            if (ledgerSum != m.points) {
                drifts.add("会员#" + m.id + "（" + m.name + "）余额 " + m.points
                        + " 与积分流水合计 " + ledgerSum + " 不一致（差额 " + (m.points - ledgerSum) + "）");
            }
        }
        if (!drifts.isEmpty()) {
            conflicts.add(Dict.CONFLICT_POINTS);
            if (detail.length() > 0) {
                detail.append("；");
            }
            detail.append("积分来源冲突：").append(String.join("；", drifts))
                    .append("，需核实积分来源并补记/修正流水后再合并");
        }
    }

    // ============================ 内部工具 ============================

    private List<Long> normalize(Long masterId, List<Long> aliasIds) {
        if (masterId == null) {
            throw new BizException("必须选择保留的主档会员");
        }
        if (aliasIds == null || aliasIds.isEmpty()) {
            throw new BizException("至少选择一个待合并的重复会员");
        }
        Set<Long> distinct = new LinkedHashSet<>(aliasIds);
        if (distinct.contains(masterId)) {
            throw new BizException("主档不能同时作为待合并副档");
        }
        List<Long> ids = new ArrayList<>();
        ids.add(masterId);
        ids.addAll(distinct);
        return ids;
    }

    /** 会员行统一按 id 升序加写锁（与下单/退款路径一致，杜绝交叉死锁） */
    private Map<Long, Member> loadMembersLocked(List<Long> ids) {
        Map<Long, Member> map = new LinkedHashMap<>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            Member m = memberRepository.findByIdForUpdate(id);
            if (m == null) {
                throw new BizException("会员不存在：" + id);
            }
            map.put(id, m);
        }
        return map;
    }

    private MemberMergePreview.Side toSide(Member m) {
        MemberMergePreview.Side side = new MemberMergePreview.Side();
        side.memberId = m.id;
        side.phone = m.phone;
        side.name = m.name;
        side.points = m.points;
        side.level = m.level;
        side.status = m.status;
        side.orderCount = cupOrderRepository.findIdsByMemberId(m.id).size();
        side.ledgerSum = ledgerService.sum(m.id);
        return side;
    }

    private void recordEvent(Long requestId, String event, String detail, String operator) {
        MemberMergeEvent e = new MemberMergeEvent();
        e.requestId = requestId;
        e.event = event;
        e.detail = detail;
        e.operator = operator;
        e.createdAt = LocalDateTime.now();
        eventRepository.save(e);
    }
}
