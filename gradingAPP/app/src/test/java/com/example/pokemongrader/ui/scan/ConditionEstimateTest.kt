package com.example.pokemongrader.ui.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ConditionEstimateTest {
    private val valid = """{
        "assessable":true,"grade":7.5,"criteria":{
            "centering":{"score":8,"evidence":"left border narrower"},
            "corners":{"score":7,"evidence":"white spot at top"},
            "edges":{"score":8,"evidence":"minor edge wear"},
            "surface":{"score":7,"evidence":"visible print line"}
        }}"""

    @Test fun validEstimateKeepsEvidence() {
        val result = parseConditionEstimate(JSONObject(valid))
        assertEquals(7.5, result.grade!!, 0.0)
        assert(result.evidence.contains("white spot"))
    }

    @Test fun poorPhotoHasNoGrade() {
        val result = parseConditionEstimate(JSONObject("""{"assessable":false,"grade":null,"reason":"back is blurred"}"""))
        assertNull(result.grade)
        assert(result.evidence.contains("blurred"))
    }

    @Test fun invalidScoresAndIncompleteJsonFail() {
        assertThrows(IllegalArgumentException::class.java) {
            parseConditionEstimate(JSONObject(valid.replace("\"grade\":7.5", "\"grade\":11")))
        }
        assertThrows(Exception::class.java) { parseConditionEstimate(JSONObject("""{"assessable":true,"grade":7}""")) }
    }
}
