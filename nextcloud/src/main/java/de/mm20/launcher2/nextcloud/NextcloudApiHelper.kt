package de.mm20.launcher2.nextcloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import de.mm20.launcher2.webdav.WebDavApi
import de.mm20.launcher2.webdav.WebDavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.IOException

class NextcloudApiHelper(val context: Context) {


    private val httpClient by lazy {
        OkHttpClient.Builder()
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.priorResponse?.priorResponse != null) return null
                    return response.request
                        .newBuilder()
                        .addHeader("Authorization", getAuthorization() ?: return null)
                        .build()
                }

            })
            .build()
    }

    private val preferences by lazy {
        createPreferences()
    }

    private fun createPreferences(catchErrors: Boolean = true): SharedPreferences {
        try {
            val masterKey =
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                "nextcloud",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: IOException) {
            if (!catchErrors) throw e
            File(context.filesDir, "../shared_prefs/nextcloud.xml").delete()
            return createPreferences(false)
        }
    }

    fun getLoginIntent(): Intent {
        return Intent(context, LoginActivity::class.java)
    }

    fun login(activity: Activity) {
        activity.startActivity(getLoginIntent())
    }


    suspend fun checkNextcloudInstallation(url: String): Boolean {
        var url = url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        val request = Request.Builder()
            .url("$url/remote.php/dav")
            .build()
        val response = runCatching {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
        }.getOrNull() ?: return false
        return response.code == 200 || response.code == 401
    }

    suspend fun getLoggedInUser(): NcUser? {
        val server = getServer()
        val username = getUserName()
        val token = getToken()

        if (server == null || username == null || token == null) {
            return null
        }

        val displayName = getDisplayName() ?: return null

        return NcUser(
            displayName,
            username
        )
    }

    /**
     * Returns the user's display name or user name if the user is logged in and their token has
     * not been revoked,
     * returns null if they are not logged in.
     */
    private suspend fun getDisplayName(): String? {

        val displayname = preferences.getString("displayname", null)
        if (displayname != null) {
            return displayname
        }

        val server = getServer() ?: return null

        val request = Request.Builder()
            .addHeader("OCS-APIRequest", "true")
            .url("$server/ocs/v1.php/cloud/user?format=json")
            .build()

        val response = runCatching {
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }
        }.getOrNull() ?: return getUserName()

        if (response.code != 200) {
            logout()
            return null
        }
        val body = response.body ?: return getUserName()

        return withContext(Dispatchers.IO) {
            val json = JSONObject(body.string())
            val name = json.optJSONObject("ocs")
                ?.optJSONObject("data")
                ?.optString("display-name")

            preferences.edit {
                putString("displayname", name)
            }

            return@withContext name
                ?: getUserName()
        }
    }

    private fun getAuthorization(): String? {
        return Credentials.basic(getUserName() ?: return null, getToken() ?: return null)
    }

    fun getServer(): String? {
        return preferences.getString("server", null)
    }

    fun getUserName(): String? {
        return preferences.getString("username", null)
    }

    private fun getToken(): String? {
        return preferences.getString("token", null)
    }

    internal fun setServer(server: String, username: String, token: String) {
        preferences.edit {
            putString("server", server)
            putString("username", username)
            putString("token", token)
        }
    }

    suspend fun logout() {
        val server = getServer()
        val username = getUserName()
        val token = getToken()
        if (server == null || username == null || token == null) return
        val request = Request.Builder()
            .addHeader("OCS-APIREQUEST", "true")
            .delete()
            .url("$server/ocs/v2.php/core/apppassword")
            .build()
        withContext(Dispatchers.IO) {
            val response = httpClient.newCall(request).execute()
            response
        }
        preferences.edit {
            putString("server", null)
            putString("username", null)
            putString("token", null)
            putString("displayname", null)
        }
    }

    val files by lazy {
        FilesApi()
    }

    inner class FilesApi internal constructor() {
        suspend fun search(query: String): List<WebDavFile> {
            val server = getServer() ?: return emptyList()
            val username = getUserName() ?: return emptyList()
            return WebDavApi.search("$server/remote.php/dav/", username, query, httpClient)
        }
    }
}