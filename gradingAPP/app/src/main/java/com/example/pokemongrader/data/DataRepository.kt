package com.example.pokemongrader.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface DataRepository {
    val isLoggedIn: StateFlow<Boolean>
    val cards: StateFlow<List<Card>>
    val isLoading: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>
    
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
    suspend fun removeCard(card: Card): Boolean
    suspend fun fetchRemote(): Boolean
    suspend fun refreshAndFetch(): Boolean
    suspend fun updateProfile(username: String, source: String, dex: Int, url: String, base64: String): Boolean
    fun logout()
}

class DefaultDataRepository(private val context: Context) : DataRepository {
    private val prefs: SharedPreferences = context.getSharedPreferences("PokeGraderPrefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isLoggedIn = MutableStateFlow(false)
    override val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    override val cards: StateFlow<List<Card>> = _cards.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Profile Settings
    private val _username = MutableStateFlow("Dr4g0n")
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
        _username.value = prefs.getString("username", "Dr4g0n") ?: "Dr4g0n"
        _profilePicSource.value = prefs.getString("profile_pic_source", "pokemon") ?: "pokemon"
        _profileFeaturedDex.value = prefs.getInt("profile_featured_dex", 25)
        _profileImageUrl.value = prefs.getString("profile_image_url", "") ?: ""
        _profileImageBase64.value = prefs.getString("profile_image_base64", "") ?: ""

        val refreshToken = prefs.getString("refresh_token", "")
        if (!refreshToken.isNullOrEmpty()) {
            _isLoggedIn.value = true
            // Silently refresh the idToken then fetch — fixes the "must logout/login" bug
            scope.launch { refreshAndFetch() }
        }
    }

    /**
     * Silently exchanges the stored refreshToken for a fresh idToken, then fetches
     * the remote collection. Falls back to stored idToken if refresh fails.
     */
    override suspend fun refreshAndFetch(): Boolean {
        val storedRefreshToken = prefs.getString("refresh_token", "") ?: ""
        if (storedRefreshToken.isNotEmpty()) {
            val newIdToken = FirebaseClient.refreshIdToken(storedRefreshToken)
            if (newIdToken != null) {
                prefs.edit().putString("token", newIdToken).apply()
            }
        }
        return fetchRemote()
    }

    override suspend fun login(email: String, password: String): Boolean {
        _isLoading.value = true
        return try {
            val result = FirebaseClient.signIn(email, password)
            prefs.edit()
                .putString("token", result.idToken)
                .putString("uid", result.uid)
                .putString("refresh_token", result.refreshToken)
                .apply()
            _isLoggedIn.value = true
            fetchRemote()
            true
        } catch (e: Exception) {
            _errorMessage.value = e.message
            false
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun register(email: String, password: String): Boolean {
        _isLoading.value = true
        return try {
            val result = FirebaseClient.signUp(email, password)
            prefs.edit()
                .putString("token", result.idToken)
                .putString("uid", result.uid)
                .putString("refresh_token", result.refreshToken)
                .apply()
            _isLoggedIn.value = true
            fetchRemote()
            true
        } catch (e: Exception) {
            _errorMessage.value = e.message
            false
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun addCard(card: Card): Boolean {
        _isLoading.value = true
        return try {
            val uid = prefs.getString("uid", "") ?: ""
            val token = prefs.getString("token", "") ?: ""
            val current = _cards.value.toMutableList()
            current.add(card)
            FirebaseClient.saveCollection(uid, token, current)
            _cards.value = current
            true
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun removeCard(card: Card): Boolean {
        _isLoading.value = true
        return try {
            val uid = prefs.getString("uid", "") ?: ""
            val token = prefs.getString("token", "") ?: ""
            val current = _cards.value.toMutableList()
            val iterator = current.iterator()
            while (iterator.hasNext()) {
                val c = iterator.next()
                if (c.page == card.page && c.slot == card.slot && c.name.equals(card.name, ignoreCase = true) && c.type == card.type && c.condition == card.condition) {
                    iterator.remove()
                    break
                }
            }
            FirebaseClient.saveCollection(uid, token, current)
            _cards.value = current
            true
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun fetchRemote(): Boolean {
        _isLoading.value = true
        return try {
            val uid = prefs.getString("uid", "") ?: ""
            val token = prefs.getString("token", "") ?: ""
            _cards.value = FirebaseClient.fetchCollection(uid, token)

            val profile = FirebaseClient.fetchProfile(uid, token)
            if (profile != null) {
                val uname = profile.optString("username", "Dr4g0n")
                val source = profile.optString("profile_picture_source", "pokemon")
                val dex = profile.optInt("profile_featured_dex", 25)
                val urlImg = profile.optString("profile_image_url", "")
                val base64Img = profile.optString("profile_image_base64", "")
                
                _username.value = uname
                _profilePicSource.value = source
                _profileFeaturedDex.value = dex
                _profileImageUrl.value = urlImg
                _profileImageBase64.value = base64Img
                
                prefs.edit()
                    .putString("username", uname)
                    .putString("profile_pic_source", source)
                    .putInt("profile_featured_dex", dex)
                    .putString("profile_image_url", urlImg)
                    .putString("profile_image_base64", base64Img)
                    .apply()
            } else {
                val uName = FirebaseClient.fetchUsername(uid, token)
                if (uName != null) {
                    _username.value = uName
                    prefs.edit().putString("username", uName).apply()
                }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun updateProfile(
        username: String,
        source: String,
        dex: Int,
        url: String,
        base64: String
    ): Boolean {
        _isLoading.value = true
        return try {
            val uid = prefs.getString("uid", "") ?: ""
            val token = prefs.getString("token", "") ?: ""
            
            FirebaseClient.saveProfile(uid, token, username, source, dex, url, base64)
            
            _username.value = username
            _profilePicSource.value = source
            _profileFeaturedDex.value = dex
            _profileImageUrl.value = url
            _profileImageBase64.value = base64
            
            prefs.edit()
                .putString("username", username)
                .putString("profile_pic_source", source)
                .putInt("profile_featured_dex", dex)
                .putString("profile_image_url", url)
                .putString("profile_image_base64", base64)
                .apply()
            true
        } catch (e: Exception) {
            _errorMessage.value = e.message
            false
        } finally {
            _isLoading.value = false
        }
    }

    override fun logout() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
        _cards.value = emptyList()
        _username.value = "Dr4g0n"
        _profilePicSource.value = "pokemon"
        _profileFeaturedDex.value = 25
        _profileImageUrl.value = ""
        _profileImageBase64.value = ""
    }
}
