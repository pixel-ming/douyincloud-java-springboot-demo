package com.bytedance.douyinclouddemo.leaderboard;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Data;

@Data
public class RankEntry {
    @JsonProperty("UserId") String userId;
    @JsonProperty("Game") String game;
    @JsonProperty("Platform") String platform;
    @JsonProperty("Faction") String faction;
    @JsonProperty("Zhubo") String zhubo;
    @JsonProperty("Nickname") @JsonSerialize(using=Utf8NicknameSerializer.class) String nickname;
    @JsonProperty("Avatar") String avatar;
    @JsonProperty("KillSum") Long killSum;
    @JsonProperty("Points1Sum") Double points1Sum;
    @JsonProperty("Points2Sum") Double points2Sum;
    @JsonProperty("Points3Sum") Double points3Sum;
    @JsonProperty("DailyKillSum") Long dailyKillSum;
    @JsonProperty("DailyPoints1Sum") Double dailyPoints1Sum;
    @JsonProperty("DailyPoints2Sum") Double dailyPoints2Sum;
    @JsonProperty("DailyPoints3Sum") Double dailyPoints3Sum;
    @JsonProperty("Value1") Double value1;
    @JsonProperty("Value2") Double value2;
    @JsonProperty("Value3") Double value3;
    @JsonProperty("CreatedAt") String createdAt;
    @JsonProperty("UpdatedAt") String updatedAt;
    @JsonProperty("DeletedAt") String deletedAt;

    public double score(int metric){switch(metric){case 0:return dailyKillSum;case 1:return killSum;case 2:return points1Sum;case 3:return points2Sum;case 4:return points3Sum;case 5:return dailyPoints1Sum;case 6:return dailyPoints2Sum;case 7:return dailyPoints3Sum;default:throw new IllegalArgumentException("未知排行指标");}}
    public void score(int metric,double score){switch(metric){case 0:dailyKillSum=(long)score;break;case 1:killSum=(long)score;break;case 2:points1Sum=score;break;case 3:points2Sum=score;break;case 4:points3Sum=score;break;case 5:dailyPoints1Sum=score;break;case 6:dailyPoints2Sum=score;break;case 7:dailyPoints3Sum=score;break;default:throw new IllegalArgumentException("未知排行指标");}}
}
