package org.apache.cordova.plugin.echo

import android.content.Intent
import android.net.Uri
import android.util.Log
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.SharedSessionSyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaPlugin
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Echo Cordova Plugin implemented in Kotlin with Clerk Android SDK Integration.
 */
class Echo : CordovaPlugin() {

    companion object {
        private const val TAG = "EchoPlugin"
        private const val NETWORK_TIMEOUT_MS = 30000L
    }

    private val pluginScope = CoroutineScope(Dispatchers.IO)
    private var inMemoryPublishableKey: String = ""

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        Log.d(TAG, "Executing action: $action")
        return when (action) {
            "echo" -> {
                val message = args.optString(0, "")
                this.echo(message, callbackContext)
                true
            }
            "echoAsync" -> {
                val message = args.optString(0, "")
                this.echoAsync(message, callbackContext)
                true
            }
            "add" -> {
                val num1 = args.optDouble(0, Double.NaN)
                val num2 = args.optDouble(1, Double.NaN)
                this.add(num1, num2, callbackContext)
                true
            }
            "checkClerk" -> {
                val publishableKey = args.optString(0, "")
                this.checkClerk(publishableKey, callbackContext)
                true
            }
            "initializeClerk" -> {
                val publishableKey = args.optString(0, "")
                val enableSharedSessionSync = args.optBoolean(1, true)
                this.initializeClerk(publishableKey, enableSharedSessionSync, callbackContext)
                true
            }
            "signInWithPassword" -> {
                val identifier = args.optString(0, "")
                val password = args.optString(1, "")
                this.signInWithPassword(identifier, password, callbackContext)
                true
            }
            "signInWithMicrosoft" -> {
                this.signInWithMicrosoft(callbackContext)
                true
            }
            "signOut" -> {
                this.signOut(callbackContext)
                true
            }
            "getCurrentUser" -> {
                this.getCurrentUser(callbackContext)
                true
            }
            "reloadFromSharedStorage" -> {
                this.reloadFromSharedStorage(callbackContext)
                true
            }
            "testConnection" -> {
                val publishableKey = args.optString(0, "")
                this.testConnection(publishableKey, callbackContext)
                true
            }
            "getKeychainAccessGroup" -> {
                this.getKeychainAccessGroup(callbackContext)
                true
            }
            else -> false
        }
    }

    private fun echo(message: String?, callbackContext: CallbackContext) {
        val msg = message ?: ""
        if (msg.isNotEmpty()) {
            callbackContext.success(msg)
        } else {
            callbackContext.error("Expected one non-empty string argument.")
        }
    }

    private fun echoAsync(message: String?, callbackContext: CallbackContext) {
        cordova.threadPool.execute {
            val msg = message ?: ""
            if (msg.isNotEmpty()) {
                val response = JSONObject().apply {
                    put("status", "success")
                    put("message", msg)
                    put("timestamp", System.currentTimeMillis())
                    put("language", "Kotlin")
                }
                callbackContext.success(response)
            } else {
                callbackContext.error("Expected one non-empty string argument.")
            }
        }
    }

    private fun add(num1: Double, num2: Double, callbackContext: CallbackContext) {
        if (num1.isNaN() || num2.isNaN()) {
            callbackContext.error("Expected two valid numeric arguments.")
        } else {
            val sum = num1 + num2
            val response = JSONObject().apply {
                put("num1", num1)
                put("num2", num2)
                put("sum", sum)
            }
            callbackContext.success(response)
        }
    }

    private fun isClerkInitialized(): Boolean {
        return try {
            val method = Clerk::class.java.methods.firstOrNull { it.name == "isInitialized" }
            if (method != null) {
                val res = method.invoke(null)
                if (res is Boolean) res else true
            } else {
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun checkClerk(publishableKey: String?, callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                Class.forName("com.clerk.api.Clerk")
                response.put("sdkAvailable", true)
                response.put("className", "com.clerk.api.Clerk")

                val key = publishableKey ?: ""
                if (key.isNotEmpty()) {
                    inMemoryPublishableKey = key
                    try {
                        val context = cordova.activity.applicationContext
                        val options = ClerkConfigurationOptions(
                            sharedSessionSync = SharedSessionSyncConfig.enabled
                        )
                        Clerk.initialize(context, key, options)
                        response.put("initialized", true)
                        response.put("publishableKey", key)
                        response.put("sharedSessionSyncEnabled", true)
                        response.put("message", "Clerk SDK initialized successfully.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Initialization failed in checkClerk", e)
                        response.put("initialized", false)
                        response.put("error", e.message ?: e.toString())
                    }
                } else {
                    response.put("initialized", isClerkInitialized())
                    response.put("message", "Clerk SDK is present on Android classpath.")
                }

                response.put("status", "success")
                response.put("platform", "android")
                response.put("timestamp", System.currentTimeMillis())
                callbackContext.success(response)
            } catch (e: ClassNotFoundException) {
                Log.e(TAG, "Clerk SDK class not found", e)
                response.put("sdkAvailable", false)
                response.put("status", "error")
                response.put("message", "Clerk SDK not found on classpath.")
                callbackContext.error(response)
            } catch (e: Throwable) {
                Log.e(TAG, "Error in checkClerk", e)
                response.put("sdkAvailable", false)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun initializeClerk(publishableKey: String?, enableSharedSessionSync: Boolean, callbackContext: CallbackContext) {
        val key = publishableKey ?: ""
        if (key.isEmpty()) {
            callbackContext.error("Expected a non-empty publishableKey argument.")
            return
        }
        inMemoryPublishableKey = key
        pluginScope.launch {
            try {
                val context = cordova.activity.applicationContext
                val syncConfig = if (enableSharedSessionSync) SharedSessionSyncConfig.enabled else null
                val options = ClerkConfigurationOptions(sharedSessionSync = syncConfig)

                Clerk.initialize(
                    context = context,
                    publishableKey = key,
                    options = options
                )

                val response = JSONObject().apply {
                    put("status", "success")
                    put("message", "Clerk SDK initialized successfully.")
                    put("publishableKey", key)
                    put("sharedSessionSyncEnabled", enableSharedSessionSync)
                    put("platform", "android")
                    put("timestamp", System.currentTimeMillis())
                }
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "initializeClerk failed", e)
                val response = JSONObject().apply {
                    put("status", "error")
                    put("message", "Failed to initialize Clerk SDK: ${e.message}")
                    put("error", e.toString())
                }
                callbackContext.error(response)
            }
        }
    }

    private fun reloadFromSharedStorage(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                val stateChanged = Clerk.reloadFromSharedStorage()
                response.put("status", "success")
                response.put("stateChanged", stateChanged)
                response.put("message", "Reloaded shared storage successfully.")
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "reloadFromSharedStorage exception", e)
                response.put("status", "error")
                response.put("message", "Failed to reload shared storage: ${e.message}")
                response.put("error", e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun testConnection(publishableKey: String?, callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            val diagnostics = JSONObject()
            try {
                var networkReachable = false
                var responseCode = -1
                try {
                    val url = URL("https://clerk.com")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.connect()
                    responseCode = connection.responseCode
                    networkReachable = (responseCode in 200..399)
                    connection.disconnect()
                } catch (netEx: Throwable) {
                    diagnostics.put("networkError", netEx.message ?: netEx.toString())
                }

                diagnostics.put("networkReachable", networkReachable)
                diagnostics.put("httpResponseCode", responseCode)
                diagnostics.put("isSDKInitialized", isClerkInitialized())

                response.put("status", if (networkReachable) "success" else "warning")
                response.put("message", "Diagnostic pipeline executed.")
                response.put("diagnostics", diagnostics)
                response.put("timestamp", System.currentTimeMillis())

                callbackContext.success(response)
            } catch (e: Throwable) {
                response.put("status", "error")
                response.put("message", "Diagnostic pipeline failed: ${e.message}")
                callbackContext.error(response)
            }
        }
    }

    private fun signInWithPassword(identifier: String?, password: String?, callbackContext: CallbackContext) {
        val id = identifier ?: ""
        val pass = password ?: ""
        if (id.isEmpty() || pass.isEmpty()) {
            callbackContext.error("Expected non-empty identifier and password.")
            return
        }

        pluginScope.launch {
            val response = JSONObject()
            try {
                val result = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                    Clerk.auth.signInWithPassword {
                        this.identifier = id
                        this.password = pass
                    }
                }

                if (result == null) {
                    response.put("status", "error")
                    response.put("message", "Sign in request timed out after ${NETWORK_TIMEOUT_MS / 1000} seconds.")
                    response.put("errorCode", "timeout")
                    callbackContext.error(response)
                    return@launch
                }

                when (result) {
                    is com.clerk.api.network.serialization.ClerkResult.Success -> {
                        val signIn = result.value
                        val sessionId = signIn.createdSessionId
                        if (!sessionId.isNullOrEmpty()) {
                            try {
                                Clerk.auth.setActive(sessionId = sessionId)
                            } catch (t: Throwable) {
                                Log.w(TAG, "Failed to set active session: ${t.message}")
                            }
                        }

                        val user = try { Clerk.user } catch (t: Throwable) { null }
                        val activeSession = try { Clerk.session ?: Clerk.auth.sessions.firstOrNull() } catch (t: Throwable) { null }
                        val effectiveUser = user ?: activeSession?.user

                        response.put("status", "success")
                        response.put("message", "Sign in successful")
                        response.put("signInId", signIn.id)
                        response.put("signInStatus", try { signIn.status.name } catch (t: Throwable) { "COMPLETE" })
                        response.put("createdSessionId", sessionId ?: "")
                        response.put("userId", effectiveUser?.id ?: "")
                        response.put("firstName", effectiveUser?.firstName ?: "")
                        response.put("lastName", effectiveUser?.lastName ?: "")
                        response.put("identifier", id)
                        response.put("platform", "android")
                        callbackContext.success(response)
                    }

                    is com.clerk.api.network.serialization.ClerkResult.Failure -> {
                        val (errMessage, errCode) = extractClerkError(result)
                        Log.e(TAG, "signInWithPassword FAILURE: $errMessage (code: $errCode)")
                        response.put("status", "error")
                        response.put("message", errMessage)
                        response.put("errorCode", errCode)
                        response.put("error", errMessage)
                        response.put("platform", "android")
                        callbackContext.error(response)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "signInWithPassword error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    /**
     * Safely extract human-readable error messages from ClerkResult.Failure.
     */
    private fun extractClerkError(failure: com.clerk.api.network.serialization.ClerkResult.Failure<*>): Pair<String, String> {
        var errorMessage = ""
        var errorCode = ""
        val errorObj = failure.error
        val throwable = failure.throwable

        if (errorObj != null) {
            try {
                val errorsField = errorObj.javaClass.getDeclaredField("errors")
                errorsField.isAccessible = true
                val errorsList = errorsField.get(errorObj) as? List<*>
                if (!errorsList.isNullOrEmpty()) {
                    val firstErr = errorsList.first()
                    if (firstErr != null) {
                        val longMsgField = try { firstErr.javaClass.getDeclaredField("longMessage") } catch (t: Throwable) { null }
                        val msgField = try { firstErr.javaClass.getDeclaredField("message") } catch (t: Throwable) { null }
                        val codeField = try { firstErr.javaClass.getDeclaredField("code") } catch (t: Throwable) { null }
                        longMsgField?.isAccessible = true
                        msgField?.isAccessible = true
                        codeField?.isAccessible = true

                        errorMessage = (longMsgField?.get(firstErr) as? String)
                            ?: (msgField?.get(firstErr) as? String)
                            ?: ""
                        errorCode = (codeField?.get(firstErr) as? String) ?: ""
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "extractClerkError: could not read errors field", t)
            }
        }

        if (errorMessage.isEmpty() && throwable != null) {
            errorMessage = throwable.message ?: throwable.toString()
        }
        if (errorMessage.isEmpty()) {
            errorMessage = "Authentication failed. Please verify your credentials."
        }
        if (errorCode.isEmpty()) {
            errorCode = "auth_failed"
        }

        return Pair(errorMessage, errorCode)
    }

    private fun getFrontendApiHost(publishableKey: String): String? {
        if (publishableKey.isEmpty()) return null
        val parts = publishableKey.split("_")
        if (parts.size < 3) return null
        val encoded = parts[2].trimEnd('$')
        return try {
            val decodedBytes = android.util.Base64.decode(
                encoded,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            String(decodedBytes, Charsets.UTF_8).trimEnd('$')
        } catch (e: Exception) {
            null
        }
    }

    private fun signInWithMicrosoft(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                val pk = inMemoryPublishableKey
                val host = getFrontendApiHost(pk) ?: "clerk.accounts.dev"
                var targetUrl = "https://$host/sign-in"

                // Query Clerk Frontend API to get direct Microsoft authorization redirect URL
                try {
                    val url = URL("https://$host/v1/client/sign_ins")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    if (pk.isNotEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer $pk")
                    }
                    conn.doOutput = true
                    conn.connectTimeout = 7000
                    conn.readTimeout = 7000

                    val body = "strategy=oauth_microsoft"
                    conn.outputStream.use { os ->
                        os.write(body.toByteArray(Charsets.UTF_8))
                    }

                    val respCode = conn.responseCode
                    val stream = if (respCode in 200..399) conn.inputStream else conn.errorStream
                    val respText = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    conn.disconnect()

                    if (respText.isNotEmpty()) {
                        val json = JSONObject(respText)
                        val respObj = json.optJSONObject("response")
                        val verificationObj = respObj?.optJSONObject("first_factor_verification")
                        val extUrl = verificationObj?.optString("external_verification_redirect_url", "") ?: ""
                        if (extUrl.isNotEmpty()) {
                            targetUrl = extUrl
                        }
                    }
                } catch (netEx: Throwable) {
                    Log.w(TAG, "Could not obtain dynamic OAuth URL from Clerk, using hosted sign-in fallback: ${netEx.message}")
                }

                // Launch external browser / Custom Tab for user authentication
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                cordova.activity.startActivity(intent)

                response.put("status", "success")
                response.put("message", "Microsoft OAuth browser opened.")
                response.put("url", targetUrl)
                response.put("requiresRedirect", true)
                response.put("platform", "android")
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "signInWithMicrosoft error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun signOut(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                    Clerk.auth.signOut()
                }
                response.put("status", "success")
                response.put("message", "Signed out successfully")
                response.put("platform", "android")
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "signOut error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun getCurrentUser(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                var userId = ""
                var firstName = ""
                var lastName = ""
                var sessionId = ""
                var isSignedIn = false

                val userObj = try { Clerk.user } catch (t: Throwable) { null }
                val sessionObj = try { Clerk.session } catch (t: Throwable) { null }
                val sessionsObj = try { Clerk.auth.sessions } catch (t: Throwable) { emptyList() }
                val activeSession = sessionObj ?: sessionsObj.firstOrNull()
                val activeUser = userObj ?: activeSession?.user

                if (activeUser != null) {
                    isSignedIn = true
                    userId = activeUser.id
                    firstName = activeUser.firstName ?: ""
                    lastName = activeUser.lastName ?: ""
                    sessionId = activeSession?.id ?: ""
                }

                response.put("status", "success")
                response.put("isSignedIn", isSignedIn)
                response.put("userId", userId)
                response.put("firstName", firstName)
                response.put("lastName", lastName)
                response.put("sessionId", sessionId)
                response.put("platform", "android")
                if (!isSignedIn) {
                    response.put("message", "No active session found.")
                }
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.w(TAG, "getCurrentUser safe fallback", e)
                response.put("status", "success")
                response.put("isSignedIn", false)
                response.put("userId", "")
                response.put("platform", "android")
                response.put("message", "No active session found.")
                callbackContext.success(response)
            }
        }
    }

    private fun getKeychainAccessGroup(callbackContext: CallbackContext) {
        val response = JSONObject().apply {
            put("status", "success")
            put("storageType", "AndroidEncryptedSharedStorage")
            put("platform", "android")
        }
        callbackContext.success(response)
    }
}
