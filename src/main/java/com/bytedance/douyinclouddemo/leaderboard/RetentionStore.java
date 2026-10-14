package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class RetentionStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private long receiptAfter,counterAfter,detailAfter,receiptThrough,counterThrough;
    private String detailTable,afterTable="";
    private static final long RETENTION_MILLIS=Duration.ofDays(90).toMillis();

    public int cleanup(long now,int limit) {
        long cutoff=now-RETENTION_MILLIS;
        if(cutoff<=0)return 0;
        return compactReceipts(cutoff,limit)+purgeDetails(cutoff,limit)+purgeCounters(cutoff,limit);
    }
    private int compactReceipts(long cutoff,int limit) {
        if(receiptThrough==0)receiptThrough=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM gift_receipt",Long.class);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,status,completed_at FROM gift_receipt WHERE id>? AND id<=? ORDER BY id LIMIT ?",receiptAfter,receiptThrough,limit);
        if(rows.isEmpty()){receiptAfter=0;receiptThrough=0;return 0;}
        List<Long> ids=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            receiptAfter=number(row,"id");
            if(("BOOKED".equals(row.get("status"))||"FILTERED".equals(row.get("status")))&&row.get("completed_at")!=null&&number(row,"completed_at")<cutoff)ids.add(receiptAfter);
        }
        if(receiptAfter>=receiptThrough){receiptAfter=0;receiptThrough=0;}
        if(ids.isEmpty())return 0;
        // 保留来源唯一键、指纹、终态和返回结果，只精简已办结且超过保留期的个人字段与原文。
        return named.update("UPDATE gift_receipt r LEFT JOIN gift_receipt p ON p.status='PENDING_REVIEW' AND p.game COLLATE utf8mb4_0900_ai_ci=r.game COLLATE utf8mb4_0900_ai_ci SET r.user_id=NULL,r.pending_payload=NULL WHERE r.id IN (:ids) AND r.status IN ('BOOKED','FILTERED') AND r.completed_at<:cutoff AND p.id IS NULL AND (r.user_id IS NOT NULL OR r.pending_payload IS NOT NULL)",parameters(ids,cutoff));
    }
    private int purgeDetails(long cutoff,int limit) {
        if(detailTable==null) {
            List<String> tables=jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' AND TABLE_NAME REGEXP '^gift_detail_[0-9]{4}(0[1-9]|1[0-2])$' AND TABLE_NAME>? AND TABLE_NAME<=? ORDER BY TABLE_NAME LIMIT 1",String.class,afterTable,Periods.detailTable(Periods.month(cutoff)));
            if(tables.isEmpty()){afterTable="";return 0;}
            afterTable=tables.get(0);detailTable=Periods.detailTable(afterTable.substring("gift_detail_".length()));
        }
        String month=detailTable.substring("gift_detail_".length());
        if(!jdbc.queryForList("SELECT id FROM gift_receipt WHERE storage_month=? AND status='PENDING_REVIEW' LIMIT 1",Long.class,month).isEmpty()){nextDetailTable();return 0;}
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT d.receipt_id,d.booked_at,r.status,r.completed_at FROM "+detailTable+" d LEFT JOIN gift_receipt r ON r.id=d.receipt_id WHERE d.receipt_id>? ORDER BY d.receipt_id LIMIT ?",detailAfter,limit);
        if(rows.isEmpty()){nextDetailTable();return 0;}
        List<Long> ids=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            detailAfter=number(row,"receipt_id");
            if(number(row,"booked_at")<cutoff&&"BOOKED".equals(row.get("status"))&&row.get("completed_at")!=null&&number(row,"completed_at")<cutoff)ids.add(detailAfter);
        }
        if(ids.isEmpty())return 0;
        return named.update("DELETE d FROM "+detailTable+" d JOIN gift_receipt r ON r.id=d.receipt_id WHERE d.receipt_id IN (:ids) AND d.booked_at<:cutoff AND r.status='BOOKED' AND r.completed_at<:cutoff AND NOT EXISTS (SELECT 1 FROM gift_receipt p WHERE p.storage_month=:month AND p.status='PENDING_REVIEW')",parameters(ids,cutoff).addValue("month",month));
    }
    private int purgeCounters(long cutoff,int limit) {
        if(counterThrough==0)counterThrough=jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM score_total",Long.class);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT id,rank_type,period_start,updated_at,sync_pending FROM score_total WHERE id>? AND id<=? ORDER BY id LIMIT ?",counterAfter,counterThrough,limit);
        if(rows.isEmpty()){counterAfter=0;counterThrough=0;return 0;}
        List<Long> ids=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            counterAfter=number(row,"id");int type=((Number)row.get("rank_type")).intValue();
            Object pending=row.get("sync_pending");boolean synced=Boolean.FALSE.equals(pending)||pending instanceof Number&&((Number)pending).intValue()==0;
            if((type==0||type==1)&&synced&&number(row,"updated_at")<cutoff&&Periods.end(type,number(row,"period_start"))<cutoff)ids.add(counterAfter);
        }
        if(counterAfter>=counterThrough){counterAfter=0;counterThrough=0;}
        if(ids.isEmpty())return 0;
        return named.update("DELETE FROM score_total WHERE id IN (:ids) AND sync_pending=FALSE AND updated_at<:cutoff AND NOT EXISTS (SELECT 1 FROM gift_receipt p WHERE p.status='PENDING_REVIEW' AND p.game COLLATE utf8mb4_0900_ai_ci=score_total.game) AND NOT EXISTS (SELECT 1 FROM rank_cache_state c WHERE c.status<>'EXPIRED' AND ((score_total.rank_type=0 AND c.day_start=score_total.period_start) OR (score_total.rank_type=1 AND c.cycle_start=score_total.period_start)))",parameters(ids,cutoff));
    }
    private MapSqlParameterSource parameters(List<Long> ids,long cutoff){return new MapSqlParameterSource().addValue("ids",ids).addValue("cutoff",cutoff);}
    private long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private void nextDetailTable(){detailTable=null;detailAfter=0;}
    @lombok.Value
    static class Progress {
        long receiptAfter,counterAfter,detailAfter,receiptThrough,counterThrough;
        String detailTable,afterTable;
    }
    Progress snapshotProgress(){return new Progress(receiptAfter,counterAfter,detailAfter,receiptThrough,counterThrough,detailTable,afterTable);}
    void restoreProgress(Progress previous){
        receiptAfter=previous.receiptAfter;counterAfter=previous.counterAfter;detailAfter=previous.detailAfter;
        receiptThrough=previous.receiptThrough;counterThrough=previous.counterThrough;
        detailTable=previous.detailTable;afterTable=previous.afterTable;
    }
}
