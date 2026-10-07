SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 奶茶饮品店 · 原料库存与配方出单 + 会员档案合并/联系方式变更审计
-- 库名 milktea_bar；docker 构建期实际建库使用 mysql/schema.sql 本文件内容一致。
-- mysql 容器是 UTC，时间戳一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR（北京时间）。

CREATE DATABASE IF NOT EXISTS milktea_bar
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE milktea_bar;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS member_contact_audit;
DROP TABLE IF EXISTS member_merge_event;
DROP TABLE IF EXISTS member_merge_order;
DROP TABLE IF EXISTS member_merge_item;
DROP TABLE IF EXISTS member_merge_request;
DROP TABLE IF EXISTS member_points_ledger;
DROP TABLE IF EXISTS member_phone_binding;
DROP TABLE IF EXISTS cup_order_stock_snapshot;
DROP TABLE IF EXISTS cup_order_failure;
DROP TABLE IF EXISTS recipe_topping;
DROP TABLE IF EXISTS cup_order_item;
DROP TABLE IF EXISTS cup_order;
DROP TABLE IF EXISTS recipe;
DROP TABLE IF EXISTS member;
DROP TABLE IF EXISTS ingredient;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 原料库存 ----------------
CREATE TABLE ingredient (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL UNIQUE COMMENT '原料名',
    category   VARCHAR(16) NOT NULL COMMENT '茶底 / 奶 / 小料 / 糖浆',
    stock      INT         NOT NULL COMMENT '库存',
    unit       VARCHAR(16) NOT NULL COMMENT '单位',
    status     VARCHAR(16) NOT NULL COMMENT '启用 / 停用',
    created_at DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 配方 ----------------
CREATE TABLE recipe (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL UNIQUE COMMENT '配方名',
    base_tea   VARCHAR(64) NOT NULL COMMENT '茶底',
    price      DECIMAL(10,2) NOT NULL COMMENT '售价',
    created_at DATETIME    COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 配方小料用量：@ElementCollection + @CollectionTable + @MapKeyColumn 承载 Map<String,Integer>
CREATE TABLE recipe_topping (
    recipe_id     BIGINT NOT NULL,
    topping_name  VARCHAR(64) NOT NULL COMMENT '小料名（= ingredient.name）',
    qty           INT NOT NULL COMMENT '份数',
    PRIMARY KEY (recipe_id, topping_name),
    CONSTRAINT fk_topping_recipe FOREIGN KEY (recipe_id) REFERENCES recipe (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员 ----------------
-- status：有效 / 已合并。已合并档只读保留别名（phone/name 不再改动），
-- merged_to_id 指向保留的主档；查会员时副档可解析回主档。
-- version 为 JPA 乐观锁版本，联系方式/积分/合并的并发修改以最新版本为准。
-- 注意：手机号“有效唯一”不在 member 表做 UNIQUE —— 已合并别名要原样保留旧号，
-- 唯一性由 member_phone_binding（仅登记有效归属）在服务端加锁维护。
CREATE TABLE member (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    phone          VARCHAR(24) NOT NULL COMMENT '手机号（已合并档只读别名保留）',
    name           VARCHAR(32) NOT NULL COMMENT '昵称',
    points         INT         NOT NULL DEFAULT 0 COMMENT '当前积分（恒等于名下积分流水之和）',
    level          VARCHAR(16) NOT NULL COMMENT '普通 / 银卡 / 金卡',
    status         VARCHAR(16) NOT NULL DEFAULT '有效' COMMENT '有效 / 已合并',
    merged_to_id   BIGINT       COMMENT '合并后主档 id（未合并为 NULL）',
    merge_reason   VARCHAR(255) COMMENT '被合并原因（副档只读留痕）',
    merged_by      VARCHAR(32)  COMMENT '合并操作人（副档只读留痕）',
    merged_at      DATETIME     COMMENT '合并时间（副档只读留痕）',
    version        INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at     DATETIME     COMMENT '创建时间',
    KEY idx_member_status (status),
    KEY idx_member_merged_to (merged_to_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 手机号有效绑定 ----------------
-- 一个手机号在任意时刻只能指向一个“有效”会员；合并时副档手机号改指主档，
-- 撤销合并时按快照指回。并发改手机号在本行 FOR UPDATE 上串行化，
-- 后到事务以最新归属复核，得到稳定的“已绑定其他会员”拒绝，不靠先查后插。
CREATE TABLE member_phone_binding (
    phone       VARCHAR(24) NOT NULL COMMENT '手机号',
    member_id   BIGINT      NOT NULL COMMENT '当前有效归属会员 id',
    bound_at    DATETIME    COMMENT '最近绑定时间',
    PRIMARY KEY (phone),
    KEY idx_binding_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员积分流水 ----------------
-- 每笔积分变动一行；会员积分余额 = SUM(change_points)。
-- 合并：副档流水整批 UPDATE 到主档并记 merge_request_id / origin_member_id；
-- 撤销：按 origin_member_id 整批挂回，期间主档新增流水不动 —— 积分不重复计算。
CREATE TABLE member_points_ledger (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id         BIGINT      NOT NULL COMMENT '当前归属会员',
    change_points     INT         NOT NULL COMMENT '本次变动（正加负减）',
    balance_after     INT         NOT NULL COMMENT '变动后余额（当时快照，不再回改）',
    type              VARCHAR(16) NOT NULL COMMENT '期初 / 消费获得 / 退款回退 / 手工调整 / 合并迁入 / 撤销恢复',
    ref_type          VARCHAR(16) COMMENT '关联类型：订单 / 合并',
    ref_id            BIGINT      COMMENT '关联 id（订单号或合并单）',
    merge_request_id  BIGINT      COMMENT '经由哪张合并单迁移（ provenance ）',
    origin_member_id  BIGINT      COMMENT '合并迁移前的原归属会员',
    reason            VARCHAR(255) COMMENT '事由（手工调整/合并/撤销等）',
    operator          VARCHAR(32) COMMENT '操作人',
    created_at        DATETIME    COMMENT '创建时间',
    KEY idx_ledger_member (member_id, id),
    KEY idx_ledger_merge (merge_request_id),
    KEY idx_ledger_origin (origin_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 会员合并申请 ----------------
-- 状态机：待处理 →（执行前检查不通过）冲突待处理 →（解决后重新检查）已合并；
-- 已合并 →（仅负责人）已撤销。重复合并/重复撤销在状态与行锁层面稳定拒绝。
CREATE TABLE member_merge_request (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    status           VARCHAR(16) NOT NULL COMMENT '待处理 / 冲突待处理 / 已合并 / 已撤销',
    reason           VARCHAR(255) NOT NULL COMMENT '合并原因',
    created_by       VARCHAR(32) NOT NULL COMMENT '发起人（店员）',
    master_member_id BIGINT      NOT NULL COMMENT '保留的主档会员 id',
    conflict_type    VARCHAR(64) COMMENT '冲突类型：手机号冲突 / 积分来源冲突（可并列）',
    conflict_detail  VARCHAR(500) COMMENT '冲突明细（哪些号、哪档流水不平）',
    executed_by      VARCHAR(32) COMMENT '执行合并操作人',
    executed_at      DATETIME    COMMENT '合并成功时间',
    revoked_by       VARCHAR(32) COMMENT '撤销发起人（须负责人）',
    revoked_at       DATETIME    COMMENT '撤销时间',
    revoke_reason    VARCHAR(255) COMMENT '撤销原因',
    resolved_at      DATETIME    COMMENT '最近一次冲突解决/重新检查时间',
    version          INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    created_at       DATETIME    COMMENT '创建时间',
    KEY idx_merge_status (status),
    KEY idx_merge_master (master_member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 合并成员与合并前后快照（含主档最终积分，供“合并前后差异”详情查看）
CREATE TABLE member_merge_item (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id             BIGINT      NOT NULL,
    member_id              BIGINT      NOT NULL,
    role                   VARCHAR(8)  NOT NULL COMMENT '主档 / 副档',
    snapshot_phone         VARCHAR(24) NOT NULL COMMENT '合并前手机号快照',
    snapshot_name          VARCHAR(32) NOT NULL COMMENT '合并前姓名快照',
    snapshot_points        INT         NOT NULL COMMENT '合并前积分快照',
    snapshot_level         VARCHAR(16) NOT NULL COMMENT '合并前等级快照',
    snapshot_order_count   INT         NOT NULL COMMENT '合并前历史订单数',
    final_points           INT         COMMENT '合并后该归属积分（主档=合计，副档=0）',
    KEY idx_item_request (request_id),
    KEY idx_item_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 订单归属迁移记录：合并时 已迁移（from→to=主档），撤销时 已恢复
CREATE TABLE member_merge_order (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id     BIGINT      NOT NULL,
    order_id       BIGINT      NOT NULL,
    from_member_id BIGINT      NOT NULL COMMENT '迁移前归属',
    to_member_id   BIGINT      NOT NULL COMMENT '迁移目标（主档）',
    status         VARCHAR(8)  NOT NULL COMMENT '已迁移 / 已恢复',
    migrated_at    DATETIME,
    restored_at    DATETIME,
    KEY idx_morder_request (request_id),
    KEY idx_morder_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 合并单生命周期审计事件（创建/冲突/合并/撤销）
CREATE TABLE member_merge_event (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  BIGINT      NOT NULL,
    event       VARCHAR(32) NOT NULL COMMENT '创建 / 冲突待处理 / 合并成功 / 撤销合并 / 重新检查',
    detail      VARCHAR(500) COMMENT '事件说明',
    operator    VARCHAR(32) COMMENT '操作人',
    created_at  DATETIME    COMMENT '创建时间',
    KEY idx_event_request (request_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 联系方式（手机号/姓名）变更审计 ----------------
-- 每次改动保留前后值、原因、生效时间、操作人；合并导致的归属变化用 merge_request_id 关联。
CREATE TABLE member_contact_audit (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id         BIGINT      NOT NULL COMMENT '被改会员',
    field             VARCHAR(8)  NOT NULL COMMENT '手机号 / 姓名',
    old_value         VARCHAR(64) COMMENT '变更前值',
    new_value         VARCHAR(64) COMMENT '变更后值',
    reason            VARCHAR(255) NOT NULL COMMENT '变更原因',
    effective_at      DATETIME    NOT NULL COMMENT '生效时间',
    operator          VARCHAR(32) NOT NULL COMMENT '操作人',
    merge_request_id  BIGINT      COMMENT '由哪张合并单间接触发（可空）',
    created_at        DATETIME    COMMENT '记录时间',
    KEY idx_audit_member (member_id, id),
    KEY idx_audit_merge (merge_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 出杯记录 ----------------
CREATE TABLE cup_order (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id     BIGINT     COMMENT '会员 id（散客为空；合并后为当前主档 id）',
    pay_type      VARCHAR(16) NOT NULL COMMENT '微信 / 支付宝 / 现金',
    total         DECIMAL(10,2) NOT NULL COMMENT '金额合计（自动汇总）',
    status        VARCHAR(16) NOT NULL COMMENT '已出杯 / 已退款',
    points_earned INT         COMMENT '本次为会员带来的积分（退款回退用）',
    created_at    DATETIME    COMMENT '创建时间',
    KEY idx_order_member (member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cup_order_item (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id   BIGINT NOT NULL COMMENT '所属出杯单',
    recipe_id  BIGINT NOT NULL COMMENT '配方 id',
    qty        INT    NOT NULL COMMENT '杯数',
    CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES cup_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 原料扣减快照 ----------------
-- 新订单出杯时按当次配方实际用量保存，之后配方/原料如何编辑都不改变历史消耗；
-- 退款时严格按快照逐行回补，refund_qty 为 NULL 表示尚未回补。
CREATE TABLE cup_order_stock_snapshot (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT      NOT NULL COMMENT '所属出杯单',
    ingredient_id   BIGINT      NOT NULL COMMENT '原料 id（可追溯）',
    ingredient_name VARCHAR(64) NOT NULL COMMENT '下单时原料名（快照）',
    unit            VARCHAR(16) NOT NULL COMMENT '下单时单位（快照）',
    used_qty        INT         NOT NULL COMMENT '本次实际扣减量',
    refund_qty      INT         COMMENT '退款已回补量（未退款为 NULL）',
    CONSTRAINT fk_snapshot_order FOREIGN KEY (order_id) REFERENCES cup_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 出杯失败记录 ----------------
CREATE TABLE cup_order_failure (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    member_id     BIGINT       COMMENT '会员 id（散客为空）',
    member_label  VARCHAR(64)  COMMENT '下单时会员描述（快照）',
    pay_type      VARCHAR(16)  NOT NULL COMMENT '支付方式',
    request_items VARCHAR(255) NOT NULL COMMENT '请求的配方与杯数',
    reason        VARCHAR(500) NOT NULL COMMENT '失败原因',
    created_at    DATETIME     COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT IGNORE INTO ingredient (name, category, stock, unit, status, created_at) VALUES
('红茶',     '茶底', 50, 'g',  '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('绿茶',     '茶底', 40, 'g',  '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('乌龙茶',   '茶底', 30, 'g',  '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('鲜牛奶',   '奶',   60, 'ml', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
('珍珠',     '小料', 100, '份', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('椰果',     '小料', 80,  '份', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('红豆',     '小料', 70,  '份', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 55 DAY),
('布丁',     '小料', 40, '份', '停用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('黑糖糖浆', '糖浆', 90, 'ml', '启用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
('蜂蜜',     '糖浆', 35, 'ml', '停用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY);

INSERT IGNORE INTO recipe (name, base_tea, price, created_at) VALUES
('招牌珍珠奶茶', '红茶', 12.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('椰果绿茶',     '绿茶', 11.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('红豆乌龙',     '乌龙茶', 13.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
('双倍珍珠奶茶', '红茶', 15.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
('椰香红豆奶',   '绿茶', 12.00, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY);

INSERT IGNORE INTO recipe_topping (recipe_id, topping_name, qty) VALUES
(1, '珍珠', 2), (1, '鲜牛奶', 1),
(2, '椰果', 2),
(3, '红豆', 2), (3, '黑糖糖浆', 1),
(4, '珍珠', 3), (4, '鲜牛奶', 1),
(5, '红豆', 1), (5, '椰果', 1);

-- 会员：1~6 为原始会员；7 已并入 1；8 与 2 合并后已撤销（恢复有效）；
-- 9 是 3 的重复档（待处理合并）；10 是 4 的重复档（同号 + 积分流水不平，冲突待处理）。
INSERT IGNORE INTO member (id, phone, name, points, level, status, merged_to_id, merge_reason, merged_by, merged_at, version, created_at) VALUES
(1,  '13800001111', '小明',         380,  '普通', '有效',   NULL, NULL, NULL, NULL, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(2,  '13800002222', '小红',         600,  '银卡', '有效',   NULL, NULL, NULL, NULL, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(3,  '13800003333', '阿强',         2200, '金卡', '有效',   NULL, NULL, NULL, NULL, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(4,  '13800004444', '丽丽',         120,  '普通', '有效',   NULL, NULL, NULL, NULL, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
(5,  '13800005555', '老王',         1800, '银卡', '有效',   NULL, NULL, NULL, NULL, 0, UTC_TIMESTAMP() + INTERVAL 18 DAY),
(6,  '13800006666', '婷婷',         50,   '普通', '有效',   NULL, NULL, NULL, NULL, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 DAY),
(7,  '13800007777', '小明（重复档）', 0,   '普通', '已合并', 1, '顾客同持两张卡，确认并入主档', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 DAY),
(8,  '13800008888', '小红（旧号）',  150,  '普通', '有效',   NULL, NULL, NULL, NULL, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 17 DAY),
(9,  '13800009999', '阿强（旧卡）',  260,  '普通', '有效',   NULL, NULL, NULL, NULL, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 16 DAY),
(10, '13800004444', '丽丽（重复档）', 30,  '普通', '有效',   NULL, NULL, NULL, NULL, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 16 DAY);

-- 手机号有效绑定：7 的号已随合并指到主档 1；10 是历史遗留重复号，无绑定行（归属以 4 为准）。
INSERT IGNORE INTO member_phone_binding (phone, member_id, bound_at) VALUES
('13800001111', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('13800007777', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('13800002222', 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('13800008888', 8, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('13800003333', 3, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
('13800009999', 9, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 16 DAY),
('13800004444', 4, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
('13800005555', 5, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
('13800006666', 6, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY);

INSERT IGNORE INTO cup_order (id, member_id, pay_type, total, status, points_earned, created_at) VALUES
(1,  1,    '微信',   24.00, '已出杯', 24, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(2,  2,    '支付宝', 11.00, '已出杯', 11, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 DAY),
(3,  3,    '现金',   28.00, '已出杯', 28, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 DAY),
(4,  1,    '微信',   36.00, '已退款', 36, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 DAY),
(5,  NULL, '现金',   12.00, '已出杯', 0,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(6,  1,    '微信',   28.00, '已出杯', 28, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(7,  8,    '支付宝', 23.00, '已出杯', 23, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(8,  9,    '微信',   35.00, '已出杯', 35, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT IGNORE INTO cup_order_item (order_id, recipe_id, qty) VALUES
(1, 1, 2),
(2, 2, 1),
(3, 3, 1), (3, 4, 1),
(4, 5, 3),
(5, 1, 1),
(6, 3, 1), (6, 4, 1),
(7, 2, 1), (7, 5, 1),
(8, 1, 2), (8, 2, 1);

-- 积分流水（会员余额 = 名下各行 change_points 之和）：
-- 会员 1：期初 276 + 订单1 +24 + 订单4 +36 −36 = 300；
--          合并单 1 把会员 7 的两行（期初 52 + 订单6 +28 = 80）整批改挂到 1（origin_member_id=7），合计 380。
--          balance_after 为变动当时快照，改挂/撤销只换归属不回改历史余额。
-- 会员 8：期初 127 + 订单7 +23 = 150；合并单 2 曾把两行挂到主档 2，撤销后按 origin 整批挂回 8，主档 2 回到 600。
-- 会员 4：流水合计 100，但 member.points=120（积分来源不平，供合并单 3 冲突演示）。
INSERT IGNORE INTO member_points_ledger (id, member_id, change_points, balance_after, type, ref_type, ref_id, merge_request_id, origin_member_id, reason, operator, created_at) VALUES
(1,  1, 276, 276, '期初',     NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(2,  1, 24,  300, '消费获得', '订单', 1, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(3,  1, 36,  336, '消费获得', '订单', 4, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 DAY),
(4,  1, -36, 300, '退款回退', '订单', 4, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 DAY),
(5,  1, 52,  52,  '期初',     NULL, NULL, 1, 7, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 19 DAY),
(6,  1, 28,  80,  '消费获得', '订单', 6, 1, 7, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(7,  2, 589, 589, '期初',     NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(8,  2, 11,  600, '消费获得', '订单', 2, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 DAY),
(9,  8, 127, 127, '期初',     NULL, NULL, 2, 8, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 17 DAY),
(10, 8, 23,  150, '消费获得', '订单', 7, 2, 8, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(11, 3, 2172, 2172, '期初',   NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY),
(12, 3, 28,  2200, '消费获得', '订单', 3, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 DAY),
(13, 4, 100, 100, '期初',     NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
(14, 10, 30, 30,  '期初',     NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 16 DAY),
(15, 5, 1800, 1800, '期初',   NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 18 DAY),
(16, 6, 50,  50,  '期初',     NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 15 DAY),
(17, 9, 225, 225, '期初',     NULL, NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 16 DAY),
(18, 9, 35,  260, '消费获得', '订单', 8, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

-- 合并单 1：7 → 1 已合并（订单 6 已迁移）；合并单 2：8 → 2 已撤销（订单 7 已恢复）；
-- 合并单 3：10 → 4 冲突待处理（同号 + 会员 4 流水不平）；合并单 4：9 → 3 待处理。
INSERT IGNORE INTO member_merge_request (id, status, reason, created_by, master_member_id, conflict_type, conflict_detail, executed_by, executed_at, revoked_by, revoked_at, revoke_reason, resolved_at, version, created_at) VALUES
(1, '已合并',   '顾客同持两张卡，确认并入主档', '店员小美', 1, NULL, NULL,
   '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, NULL, NULL, NULL, NULL, 1,
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, '已撤销',   '误以为是同一人，实际为两位顾客', '店员小美', 2, NULL, NULL,
   '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY,
   '店长阿峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, '核实为两位不同顾客，撤销合并恢复归属',
   NULL, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(3, '冲突待处理', '系统提示两档手机号相同，疑似重复建档', '店员小美', 4,
   '手机号冲突、积分来源冲突',
   '会员#4 与会员#10 手机号同为 13800004444；会员#4 积分余额 120 与积分流水合计 100 不一致（差额 20），需核实积分来源',
   NULL, NULL, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, 0,
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, '待处理',   '顾客换卡前旧卡仍有积分，申请并入', '店员小美', 3, NULL, NULL,
   NULL, NULL, NULL, NULL, NULL, NULL, 0,
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT IGNORE INTO member_merge_item (id, request_id, member_id, role, snapshot_phone, snapshot_name, snapshot_points, snapshot_level, snapshot_order_count, final_points) VALUES
(1, 1, 1, '主档', '13800001111', '小明', 300, '普通', 2, 380),
(2, 1, 7, '副档', '13800007777', '小明（重复档）', 80, '普通', 1, 0),
(3, 2, 2, '主档', '13800002222', '小红', 600, '银卡', 1, 750),
(4, 2, 8, '副档', '13800008888', '小红（旧号）', 150, '普通', 1, 0),
(5, 3, 4, '主档', '13800004444', '丽丽', 120, '普通', 0, NULL),
(6, 3, 10, '副档', '13800004444', '丽丽（重复档）', 30, '普通', 0, NULL),
(7, 4, 3, '主档', '13800003333', '阿强', 2200, '金卡', 1, NULL),
(8, 4, 9, '副档', '13800009999', '阿强（旧卡）', 260, '普通', 1, NULL);

INSERT IGNORE INTO member_merge_order (id, request_id, order_id, from_member_id, to_member_id, status, migrated_at, restored_at) VALUES
(1, 1, 6, 7, 1, '已迁移', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, NULL),
(2, 2, 7, 8, 2, '已恢复', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT IGNORE INTO member_merge_event (id, request_id, event, detail, operator, created_at) VALUES
(1, 1, '创建',     '店员标记重复会员，选择会员#1 为主档', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, 1, '合并成功', '副档会员#7 的 1 笔历史订单与 80 积分归入主档会员#1，主档合计 380', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3, 2, '创建',     '店员标记重复会员，选择会员#2 为主档', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(4, 2, '合并成功', '副档会员#8 的 1 笔历史订单与 150 积分归入主档会员#2，主档合计 750', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(5, 2, '撤销合并', '负责人核实为两位不同顾客：订单 7 挂回会员#8，积分按流水来源恢复（主档 600 / 副档 150），未重复计算', '店长阿峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(6, 3, '创建',       '店员标记重复会员，选择会员#4 为主档', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(7, 3, '冲突待处理', '手机号冲突、积分来源冲突：同号 13800004444；会员#4 流水合计 100 与余额 120 不平', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(8, 4, '创建',       '店员标记重复会员，选择会员#3 为主档', '店员小美', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

-- 联系方式变更审计演示：会员 6 曾换号
INSERT IGNORE INTO member_contact_audit (id, member_id, field, old_value, new_value, reason, effective_at, operator, merge_request_id, created_at) VALUES
(1, 6, '手机号', '13800006660', '13800006666', '顾客更换手机号', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY, '店员小美', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY);
