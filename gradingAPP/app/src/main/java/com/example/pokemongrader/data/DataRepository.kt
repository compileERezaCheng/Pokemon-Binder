package com.example.pokemongrader.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

interface DataRepository {
    val isLoggedIn: StateFlow<Boolean>
    val cards: StateFlow<List<Card>>
    val isLoading: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>
    val personalGemini: PersonalGeminiSession? get() = null
    
    // User profile sync states
    val username: StateFlow<String>
    val profilePicSource: StateFlow<String>
    val profileFeaturedDex: StateFlow<Int>
    val profileImageUrl: StateFlow<String>
    val profileImageBase64: StateFlow<String>

    // Navigation pre-fills
    var prefilledPage: Int?
    var prefilledSlot: Int?
    
    suspend fun login(email: String, password: String): Boolean
    suspend fun register(email: String, password: String): Boolean
    suspend fun addCard(card: Card): Boolean
    suspend fun updateCard(card: Card): Boolean
    suspend fun removeCard(card: Card): Boolean
    suspend fun fetchRemote(): Boolean
    suspend fun refreshAndFetch(): Boolean
    suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String): Boolean
    fun logout()
}

class DefaultDataRepository(private val context: Context) : DataRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences("PokeGraderPrefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main)
    private var sessionRevision = 0L
    override val personalGemini = PersonalGeminiSession(AndroidGeminiKeyStore(context))

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    override val cards: StateFlow<List<Card>> = _cards.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Profile Settings
    private val _username = MutableStateFlow("Trainer")
    override val username: StateFlow<String> = _username.asStateFlow()

    private val _profilePicSource = MutableStateFlow("pokemon")
    override val profilePicSource: StateFlow<String> = _profilePicSource.asStateFlow()

    private val _profileFeaturedDex = MutableStateFlow(25)
    override val profileFeaturedDex: StateFlow<Int> = _profileFeaturedDex.asStateFlow()

    private val _profileImageUrl = MutableStateFlow("")
    override val profileImageUrl: StateFlow<String> = _profileImageUrl.asStateFlow()

    private val _profileImageBase64 = MutableStateFlow("")
    override val profileImageBase64: StateFlow<String> = _profileImageBase64.asStateFlow()

    // Navigation pre-fills
    private var _prefilledPage: Int? = null
    override var prefilledPage: Int?
        get() = _prefilledPage
        set(value) { _prefilledPage = value }

    private var _prefilledSlot: Int? = null
    override var prefilledSlot: Int?
        get() = _prefilledSlot
        set(value) { _prefilledSlot = value }

    init {
        val uid = prefs.getString("uid", "") ?: ""
        if (uid.isNotEmpty()) loadCachedProfile(uid)
        val refreshToken = prefs.getString("refresh_token", "")
        if (!refreshToken.isNullOrEmpty()) {
            restoreGeminiKey(uid)
            _isLoggedIn.value = true
            // Silently refresh the idToken then fetch — fixes the "must logout/login" bug
            scope.launch { refreshAndFetch() }
        }
    }

    private fun isCurrentSession(revision: Long, uid: String): Boolean =
        revision == sessionRevision && prefs.getString("uid", "") == uid

    private fun profileKey(uid: String, field: String) = "profile:$uid:$field"

    private fun clearVisibleAccount() {
        personalGemini.selectAccount(null)
        _isLoggedIn.value = false
        _cards.value = emptyList()
        _isLoading.value = false
        _username.value = "Trainer"
        _profilePicSource.value = "pokemon"
        _profileFeaturedDex.value = 25
        _profileImageUrl.value = ""
        _profileImageBase64.value = ""
        _errorMessage.value = null
    }

    private fun loadCachedProfile(uid: String) {
        _username.value = prefs.getString(profileKey(uid, "username"), "Trainer") ?: "Trainer"
        _profilePicSource.value = prefs.getString(profileKey(uid, "profile_pic_source"), "pokemon") ?: "pokemon"
        _profileFeaturedDex.value = prefs.getInt(profileKey(uid, "profile_featured_dex"), 25)
        _profileImageUrl.value = prefs.getString(profileKey(uid, "profile_image_url"), "") ?: ""
        _profileImageBase64.value = prefs.getString(profileKey(uid, "profile_image_base64"), "") ?: ""
    }

    private fun restoreGeminiKey(uid: String) {
        personalGemini.selectAccount(uid)
        scope.launch { personalGemini.restore() }
    }

    private fun cacheProfile(uid: String, username: String, source: String, dex: Int, url: String, base64: String) {
        prefs.edit()
            .putString(profileKey(uid, "username"), username)
            .putString(profileKey(uid, "profile_pic_source"), source)
            .putInt(profileKey(uid, "profile_featured_dex"), dex)
            .putString(profileKey(uid, "profile_image_url"), url)
            .putString(profileKey(uid, "profile_image_base64"), base64)
            .apply()
    }

    private fun beginSignIn(): Long {
        val revision = ++sessionRevision
        prefs.edit().remove("uid").remove("token").remove("refresh_token").apply()
        clearVisibleAccount()
        _isLoading.value = true
        return revision
    }

    private fun pendingAddKey(uid: String, card: Card) = "pending_add:$uid:${card.page}:${card.slot}"

    private fun preserveConflict(uid: String, conflict: CardSyncConflictException) {
        val record = JSONObject()
            .put("reason", conflict.message)
            .put("base", conflict.base?.toJsonObject() ?: JSONObject.NULL)
            .put("local", conflict.local?.toJsonObject() ?: JSONObject.NULL)
            .put("remote", conflict.remote?.toJsonObject() ?: JSONObject.NULL)
        prefs.edit().putString("sync_conflict:$uid:${conflict.copyId}", record.toString()).apply()
        _errorMessage.value = conflict.message
    }

    /**
     * Silently exchanges the stored refreshToken for a fresh idToken, then fetches
     * the remote collection. Falls back to stored idToken if refresh fails.
     */
    override suspend fun refreshAndFetch(): Boolean {
        val revision = sessionRevision
        val uid = prefs.getString("uid", "") ?: ""
        _isLoading.value = true
        return try {
            val storedRefreshToken = prefs.getString("refresh_token", "") ?: ""
            var token = prefs.getString("token", "") ?: ""
            if (storedRefreshToken.isNotEmpty()) {
                val newIdToken = FirebaseClient.refreshIdToken(storedRefreshToken)
                if (newIdToken != null) {
                    if (!isCurrentSession(revision, uid)) return false
                    prefs.edit().putString("token", newIdToken).apply()
                    token = newIdToken
                }
            }
            if (!isCurrentSession(revision, uid)) return false
            fetchRemoteFor(revision, uid, token)
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override suspend fun login(email: String, password: String): Boolean {
        val revision = beginSignIn()
        return try {
            val result = FirebaseClient.signIn(email, password)
            if (revision != sessionRevision) return false
            prefs.edit()
                .putString("token", result.idToken)
                .putString("uid", result.uid)
                .putString("refresh_token", result.refreshToken)
                .apply()
            restoreGeminiKey(result.uid)
            _isLoggedIn.value = true
            loadCachedProfile(result.uid)
            fetchRemoteFor(revision, result.uid, result.idToken)
            isCurrentSession(revision, result.uid)
        } catch (e: Exception) {
            if (revision == sessionRevision) _errorMessage.value = e.message ?: "Could not refresh the session."
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override suspend fun register(email: String, password: String): Boolean {
        val revision = beginSignIn()
        return try {
            val result = FirebaseClient.signUp(email, password)
            if (revision != sessionRevision) return false
            prefs.edit()
                .putString("token", result.idToken)
                .putString("uid", result.uid)
                .putString("refresh_token", result.refreshToken)
                .apply()
            restoreGeminiKey(result.uid)
            _isLoggedIn.value = true
            loadCachedProfile(result.uid)
            fetchRemoteFor(revision, result.uid, result.idToken)
            isCurrentSession(revision, result.uid)
        } catch (e: Exception) {
            if (revision == sessionRevision) _errorMessage.value = e.message
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override suspend fun addCard(card: Card): Boolean {
        val revision = sessionRevision
        val uid = prefs.getString("uid", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (uid.isBlank() || token.isBlank()) return false
        _isLoading.value = true
        return try {
            val key = pendingAddKey(uid, card)
            val copyId = prefs.getString(key, null) ?: card.copyId.also { prefs.edit().putString(key, it).apply() }
            val requested = card.copy(copyId = copyId)
            val saved = FirebaseClient.writeCopy(uid, token, base = null, local = requested) ?: return false
            if (!isCurrentSession(revision, uid)) return false
            val current = _cards.value.toMutableList()
            current.removeAll { it.copyId == saved.copyId }
            current.add(saved)
            _cards.value = current
            prefs.edit().remove(key).apply()
            true
        } catch (e: Exception) {
            if (revision == sessionRevision) {
                if (e is CardSyncConflictException) preserveConflict(uid, e) else _errorMessage.value = e.message
            }
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override suspend fun updateCard(card: Card): Boolean {
        val revision = sessionRevision
        val uid = prefs.getString("uid", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (uid.isBlank() || token.isBlank()) return false
        _isLoading.value = true
        return try {
            val base = _cards.value.firstOrNull { it.copyId == card.copyId } ?: return false
            val saved = FirebaseClient.writeCopy(uid, token, base = base, local = card) ?: return false
            if (!isCurrentSession(revision, uid)) return false
            _cards.value = _cards.value.map { if (it.copyId == saved.copyId) saved else it }
            true
        } catch (e: Exception) {
            if (revision == sessionRevision) {
                if (e is CardSyncConflictException) preserveConflict(uid, e) else _errorMessage.value = e.message
            }
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override suspend fun removeCard(card: Card): Boolean {
        val revision = sessionRevision
        val uid = prefs.getString("uid", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (uid.isBlank() || token.isBlank()) return false
        _isLoading.value = true
        return try {
            val base = _cards.value.firstOrNull { it.copyId == card.copyId }
                ?: _cards.value.firstOrNull { card.copyId.isBlank() && it.page == card.page && it.slot == card.slot && it.name.equals(card.name, ignoreCase = true) }
                ?: return false
            FirebaseClient.writeCopy(uid, token, base = base, local = null, delete = true)
            if (!isCurrentSession(revision, uid)) return false
            _cards.value = _cards.value.filterNot { it.copyId == base.copyId }
            true
        } catch (e: Exception) {
            if (revision == sessionRevision) {
                if (e is CardSyncConflictException) preserveConflict(uid, e) else _errorMessage.value = e.message
            }
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override suspend fun fetchRemote(): Boolean {
        val revision = sessionRevision
        val uid = prefs.getString("uid", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        if (uid.isBlank() || token.isBlank()) return false
        _isLoading.value = true
        return try {
            fetchRemoteFor(revision, uid, token)
        } catch (e: Exception) {
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    private suspend fun fetchRemoteFor(revision: Long, uid: String, token: String): Boolean = try {
        val remoteCards = FirebaseClient.fetchCollection(uid, token)
        if (!isCurrentSession(revision, uid)) return false
        _cards.value = remoteCards

        val profile = FirebaseClient.fetchProfile(uid, token)
        if (!isCurrentSession(revision, uid)) return false
        if (profile != null) {
            val username = profile.optString("username", "Trainer")
            val source = profile.optString("profile_picture_source", "pokemon")
            val dex = profile.optInt("profile_featured_dex", 25)
            val url = profile.optString("profile_image_url", "")
            val base64 = profile.optString("profile_image_base64", "")
            _username.value = username
            _profilePicSource.value = source
            _profileFeaturedDex.value = dex
            _profileImageUrl.value = url
            _profileImageBase64.value = base64
            cacheProfile(uid, username, source, dex, url, base64)
        } else {
            val username = FirebaseClient.fetchUsername(uid, token)
            if (!isCurrentSession(revision, uid)) return false
            if (username != null) {
                _username.value = username
                cacheProfile(uid, username, _profilePicSource.value, _profileFeaturedDex.value, _profileImageUrl.value, _profileImageBase64.value)
            }
        }
        true
    } catch (e: Exception) {
        if (isCurrentSession(revision, uid)) _errorMessage.value = e.message ?: "Could not sync collection."
        false
    }

    override suspend fun updateProfile(
        username: String,
        source: String,
        dex: Int,
        url: String,
        base64: String
    ): Boolean {
        val revision = sessionRevision
        val uid = prefs.getString("uid", "") ?: ""
        val token = prefs.getString("token", "") ?: ""
        _isLoading.value = true
        return try {
            FirebaseClient.saveProfile(uid, token, username, source, dex, url, base64)
            if (!isCurrentSession(revision, uid)) return false
            
            _username.value = username
            _profilePicSource.value = source
            _profileFeaturedDex.value = dex
            _profileImageUrl.value = url
            _profileImageBase64.value = base64
            
            cacheProfile(uid, username, source, dex, url, base64)
            true
        } catch (e: Exception) {
            if (revision == sessionRevision) _errorMessage.value = e.message
            false
        } finally {
            if (revision == sessionRevision) _isLoading.value = false
        }
    }

    override fun logout() {
        sessionRevision++
        prefs.edit().remove("uid").remove("token").remove("refresh_token").apply()
        clearVisibleAccount()
    }

}
