package paol0b.azuredevops.checkout

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service for OAuth 2.0 authentication with Azure DevOps using Device Code Flow.
 * 
 * This implementation uses the Device Code Flow (like Visual Studio 2022) which:
 * - Does NOT require app registration or client secrets
 * - User simply logs in via browser
 * - Works automatically for all users
 * 
 * How it works:
 * 1. Request a device code from Microsoft
 * 2. Show the code to the user
 * 3. Open browser to Microsoft login page
 * 4. User enters the code and authenticates
 * 5. Plugin polls Microsoft for the access token
 * 6. Token is saved globally in the IDE
 */
@Service(Service.Level.APP)
class AzureDevOpsOAuthService {

    private val logger = Logger.getInstance(AzureDevOpsOAuthService::class.java)
    
    // Microsoft Entra ID Device Code Flow endpoints
    private val DEVICE_CODE_URL = "https://login.microsoftonline.com/organizations/oauth2/v2.0/devicecode"
    private val TOKEN_URL = "https://login.microsoftonline.com/organizations/oauth2/v2.0/token"
    
    // Public client ID for Azure CLI (works without registration)
    // This is Microsoft's official Azure CLI client ID, publicly documented and safe to use
    private val CLIENT_ID = "04b07795-8ddb-461a-bbee-02f9e1bf7b46"  // Azure CLI client ID
    
    // Azure DevOps resource URI and scopes
    private val RESOURCE_URI = "499b84ac-1321-427f-aa17-267ca6975798"  // Azure DevOps resource ID
    private val SCOPES = "$RESOURCE_URI/.default offline_access"

    companion object {
        private const val API_VERSION = "7.0"

        fun getInstance(): AzureDevOpsOAuthService {
            return ApplicationManager.getApplication().getService(AzureDevOpsOAuthService::class.java)
        }
    }

    /**
     * Callback interface for device code flow updates
     */
    interface DeviceCodeCallback {
        fun onDeviceCodeReceived(userCode: String, verificationUri: String, message: String)
    }

    /**
     * Request device code synchronously (blocking).
     * Use this to get the code before showing UI.
     */
    fun requestDeviceCodeSync(): DeviceCodeResponse? {
        return requestDeviceCode()
    }

    /**
     * Initiates Device Code Flow authentication (like Visual Studio 2022).
     * This method:
     * 1. Requests a device code from Microsoft
     * 2. Calls callback with device code info
     * 3. Opens browser for user to login
     * 4. Polls for token until user completes authentication
     * 
     * @param organizationUrl The Azure DevOps organization URL
     * @param deviceCodeResponse Pre-fetched device code response
     * @return CompletableFuture with the OAuth result or null if authentication failed
     */
    fun authenticateWithDeviceCode(organizationUrl: String, deviceCodeResponse: DeviceCodeResponse): CompletableFuture<OAuthResult?> {
        val future = CompletableFuture<OAuthResult?>()
        
        try {
            logger.info("Starting device code authentication")
            logger.info("User code: ${deviceCodeResponse.userCode}")
            logger.info("Verification URI: ${deviceCodeResponse.verificationUri}")
            
            // Browser should already be opened by DeviceCodeAuthDialog
            // BrowserUtil.browse(deviceCodeResponse.verificationUri)
            
            // Poll for token in background
            val executor = Executors.newSingleThreadExecutor()
            executor.submit {
                try {
                    val result = pollForToken(deviceCodeResponse, organizationUrl)
                    future.complete(result)
                } catch (e: Exception) {
                    logger.error("Error during token polling", e)
                    future.complete(null)
                } finally {
                    executor.shutdown()
                }
            }
            
        } catch (e: Exception) {
            logger.error("Failed to start device code authentication", e)
            future.complete(null)
        }
        
        return future
    }

    /**
     * Refreshes an access token using a refresh token.
     * According to Microsoft docs: https://learn.microsoft.com/en-us/azure/active-directory/develop/v2-oauth2-device-code
     */
    fun refreshAccessToken(refreshToken: String, organizationUrl: String): OAuthResult? {
        return try {
            logger.info("Attempting to refresh access token")
            val connection = URI.create(TOKEN_URL).toURL().openConnection() as java.net.HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true

            val postData = "client_id=${URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)}" +
                    "&grant_type=refresh_token" +
                    "&refresh_token=${URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)}" +
                    "&scope=${URLEncoder.encode(SCOPES, StandardCharsets.UTF_8)}"

            connection.outputStream.use { os ->
                os.write(postData.toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                logger.info("Successfully refreshed access token")
                return parseTokenResponse(response, organizationUrl)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                logger.error("Failed to refresh token: HTTP $responseCode - $error")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to refresh access token", e)
            null
        }
    }

    /**
     * Authenticates using Personal Access Token (PAT) - fallback method
     */
    fun authenticateWithPAT(organizationUrl: String, pat: String): OAuthResult? {
        return try {
            // Test connection with PAT
            if (testConnection(organizationUrl, pat)) {
                OAuthResult(
                    accessToken = pat,
                    refreshToken = null,
                    expiresIn = null,
                    serverUrl = organizationUrl
                )
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to authenticate with PAT", e)
            null
        }
    }

    /**
     * Step 1: Request a device code from Microsoft
     */
    private fun requestDeviceCode(): DeviceCodeResponse? {
        return try {
            val connection = URI.create(DEVICE_CODE_URL).toURL().openConnection() as java.net.HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.doOutput = true

            // Request device code with Azure DevOps scopes
            val postData = "client_id=${URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)}" +
                    "&scope=${URLEncoder.encode(SCOPES, StandardCharsets.UTF_8)}"

            connection.outputStream.use { os ->
                os.write(postData.toByteArray(StandardCharsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseDeviceCodeResponse(response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                logger.error("Failed to request device code: HTTP $responseCode - $error")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to request device code", e)
            null
        }
    }

    /**
     * Parse device code response from Microsoft
     */
    private fun parseDeviceCodeResponse(json: String): DeviceCodeResponse? {
        return try {
            val gson = Gson()
            val jsonObject = gson.fromJson(json, JsonObject::class.java)
            
            DeviceCodeResponse(
                deviceCode = jsonObject.get("device_code")?.asString ?: return null,
                userCode = jsonObject.get("user_code")?.asString ?: return null,
                verificationUri = jsonObject.get("verification_uri")?.asString ?: return null,
                expiresIn = jsonObject.get("expires_in")?.asInt ?: 900,
                interval = jsonObject.get("interval")?.asInt ?: 5,
                message = jsonObject.get("message")?.asString ?: ""
            )
        } catch (e: Exception) {
            logger.error("Failed to parse device code response", e)
            null
        }
    }

    /**
     * Step 2: Poll for access token until user completes authentication
     */
    private fun pollForToken(deviceCodeResponse: DeviceCodeResponse, organizationUrl: String): OAuthResult? {
        val startTime = System.currentTimeMillis()
        val timeout = deviceCodeResponse.expiresIn * 1000L
        val pollInterval = deviceCodeResponse.interval * 1000L
        
        logger.info("Polling for token (timeout: ${deviceCodeResponse.expiresIn}s, interval: ${deviceCodeResponse.interval}s)")
        
        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                val connection = URI.create(TOKEN_URL).toURL().openConnection() as java.net.HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true

                val postData = "client_id=${URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)}" +
                        "&grant_type=urn:ietf:params:oauth:grant-type:device_code" +
                        "&device_code=${URLEncoder.encode(deviceCodeResponse.deviceCode, StandardCharsets.UTF_8)}"

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray(StandardCharsets.UTF_8))
                }

                val responseCode = connection.responseCode
                when (responseCode) {
                    200 -> {
                        // Success! We got the token
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        logger.info("Successfully obtained access token")
                        return parseTokenResponse(response, organizationUrl)
                    }
                    400 -> {
                        // Check error type
                        val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        val gson = Gson()
                        val errorJson = gson.fromJson(errorResponse, JsonObject::class.java)
                        val error = errorJson?.get("error")?.asString
                        
                        when (error) {
                            "authorization_pending" -> {
                                // User hasn't completed authentication yet, continue polling
                                logger.debug("Authorization pending, will retry...")
                            }
                            "authorization_declined" -> {
                                logger.warn("User declined authorization")
                                return null
                            }
                            "expired_token" -> {
                                logger.error("Device code expired")
                                return null
                            }
                            else -> {
                                logger.error("Token request error: $error - $errorResponse")
                                return null
                            }
                        }
                    }
                    else -> {
                        val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        logger.error("Unexpected response: HTTP $responseCode - $error")
                        return null
                    }
                }
                
                // Wait before next poll
                Thread.sleep(pollInterval)
                
            } catch (e: InterruptedException) {
                logger.warn("Polling interrupted")
                return null
            } catch (e: Exception) {
                logger.error("Error during token polling", e)
                Thread.sleep(pollInterval)  // Continue polling despite error
            }
        }
        
        logger.error("Timeout waiting for user authentication")
        return null
    }

    /**
     * Parse token response and create OAuthResult
     */
    private fun parseTokenResponse(json: String, organizationUrl: String): OAuthResult? {
        return try {
            val gson = Gson()
            val jsonObject = gson.fromJson(json, JsonObject::class.java)
            
            val accessToken = jsonObject.get("access_token")?.asString ?: return null
            val refreshToken = jsonObject.get("refresh_token")?.asString
            val expiresIn = jsonObject.get("expires_in")?.asInt ?: 3600
            
            logger.info("Token received, expires in $expiresIn seconds")
            
            OAuthResult(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = expiresIn,
                serverUrl = organizationUrl
            )
        } catch (e: Exception) {
            logger.error("Failed to parse token response", e)
            null
        }
    }

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int,
        val message: String
    )

    data class OAuthResult(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int?,
        val serverUrl: String
    )

    private fun testConnection(serverUrl: String, token: String): Boolean {
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')
            val url = "$normalizedUrl/_apis/projects?\$top=1&api-version=$API_VERSION"
            val connection = URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
            
            try {
                connection.requestMethod = "GET"
                val credentials = ":$token"
                val encodedCredentials = java.util.Base64.getEncoder()
                    .encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                responseCode == java.net.HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            logger.error("Connection test failed", e)
            false
        }
    }
}
