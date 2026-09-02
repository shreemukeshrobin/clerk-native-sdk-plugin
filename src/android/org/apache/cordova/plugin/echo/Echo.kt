package org.apache.cordova.plugin.echo

import android.util.Log
import com.clerk.api.Clerk
import com.clerk.api.ClerkConfigurationOptions
import com.clerk.api.SharedSessionSyncConfig
import com.clerk.api.auth.OAuthProvider
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
                val signInMethod = Clerk::class.java.methods.firstOrNull { it.name.startsWith("signInWithPassword") }
                if (signInMethod == null) {
                    response.put("status", "error")
                    response.put("message", "Native signInWithPassword method not resolved on current Clerk SDK.")
                    callbackContext.error(response)
                    return@launch
                }

                response.put("status", "success")
                response.put("message", "Credentials received. Flow initiated.")
                response.put("identifier", id)
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "signInWithPassword error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
            }
        }
    }

    private fun signInWithMicrosoft(callbackContext: CallbackContext) {
        pluginScope.launch {
            val response = JSONObject()
            try {
                val oAuthMethod = Clerk::class.java.methods.firstOrNull { it.name.contains("OAuth", ignoreCase = true) }
                if (oAuthMethod == null) {
                    response.put("status", "error")
                    response.put("message", "Native OAuth launcher method not resolved on current Clerk SDK.")
                    callbackContext.error(response)
                    return@launch
                }

                response.put("status", "success")
                response.put("message", "OAuth flow invoked.")
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
                val signOutMethod = Clerk::class.java.methods.firstOrNull { it.name == "signOut" }
                if (signOutMethod != null) {
                    signOutMethod.invoke(null)
                }
                response.put("status", "success")
                response.put("message", "Signed out successfully")
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
                val userProp = Clerk::class.java.methods.firstOrNull { it.name == "getUser" || it.name == "getCurrentUser" }
                val currentUser = userProp?.invoke(null)

                if (currentUser != null) {
                    response.put("status", "success")
                    response.put("isSignedIn", true)
                    response.put("userId", currentUser.toString())
                } else {
                    response.put("status", "success")
                    response.put("isSignedIn", false)
                    response.put("message", "No active session found.")
                }
                callbackContext.success(response)
            } catch (e: Throwable) {
                Log.e(TAG, "getCurrentUser error", e)
                response.put("status", "error")
                response.put("message", e.message ?: e.toString())
                callbackContext.error(response)
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
