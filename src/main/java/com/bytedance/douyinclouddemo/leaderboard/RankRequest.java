package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=RankRequestDeserializer.class)
public class RankRequest {
    private Integer limit;
    private String game,platform,faction,zhubo;
    @JsonProperty("order_by")
    private String orderBy;
    public int rankType() {
        if(limit==null||limit<=0||limit>1000)throw new RankValidationException("Limit","Limit必须在1到1000之间");
        return RankMetric.requested(orderBy).code;
    }
    public RankScope scope() {
        String[] fields={"Game","Platform","Faction","Zhubo"},values={game,platform,faction,zhubo};
        for(int i=0;i<values.length;i++)if(values[i]!=null)try{ScoreRules.identifier(values[i],128,true);}catch(RankException e){throw new RankValidationException(fields[i],fields[i]+"超长或含控制字符");}
        return new RankScope(game,platform,faction,zhubo);
    }
}
