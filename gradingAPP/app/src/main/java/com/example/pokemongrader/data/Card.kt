package com.example.pokemongrader.data

import org.json.JSONArray
import org.json.JSONObject

data class Card(
    val page: Int,
    val slot: Int,
    val dexNumber: Int,
    val name: String,
    val type: String,
    val condition: String,
    val notes: String,
    val dateAdded: String,
    val grade: Double = 0.0
) {
    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("Page", page)
            put("Slot", slot)
            put("Dex Number", dexNumber)
            put("Name", name)
            put("Type", type)
            put("Condition", condition)
            put("Notes", notes)
            put("Date Added", dateAdded)
            if (grade > 0.0) put("Grade", grade)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): Card {
            return Card(
                page = obj.optInt("Page", 1),
                slot = obj.optInt("Slot", 1),
                dexNumber = obj.optInt("Dex Number", 0),
                name = obj.optString("Name", ""),
                type = obj.optString("Type", "Normal"),
                condition = obj.optString("Condition", "NM"),
                notes = obj.optString("Notes", ""),
                dateAdded = obj.optString("Date Added", ""),
                grade = obj.optDouble("Grade", 0.0)
            )
        }

        fun parseCollection(jsonStr: String?): List<Card> {
            if (jsonStr == null || jsonStr.trim() == "null" || jsonStr.trim().isEmpty()) {
                return emptyList()
            }
            val list = mutableListOf<Card>()
            try {
                if (jsonStr.trim().startsWith("[")) {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        if (!array.isNull(i)) {
                            val obj = array.optJSONObject(i)
                            if (obj != null) list.add(fromJsonObject(obj))
                        }
                    }
                } else if (jsonStr.trim().startsWith("{")) {
                    val obj = JSONObject(jsonStr)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val cardObj = obj.optJSONObject(keys.next())
                        if (cardObj != null) list.add(fromJsonObject(cardObj))
                    }
                }
            } catch (e: Exception) {}
            return list
        }

        fun serializeCollection(collection: List<Card>): String {
            val array = JSONArray()
            collection.forEach { array.put(it.toJsonObject()) }
            return array.toString()
        }
    }
}

