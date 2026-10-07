package com.milktea.bar.config;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * recipe 表金额口径的启动期幂等迁移（兼容仍在旧 schema 上运行的库）：
 * <ol>
 *   <li>把 price 放宽到 DECIMAL(12,4) 以保留历史多位小数的精确值；</li>
 *   <li>新增 price_raw 原样承接历史值，新增 version 乐观锁列；</li>
 *   <li>非规范价格（空 / 0 / 负 / 超过两位小数 / 超上限）的规范列 price 置 NULL，
 *       由读取侧显式降级提示——全程不做四舍五入；</li>
 *   <li>price 收口回 DECIMAL(10,2) NULL。</li>
 * </ol>
 * 已是新 schema 时所有步骤跳过。重建镜像的环境由 schema.sql 直接得到同样结构。
 */
@Component
@Order(0)
public class RecipeSchemaMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecipeSchemaMigration.class);

    private final JdbcTemplate jdbc;

    public RecipeSchemaMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!tableExists("recipe")) {
            return;
        }
        boolean hasRaw = columnExists("recipe", "price_raw");
        boolean hasVersion = columnExists("recipe", "version");
        boolean priceNullable = isPriceNullable();
        if (hasRaw && hasVersion && priceNullable) {
            return; // 已是新口径
        }

        log.warn("[recipe 迁移] 检测到旧版 recipe 结构，开始金额口径迁移（保留历史原值，不四舍五入）");
        if (!hasRaw) {
            // 先放宽到 4 位小数，保证历史值在后续复制时不被列定义提前取舍
            jdbc.execute("ALTER TABLE recipe MODIFY COLUMN price DECIMAL(12,4) NULL");
            jdbc.execute("ALTER TABLE recipe ADD COLUMN price_raw DECIMAL(12,4) NULL AFTER price");
            jdbc.execute("UPDATE recipe SET price_raw = price WHERE price_raw IS NULL");
            // 非规范价格：规范列显式置空，交由读取侧降级（原始值保留在 price_raw）。
            // scale 判定用「放大 100 倍后是否等于其取整」识别超过两位小数的残值，不依赖 ROUND 取舍方向。
            jdbc.execute("UPDATE recipe SET price = NULL WHERE price IS NULL OR price <= 0 "
                    + "OR price * 100 <> TRUNCATE(price * 100, 0) OR price > 99999999.99");
        }
        if (!hasVersion) {
            jdbc.execute("ALTER TABLE recipe ADD COLUMN version INT NOT NULL DEFAULT 0");
        }
        // 新列加齐后再收口规范列：此时所有非规范值已挪到 price_raw，NULL 行不会触发截断
        jdbc.execute("ALTER TABLE recipe MODIFY COLUMN price DECIMAL(10,2) NULL");
        log.warn("[recipe 迁移] 迁移完成：异常历史价格已置为降级态（price=NULL，原值见 price_raw）");
    }

    private boolean tableExists(String table) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class, table);
        return c != null && c > 0;
    }

    private boolean columnExists(String table, String column) {
        List<String> names = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                String.class, table, column);
        return !names.isEmpty();
    }

    private boolean isPriceNullable() {
        String nullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = 'recipe' AND column_name = 'price'",
                String.class);
        return "YES".equalsIgnoreCase(nullable);
    }
}
