package com.example.pokemongrader.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
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
    val imageSource: String = "",
    val imageMatchId: String = "",
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
            if (imageSource.isNotEmpty()) put("Image Source", imageSource)
            if (imageMatchId.isNotEmpty()) put("Image Match ID", imageMatchId)
            if (copyId.isNotEmpty()) put("Copy ID", copyId)
        }
    }

    companion object {
        fun fromJsonObject(obj: JSONObject, copyIdFallback: String = UUID.randomUUID().toString()): Card {
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
                imageSource = obj.optString("Image Source", ""),
                imageMatchId = obj.optString("Image Match ID", ""),
                copyId = obj.optString("Copy ID", "").ifBlank { copyIdFallback }
            )
        }

        fun parseCollection(jsonStr: String?): List<Card> {
            if (jsonStr == null || jsonStr.trim() == "null" || jsonStr.trim().isEmpty()) return emptyList()
            val list = mutableListOf<Card>()
            try {
                if (jsonStr.trim().startsWith("[")) {
                    val array = JSONArray(jsonStr)
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.takeUnless { it.optBoolean("_deleted") }?.let {
                            list.add(fromJsonObject(it, legacyCopyId(it, i)))
                        }
                    }
                } else if (jsonStr.trim().startsWith("{")) {
                    val obj = JSONObject(jsonStr)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        obj.optJSONObject(key)?.takeUnless { it.optBoolean("_deleted") }?.let {
                            list.add(fromJsonObject(it, key))
                        }
                    }
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

        private fun legacyCopyId(card: JSONObject, index: Int): String {
            val fields = listOf("Page", "Slot", "Dex Number", "Name", "Type", "Condition", "Notes", "Date Added", "Catalog Card ID", "Set ID", "Card Number")
            val identity = fields.joinToString("|") { if (card.isNull(it)) "" else card.optString(it, "") } + "|" + index
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            val hash = digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "legacy-${hash.take(32)}"
        }
    }
}
