package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GiftService {
    private final LedgerStore store;
    private final PlatformTransactionManager transactions;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final GiftClassifier classifier;
    private final MigrationStore migrations;

    @Value
    public static class Result { long receiptId; String status; boolean duplicate; Long scoreDelta; }
    private static class ExistingSource extends RuntimeException { }

    public Result receive(GiftData gift) {
        Result result=null;
        for(int attempt=0;attempt<3;attempt++) {
            try {
                result=new TransactionTemplate(transactions).execute(status->{
                    migrations.guard(gift);
                    long id;
                    try { id=store.insertReceipt(gift); }
                    catch(DuplicateKeyException e) {
                        if(e.getMostSpecificCause().getMessage()!=null && e.getMostSpecificCause().getMessage().contains("uk_source")) throw new ExistingSource();
                        throw e;
                    }
                    if("BOOKED".equals(gift.getStatus())) store.book(id,gift,clock.millis());
                    if("BOOKED".equals(gift.getStatus()))store.updateProfile(gift);
                    return new Result(id,gift.getStatus(),false,gift.getScoreDelta());
                });
                break;
            } catch(ExistingSource e) {
                Optional<LedgerStore.Receipt> original=store.receipt(gift);
                if(!original.isPresent()) throw new RankException(503,50302,"重复消息仍在处理中");
                LedgerStore.Receipt receipt=original.get();
                if(!receipt.getFingerprint().equals(gift.getFingerprint())) {
                    log.warn("礼物重复校验失败：appId={}, roomId={}, msgId={}",gift.getAppId(),gift.getRoomId(),gift.getMsgId());
                    throw new RankException(409,40901,"同一消息的计分内容冲突");
                }
                result=new Result(receipt.getId(),receipt.getStatus(),true,receipt.getDelta());
                break;
            } catch(TransientDataAccessException e) {
                if(attempt==2) throw e;
                try { Thread.sleep(20L*(attempt+1)); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw RankException.unavailable(); }
            }
        }
        if(result==null) throw new IllegalStateException("入账未返回结果");
        if(result.isDuplicate() && "BOOKED".equals(result.getStatus())) {
            try { store.updateProfile(gift); }
            catch(DataAccessException e) { log.warn("礼物资料更新失败：receiptId={}, exception={}",result.getReceiptId(),e.getClass().getSimpleName()); }
        }
        return result;
    }

    // 仅供受控后台调用；不暴露可由游戏客户端调用的补分接口。
    public Result approvePending(long id,String operator,String reason,Long correctedEventAt) {
        ScoreRules.identifier(operator,128,false); ScoreRules.identifier(reason,512,false);
        return new TransactionTemplate(transactions).execute(status->{
            LedgerStore.Receipt receipt=store.lockReceipt(id).orElseThrow(()->RankException.badRequest("待核对消息不存在"));
            if(!"PENDING_REVIEW".equals(receipt.getStatus())) return new Result(id,receipt.getStatus(),true,receipt.getDelta());
            try {
                JsonNode payload=mapper.readTree(receipt.getPendingPayload());
                LeaderboardProperties.App app=new LeaderboardProperties.App();
                app.setAppId(receipt.getAppId());app.setGame(payload.path("game").asText());app.setPlatform(payload.path("platform").asText());
                app.setFaction(payload.path("faction").asText());app.setZhubo(payload.path("zhubo").asText());
                app.setTimestampUnit(LeaderboardProperties.TimestampUnit.valueOf(payload.path("timestampUnit").asText()));
                JsonNode message=payload.get("message");
                GiftData gift=classifier.classify(app,receipt.getRoomId(),message,receipt.getFirstReceivedAt());
                if(correctedEventAt!=null) gift.setEventAt(correctedEventAt);
                // 超窗可以人工核对；缺失身份、负价值和特殊礼物不靠批准按钮猜测补齐。
                if(gift.getUserId()==null || gift.getGiftId()==null || gift.getGiftNum()==null || gift.getGiftValue()==null || gift.getScoreDelta()==null || gift.getScoreDelta()<=0
                        || gift.getEventAt()==null || gift.getEventAt()<1451606400000L || gift.getEventAt()>clock.millis()+30000L
                        || (message.has("test")&&(!message.get("test").isBoolean()||message.get("test").booleanValue())) || GiftClassifier.hasSpecialGift(message)) throw RankException.badRequest("缺少可安全补记的礼物依据");
                gift.setStorageMonth(receipt.getMonth()); gift.setStatus("BOOKED");
                migrations.guard(gift);
                if(!"BOOKED".equals(gift.getStatus()))throw RankException.badRequest("切换前消息须核对旧基线，不允许直接补记");
                if(!store.completeReview(id,gift,operator,reason,clock.millis())) throw new RankException(409,40903,"消息状态已变化");
                store.book(id,gift,clock.millis());
                store.updateProfile(gift);
                return new Result(id,"BOOKED",false,gift.getScoreDelta());
            } catch(java.io.IOException | IllegalArgumentException e) { throw RankException.badRequest("待核对内容无法恢复"); }
        });
    }
}
