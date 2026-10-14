package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DetailTableManagerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private DetailTableManager manager(String instant) {
        return new DetailTableManager(jdbc,Clock.fixed(Instant.parse(instant),ZoneOffset.UTC));
    }

    @ParameterizedTest
    @CsvSource({
            "2026-10-31T15:59:59Z,202610,202611",
            "2026-10-31T16:00:00Z,202611,202612",
            "2026-12-31T16:00:00Z,202701,202702",
            "2028-01-31T15:59:59Z,202801,202802"
    })
    void startupPreparesOnlyTheBeijingCurrentAndNextMonth(String instant,String current,String next) {
        manager(instant).run(null);
        verify(jdbc).execute("CREATE TABLE IF NOT EXISTS gift_detail_"+current+" LIKE gift_detail_template");
        verify(jdbc).execute("CREATE TABLE IF NOT EXISTS gift_detail_"+next+" LIKE gift_detail_template");
        verifyNoMoreInteractions(jdbc);
    }

    @Test void startupFailureIsNotReportedAsReady() {
        doThrow(new DataAccessResourceFailureException("template missing")).when(jdbc).execute(anyString());
        assertThrows(DataAccessResourceFailureException.class,()->manager("2026-10-14T00:00:00Z").run(null));
        verify(jdbc,times(1)).execute(anyString());
    }

    @Test void scheduledFailureCanRecoverOnTheNextAttempt() {
        doThrow(new DataAccessResourceFailureException("permission denied")).doNothing().when(jdbc).execute("CREATE TABLE IF NOT EXISTS gift_detail_202610 LIKE gift_detail_template");
        DetailTableManager tables=manager("2026-10-14T00:00:00Z");
        assertDoesNotThrow(tables::tick);
        assertDoesNotThrow(tables::tick);
        verify(jdbc,times(2)).execute("CREATE TABLE IF NOT EXISTS gift_detail_202610 LIKE gift_detail_template");
        verify(jdbc).execute("CREATE TABLE IF NOT EXISTS gift_detail_202611 LIKE gift_detail_template");
        verifyNoMoreInteractions(jdbc);
    }
}
