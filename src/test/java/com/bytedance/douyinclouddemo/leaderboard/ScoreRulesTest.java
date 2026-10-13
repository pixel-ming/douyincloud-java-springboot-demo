package com.bytedance.douyinclouddemo.leaderboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class ScoreRulesTest {
    @ParameterizedTest @CsvSource({"10,3","100,30","520,156","15,5","5,2","0,0"})
    void roundsOnlyFinalScore(String value,long expected){assertEquals(expected,ScoreRules.score(new BigDecimal(value)));}
    @Test void refusesNegativeAndInexactCacheValues(){assertThrows(IllegalArgumentException.class,()->ScoreRules.score(new BigDecimal("-1")));assertThrows(ArithmeticException.class,()->ScoreRules.score(new BigDecimal("99999999999999999999999999")));}
    @Test void fingerprintIsStableUtf8(){assertEquals(ScoreRules.sha256("中文"),ScoreRules.sha256("中文"));assertNotEquals(ScoreRules.sha256("u1"),ScoreRules.sha256("U1"));}
}
