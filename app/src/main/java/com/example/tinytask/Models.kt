package com.example.tinytask

import org.json.JSONArray
import org.json.JSONObject

data class TouchPoint(val t: Long, val x: Float, val y: Float)

data class RecordedStroke(val startOffset: Long, val points: List<TouchPoint>)

object MacroSerializer {

    fun toJson(strokes: List<RecordedStroke>): String {
        val arr = JSONArray()
        for (s in strokes) {
            val strokeObj = JSONObject()
            strokeObj.put("startOffset", s.startOffset)
            val pointsArr = JSONArray()
            for (p in s.points) {
                val pointObj = JSONObject()
                pointObj.put("t", p.t)
                pointObj.put("x", p.x.toDouble())
                pointObj.put("y", p.y.toDouble())
                pointsArr.put(pointObj)
            }
            strokeObj.put("points", pointsArr)
            arr.put(strokeObj)
        }
        return arr.toString()
    }

    fun fromJson(json: String): List<RecordedStroke> {
        val arr = JSONArray(json)
        val result = mutableListOf<RecordedStroke>()
        for (i in 0 until arr.length()) {
            val strokeObj = arr.getJSONObject(i)
            val startOffset = strokeObj.getLong("startOffset")
            val pointsArr = strokeObj.getJSONArray("points")
            val points = mutableListOf<TouchPoint>()
            for (j in 0 until pointsArr.length()) {
                val pointObj = pointsArr.getJSONObject(j)
                points.add(
                    TouchPoint(
                        pointObj.getLong("t"),
                        pointObj.getDouble("x").toFloat(),
                        pointObj.getDouble("y").toFloat()
                    )
                )
            }
            result.add(RecordedStroke(startOffset, points))
        }
        return result
    }
}
