package com.example.pokemongrader.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Card(
    val page: Int,
    val slot: Int,
    val dexNumber: Int,
    val name: String,
    val type: String,
    val condition: String,
    val notes: String,
    val dateAdded: String,
    val grade: Double = 0.0,
    val imageUrl: String = "",
    val catalogCardId: String = "",
    val setId: String = "",
    val setName: String = "",
    val cardNumber: String = "",
    val catalogRarity: String = "",
    val gradeDetails: String = "",
    val copyId: String = UUID.randomUUID().toString()
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
            if (grade.isFinite() && grade in 1.0..10.0) put("Grade", grade)
            if (imageUrl.isNotEmpty()) put("Image URL", imageUrl)
            if (catalogCardId.isNotEmpty()) put("Catalog Card ID", catalogCardId)
            if (setId.isNotEmpty()) put("Set ID", setId)
            if (setName.isNotEmpty()) put("Set Name", setName)
            if (cardNumber.isNotEmpty()) put("Card Number", cardNumber)
            if (catalogRarity.isNotEmpty()) put("Catalog Rarity", catalogRarity)
            if (gradeDetails.isNotEmpty()) put("Grade Details", gradeDetails)
            if (copyId.isNotEmpty()) put("Copy ID", copyId)
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
                grade = obj.optDouble("Grade", 0.0),
                imageUrl = obj.optString("Image URL", ""),
                catalogCardId = obj.optString("Catalog Card ID", ""),
                setId = obj.optString("Set ID", ""),
                setName = obj.optString("Set Name", ""),
                cardNumber = obj.optString("Card Number", ""),
                catalogRarity = obj.optString("Catalog Rarity", ""),
                gradeDetails = obj.optString("Grade Details", ""),
                copyId = obj.optString("Copy ID", "").ifBlank { UUID.randomUUID().toString() }
            )
        }

        fun parseCollection(jsonStr: String?): List<Card> {
            if (jsonStr == null || jsonStr.trim() == "null" || jsonStr.trim().isEmpty()) return emptyList()
            val list = mutableListOf<Card>()
            try {
                if (jsonStr.trim().startsWith("[")) {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        if (!array.isNull(i)) array.optJSONObject(i)?.let { list.add(fromJsonObject(it)) }
                    }
                } else if (jsonStr.trim().startsWith("{")) {
                    val obj = JSONObject(jsonStr)
                    val keys = obj.keys()
                    while (keys.hasNext()) obj.optJSONObject(keys.next())?.let { list.add(fromJsonObject(it)) }
                }
            } catch (_: Exception) {
            }
            return list
        }

        fun serializeCollection(collection: List<Card>): String {
            val array = JSONArray()
            collection.forEach { array.put(it.toJsonObject()) }
            return array.toString()
        }
    }
}
