package com.opspilot.demo.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 种子数据：demo_order 固定 5 万行（Random(42) 可复现），remark 无索引作为慢 SQL 素材。
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final int TARGET = 50_000;
    private static final int BATCH = 1_000;

    private final JdbcTemplate jdbcTemplate;

    private static final String[] REMARK_WORDS = {
            "用户要求尽快发货", "双十一优惠活动订单", "客户备注发票抬头需修改", "会员积分抵扣部分金额",
            "礼品包装请勿放价格单", "分批配送先到先发", "优惠券已使用不可退", "企业采购月结订单",
            "收货地址临时变更", "预售商品等待补货", "老客户回购赠送小样", "周末派送请电话联系"
    };

    public DataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM demo_order", Long.class);
        if (count != null && count >= TARGET) {
            log.info("demo_order 已有 {} 行数据，跳过种子初始化", count);
            return;
        }
        log.info("开始初始化 demo_order 种子数据 {} 行...", TARGET);
        Random random = new Random(42);
        long base = Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli();
        long span = 90L * 24 * 3600 * 1000;

        String sql = "INSERT INTO demo_order(order_no, user_id, amount, status, created_at, remark) VALUES (?,?,?,?,?,?)";
        long start = System.currentTimeMillis();
        int inserted = 0;
        while (inserted < TARGET) {
            List<Object[]> batch = new ArrayList<>(BATCH);
            for (int i = 0; i < BATCH && inserted < TARGET; i++, inserted++) {
                String orderNo = "OD" + String.format("%010d", inserted + 1);
                long userId = 1 + random.nextInt(10_000);
                BigDecimal amount = BigDecimal.valueOf(1 + random.nextDouble() * 5000)
                        .setScale(2, RoundingMode.HALF_UP);
                int status = random.nextInt(3);
                Timestamp createdAt = new Timestamp(base + (long) (random.nextDouble() * span));
                String remark = buildRemark(random);
                batch.add(new Object[]{orderNo, userId, amount, status, createdAt, remark});
            }
            jdbcTemplate.batchUpdate(sql, batch);
        }
        log.info("demo_order 种子数据初始化完成，{} 行，耗时 {}ms", TARGET, System.currentTimeMillis() - start);
    }

    private String buildRemark(Random random) {
        StringBuilder sb = new StringBuilder();
        int n = 2 + random.nextInt(4);
        for (int i = 0; i < n; i++) {
            sb.append(REMARK_WORDS[random.nextInt(REMARK_WORDS.length)]).append(";");
        }
        return sb.toString();
    }
}
