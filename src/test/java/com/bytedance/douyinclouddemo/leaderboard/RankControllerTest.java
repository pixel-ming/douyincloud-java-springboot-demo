package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
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
}
