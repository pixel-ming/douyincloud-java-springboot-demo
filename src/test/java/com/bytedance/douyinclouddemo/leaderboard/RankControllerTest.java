package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RankControllerTest {
    private final RankQueryService queries=mock(RankQueryService.class);
    private MockMvc mvc;
    @BeforeEach void setup(){when(queries.query(any())).thenAnswer(call->{RankRequest request=call.getArgument(0);request.rankType();request.scope();return Collections.emptyList();});mvc=MockMvcBuilders.standaloneSetup(new RankController(queries,Fixtures.mapper()))
            .setControllerAdvice(new LegacyRankErrorHandler(),new RankErrorHandler()).build();}
    @Test void successAndEmptyBoardKeepLegacyEnvelope() throws Exception {
        mvc.perform(post("/api/v1/game/get_kill_rank2").contentType("application/json").content("{\"limit\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errCode").value(0))
                .andExpect(jsonPath("$.errMsg").value("success")).andExpect(jsonPath("$.data").isArray());
    }
    @Test void emptyMalformedAndInvalidParametersHaveDistinctErrors() throws Exception {
        mvc.perform(post("/api/v1/game/get_kill_rank2").contentType("application/json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errCode").value(10011));
        mvc.perform(post("/api/v1/game/get_kill_rank2").contentType("application/json").content("{\"limit\":\"10\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errCode").value(10001));
        mvc.perform(post("/api/v1/game/get_kill_rank2").contentType("application/json").content("{\"limit\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errCode").value(10002))
                .andExpect(jsonPath("$.data.Limit").exists());
        verify(queries,times(1)).query(any());
    }
    @Test void unavailableRankIsLegacyQueryError() throws Exception {
        doThrow(RankException.unavailable()).when(queries).query(any());
        mvc.perform(post("/api/v1/game/get_kill_rank2").contentType("application/json").content("{\"limit\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errCode").value(1000));
    }
    @Test void nicknamesKeepUtf8SymbolsAndJsonEscaping() throws Exception {
        String[] names={"\uD83C\uDDE8***", "\u1A36\u1092\u1A5A\uD83D\uDC9E\u20DF\u55B5\"\\\n\t\\u083"};
        RankEntry first=new RankEntry();first.setNickname(names[0]);first.setKillSum(999L);
        RankEntry second=new RankEntry();second.setNickname(names[1]);second.setKillSum(888L);
        doReturn(Arrays.asList(first,second)).when(queries).query(any());
        byte[] body=mvc.perform(post("/api/v1/game/get_kill_rank2").contentType("application/json").content("{\"limit\":10}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errCode").value(0))
                .andExpect(jsonPath("$.data[0].KillSum").value(999))
                .andReturn().getResponse().getContentAsByteArray();
        String json=new String(body,StandardCharsets.UTF_8);
        assertTrue(json.contains("\uD83C\uDDE8"));
        assertTrue(json.contains("\uD83D\uDC9E"));
        assertFalse(json.contains("\\uD83C"));
        assertFalse(json.contains("\\uD83D"));
        com.fasterxml.jackson.databind.JsonNode rows=Fixtures.mapper().readTree(body).get("data");
        for(int i=0;i<names.length;i++)assertEquals(names[i],rows.get(i).get("Nickname").textValue());
        String snapshotJson=Fixtures.mapper().writeValueAsString(second);
        assertEquals(names[1],Fixtures.mapper().readTree(snapshotJson).get("Nickname").textValue());
        assertTrue(snapshotJson.contains("\"Nickname\":"+Fixtures.mapper().writeValueAsString(names[1])));
    }
}
