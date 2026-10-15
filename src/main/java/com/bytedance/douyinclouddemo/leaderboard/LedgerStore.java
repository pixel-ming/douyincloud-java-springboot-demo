package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class LedgerStore {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    @Data
    public static class Receipt {
        private long id, firstReceivedAt;
        private String appId,roomId,msgId,game,month,status,fingerprint,pendingPayload;
        private Long delta;
    }
    @Data
    public static class Counter {
        private long id,revision,periodStart;
        private int rankType;
        private String game,platform,faction,zhubo,userId;
    }
    public long insertReceipt(GiftData gift) {
        GeneratedKeyHolder holder=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps=connection.prepareStatement("INSERT INTO gift_receipt(source_app_id,room_id,msg_type,msg_id,game,user_id,content_fingerprint,event_at,first_received_at,storage_month,score_delta,rule_version,status,reason_code,completed_at,pending_payload) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            Object[] values={gift.getAppId(),gift.getRoomId(),gift.getMsgType(),gift.getMsgId(),gift.getGame(),gift.getUserId(),gift.getFingerprint(),gift.getEventAt(),gift.getFirstReceivedAt(),gift.getStorageMonth(),gift.getScoreDelta(),gift.getRuleVersion(),gift.getStatus(),gift.getReason(),"PENDING_REVIEW".equals(gift.getStatus())?null:gift.getFirstReceivedAt(),gift.getPendingPayload()};
            for (int i=0;i<values.length;i++) ps.setObject(i+1,values[i]);
            return ps;
        },holder);
        if (holder.getKey()==null) throw new IllegalStateException("来源记录未返回编号");
        return holder.getKey().longValue();
    }
    public Optional<Receipt> receipt(GiftData gift) {
        return receiptQuery("SELECT * FROM gift_receipt WHERE source_app_id=? AND room_id=? AND msg_type=? AND msg_id=?",gift.getAppId(),gift.getRoomId(),gift.getMsgType(),gift.getMsgId());
    }
    public Optional<Receipt> lockReceipt(long id) { return receiptQuery("SELECT * FROM gift_receipt WHERE id=? FOR UPDATE",id); }
    private Optional<Receipt> receiptQuery(String sql,Object... args) {
        List<Receipt> result=jdbc.query(sql,(rs,n) -> {
            Receipt r=new Receipt(); r.setId(rs.getLong("id")); r.setFirstReceivedAt(rs.getLong("first_received_at"));
            r.setAppId(rs.getString("source_app_id")); r.setRoomId(rs.getString("room_id")); r.setMsgId(rs.getString("msg_id"));
            r.setGame(rs.getString("game")); r.setMonth(rs.getString("storage_month")); r.setStatus(rs.getString("status"));
            r.setFingerprint(rs.getString("content_fingerprint")); r.setPendingPayload(rs.getString("pending_payload"));
            r.setDelta((Long)rs.getObject("score_delta")); return r;
        },args);
        return result.isEmpty()?Optional.empty():Optional.of(result.get(0));
    }
    public void book(long id,GiftData gift,long bookedAt) {
        jdbc.update("INSERT INTO "+Periods.detailTable(gift.getStorageMonth())+"(receipt_id,game,platform,faction,zhubo,user_id,gift_id,gift_num,gift_value,score_delta,rule_version,event_at,first_received_at,booked_at,day_start,cycle_start) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,gift.getGame(),gift.getPlatform(),gift.getFaction(),gift.getZhubo(),gift.getUserId(),gift.getGiftId(),gift.getGiftNum(),gift.getGiftValue(),gift.getScoreDelta(),gift.getRuleVersion(),gift.getEventAt(),gift.getFirstReceivedAt(),bookedAt,Periods.day(gift.getEventAt()),Periods.cycle(gift.getEventAt()));
        addCounter(gift,0,Periods.day(gift.getEventAt()),bookedAt);
        addCounter(gift,1,Periods.cycle(gift.getEventAt()),bookedAt);
        jdbc.update("INSERT INTO user_profile(game,platform,faction,zhubo,user_id,profile_at,created_at,updated_at) VALUES(?,?,?,?,?,0,?,?) ON DUPLICATE KEY UPDATE updated_at=GREATEST(updated_at,VALUES(updated_at))",
                gift.getGame(),gift.getPlatform(),gift.getFaction(),gift.getZhubo(),gift.getUserId(),bookedAt,bookedAt);
    }
    private void addCounter(GiftData gift,int type,long period,long now) {
        jdbc.update("INSERT INTO score_total(game,platform,faction,zhubo,user_id,rank_type,period_start,score_sum,revision,sync_pending,updated_at) VALUES(?,?,?,?,?,?,?,0,0,FALSE,?) ON DUPLICATE KEY UPDATE id=id",
                gift.getGame(),gift.getPlatform(),gift.getFaction(),gift.getZhubo(),gift.getUserId(),type,period,now);
        int updated=jdbc.update("UPDATE score_total SET score_sum=score_sum+?,revision=revision+1,sync_pending=TRUE,updated_at=? WHERE game=? AND platform=? AND faction=? AND zhubo=? AND user_id=? AND rank_type=? AND period_start=? AND score_sum<=?",
                gift.getScoreDelta(),now,gift.getGame(),gift.getPlatform(),gift.getFaction(),gift.getZhubo(),gift.getUserId(),type,period,ScoreRules.MAX_SCORE-gift.getScoreDelta());
        if (updated!=1) throw new RankException(409,40902,"累计积分超出精确范围");
    }
    public boolean completeReview(long id,GiftData gift,String operator,String reason,long now) {
        return jdbc.update("UPDATE gift_receipt SET status='BOOKED',reason_code=NULL,user_id=?,event_at=?,score_delta=?,rule_version=?,completed_at=?,reviewed_by=?,review_reason=?,reviewed_at=? WHERE id=? AND status='PENDING_REVIEW'",
                gift.getUserId(),gift.getEventAt(),gift.getScoreDelta(),gift.getRuleVersion(),now,operator,reason,now,id)==1;
    }
    public List<Counter> pending(long after,int limit) {
        return jdbc.query("SELECT id,game,platform,faction,zhubo,user_id,rank_type,period_start,revision FROM score_total WHERE sync_pending=TRUE AND id>? ORDER BY id LIMIT ?",(rs,n)->{
            Counter c=new Counter();c.setId(rs.getLong("id"));c.setGame(rs.getString("game"));c.setPlatform(rs.getString("platform"));c.setFaction(rs.getString("faction"));
            c.setZhubo(rs.getString("zhubo"));c.setUserId(rs.getString("user_id"));c.setRankType(rs.getInt("rank_type"));c.setPeriodStart(rs.getLong("period_start"));c.setRevision(rs.getLong("revision"));return c;
        },after,limit);
    }
    public void acknowledge(List<Counter> counters) {
        jdbc.batchUpdate("UPDATE score_total SET sync_pending=FALSE WHERE id=? AND revision=? AND sync_pending=TRUE",counters,counters.size(),(ps,c)->{ps.setLong(1,c.getId());ps.setLong(2,c.getRevision());});
    }
    public void updateProfile(GiftData gift) {
        if(gift.getEventAt()==null || (gift.getNickname()==null&&gift.getAvatar()==null))return;
        jdbc.update("UPDATE user_profile SET nickname=IF(? >= profile_at,COALESCE(?,nickname),nickname),avatar=IF(? >= profile_at,COALESCE(?,avatar),avatar),profile_at=GREATEST(profile_at,?) WHERE game=? AND platform=? AND faction=? AND zhubo=? AND user_id=?",
                gift.getEventAt(),gift.getNickname(),gift.getEventAt(),gift.getAvatar(),gift.getEventAt(),gift.getGame(),gift.getPlatform(),gift.getFaction(),gift.getZhubo(),gift.getUserId());
    }
}
