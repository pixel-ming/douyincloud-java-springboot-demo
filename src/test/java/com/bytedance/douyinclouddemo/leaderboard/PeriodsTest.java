package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class PeriodsTest {
    private long at(String local){return LocalDateTime.parse(local).atZone(Periods.ZONE).toInstant().toEpochMilli();}
    @Test void dayAndCycleHaveSeparateBoundaries(){long cutoff=at("2026-10-20T02:00:00");assertEquals(at("2026-10-10T02:00:00"),Periods.cycle(cutoff-1));assertEquals(cutoff,Periods.cycle(cutoff));assertEquals(at("2026-10-20T00:00:00"),Periods.day(cutoff));}
    @Test void februaryDoesNotInventThirtieth(){assertEquals(at("2026-02-20T02:00:00"),Periods.cycle(at("2026-03-01T12:00:00")));assertEquals(at("2026-03-10T02:00:00"),Periods.end(1,at("2026-02-20T02:00:00")));}
    @Test void earlyMonthBelongsToPriorMonth(){assertEquals(at("2026-09-30T02:00:00"),Periods.cycle(at("2026-10-01T00:00:00")));}
    @Test void midnightUsesBeijingRatherThanSystemZone(){assertEquals(at("2026-10-20T00:00:00"),Periods.day(at("2026-10-20T00:00:01")));}
    @Test void monthlyTableCannotComeFromRequestSql(){assertEquals("gift_detail_202610",Periods.detailTable("202610"));assertThrows(IllegalArgumentException.class,()->Periods.detailTable("202610;DROP"));assertThrows(IllegalArgumentException.class,()->Periods.detailTable("202613"));}
}
