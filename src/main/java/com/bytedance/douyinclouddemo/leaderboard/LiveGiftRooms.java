package com.bytedance.douyinclouddemo.leaderboard;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class LiveGiftRooms {
    private final JdbcTemplate jdbc;
    @Value
    public static class Room { String appId,roomId,alias; int page; }
    public void observe(String alias,String appId,String room,long now) {
        jdbc.update("INSERT INTO live_gift_room(source_app_id,room_id,app_alias,replay_until,last_seen_at,task_state) VALUES(?,?,?,?,?,'OBSERVED') ON DUPLICATE KEY UPDATE last_seen_at=VALUES(last_seen_at),replay_until=GREATEST(replay_until,VALUES(replay_until)),app_alias=VALUES(app_alias)",appId,room,alias,now+86400000L,now);
    }
    public void task(String alias,String appId,String room,String state,long now) {
        observe(alias,appId,room,now);
        jdbc.update("UPDATE live_gift_room SET task_state=?,replay_page=1,next_replay_at=0 WHERE source_app_id=? AND room_id=?",state,appId,room);
    }
    public Optional<Room> next() {
        List<Room> rooms=jdbc.query("SELECT source_app_id,room_id,app_alias,replay_page FROM live_gift_room WHERE next_replay_at<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED) AND replay_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED) ORDER BY next_replay_at,source_app_id,room_id LIMIT 1",(rs,n)->new Room(rs.getString("source_app_id"),rs.getString("room_id"),rs.getString("app_alias"),rs.getInt("replay_page")));
        return rooms.isEmpty()?Optional.empty():Optional.of(rooms.get(0));
    }
    public void finish(Room room,int nextPage,long delay) {
        jdbc.update("UPDATE live_gift_room SET replay_page=?,next_replay_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS SIGNED)+? WHERE source_app_id=? AND room_id=? AND replay_page=?",nextPage,delay,room.getAppId(),room.getRoomId(),room.getPage());
    }
}
