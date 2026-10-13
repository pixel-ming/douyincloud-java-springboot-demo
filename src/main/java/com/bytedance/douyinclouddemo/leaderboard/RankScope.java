package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;

@Getter
@EqualsAndHashCode
public class RankScope {
    private final String game, platform, faction, zhubo;
    @JsonCreator
    public RankScope(@JsonProperty("game") String game, @JsonProperty("platform") String platform,
                     @JsonProperty("faction") String faction, @JsonProperty("zhubo") String zhubo) {
        this.game = normalize(game, 128);
        this.platform = normalize(platform, 128);
        this.faction = normalize(faction, 128);
        this.zhubo = normalize(zhubo, 128);
    }
    private static String normalize(String value, int max) {
        return value == null || value.isEmpty() ? null : ScoreRules.identifier(value, max, false);
    }
    public String id() {
        try { return ScoreRules.sha256(new ObjectMapper().writeValueAsString(Arrays.asList(game, platform, faction, zhubo))); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
