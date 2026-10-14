package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.ZonedDateTime;

@Component
@Slf4j
@RequiredArgsConstructor
public class DetailTableManager implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        prepare();
    }

    @Scheduled(cron="0 0 * * * *",zone="Asia/Shanghai")
    public void tick() {
        try {prepare();}
        catch(DataAccessException e) {
            log.error("礼物月表准备失败：month={}, exception={}，请检查明细模板和建表权限",Periods.month(clock.millis()),e.getClass().getSimpleName());
        }
    }

    private void prepare() {
        ZonedDateTime now=clock.instant().atZone(Periods.ZONE);
        // DDL 独立于礼物入账和协调事务，避免建表隐式提交影响业务事务。
        for(int offset=0;offset<2;offset++) {
            String month=Periods.month(now.plusMonths(offset).toInstant().toEpochMilli());
            jdbc.execute("CREATE TABLE IF NOT EXISTS "+Periods.detailTable(month)+" LIKE gift_detail_template");
        }
    }
}
