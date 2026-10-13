package com.bytedance.douyinclouddemo.leaderboard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

@Data
@ConfigurationProperties("leaderboard")
public class LeaderboardProperties {
    private String environment = "local";
    private boolean workerEnabled;
    private int batchSize = 200;
    private int maxBoards = 128;
    private int maxMembers = 500000;
    private int snapshotTimeoutSeconds = 120;
    private boolean cloudOpenapiEnabled;
    @lombok.ToString.Exclude
    private String internalToken = "";
    private Map<String, App> apps = new LinkedHashMap<>();
    private List<WarmupScope> warmupScopes = new ArrayList<>();

    @Data
    public static class WarmupScope {
        private String game,platform,faction,zhubo;
        public RankScope toRankScope(){return new RankScope(game,platform,faction,zhubo);}
    }

    @Data
    public static class App {
        private boolean enabled;
        private String appId = "";
        private String game = "";
        private String platform = "";
        private String faction = "";
        private String zhubo = "";
        private Mode mode = Mode.SIGNED;
        @lombok.ToString.Exclude
        private String secret = "";
        private String source = "";
        private boolean privateIngressConfirmed;
        private TimestampUnit timestampUnit = TimestampUnit.MILLIS;
        private Set<String> giftIds = new HashSet<>();
    }
    public enum Mode { SIGNED, CLOUD }
    public enum TimestampUnit { MILLIS, SECONDS }
}
