// src/main/kotlin/com/shifa/service/DailyVideoService.kt
package com.shifa.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.shifa.config.DailyProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

@Service
class DailyVideoService(
    private val dailyProperties: DailyProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    /**
     * Check if Daily.co API key is configured
     */
    fun isApiKeyConfigured(): Boolean {
        return try {
            dailyProperties.apiKey.isNotBlank()
        } catch (e: UninitializedPropertyAccessException) {
            false
        }
    }
    
    /**
     * Construct room URL from domain and room name
     * Daily.co room URLs follow the pattern: https://domain.daily.co/room-name
     */
    private fun constructRoomUrl(roomName: String): String {
        // Extract domain from API URL (e.g., https://api.daily.co/v1 -> shifauz.daily.co)
        // Or use the configured domain if available
        // For now, we'll use the user's domain: shifauz.daily.co
        val domain = "shifauz.daily.co"
        return "https://$domain/$roomName"
    }

    data class CreateRoomRequest(
        val name: String,
        val privacy: String = "private",
        val properties: RoomProperties = RoomProperties()
    )

    /**
     * Room properties as expected by Daily.co.
     * enable_recording must be a STRING ('cloud', 'cloud-audio-only', or 'raw-tracks'),
     * not a boolean.
     */
    data class RoomProperties(
        val exp: Long? = null,
        val enable_chat: Boolean = true,
        val enable_screenshare: Boolean = true,
        val enable_recording: String? = null,
        val max_participants: Int = 2
    )

    data class CreateTokenRequest(
        val properties: TokenProperties
    )

    data class TokenProperties(
        val room_name: String,
        val user_id: String,
        val user_name: String,
        val is_owner: Boolean = false,
        val exp: Long
    )

    data class RoomResponse(
        val id: String? = null,
        val name: String? = null,
        val url: String? = null,
        val config: Map<String, Any>? = null
    )

    data class TokenResponse(
        val token: String? = null,
        val id: String? = null
    )

    /**
     * Get or create a Daily.co room
     */
    fun getOrCreateRoom(roomName: String, maxParticipants: Int = 2): RoomResponse {
        logger.info("Getting or creating room: $roomName with maxParticipants: $maxParticipants")
        
        // Validate API key is configured
        if (!isApiKeyConfigured()) {
            logger.error("Daily.co API key is not configured. Cannot create room.")
            throw RuntimeException("Daily.co API key is not configured. Please set DAILY_API_KEY environment variable.")
        }
        
        logger.debug("Daily API URL: ${dailyProperties.apiUrl}, API Key present: ${isApiKeyConfigured()}")
        
        try {
            // First, try to get existing room
            val getRequest = HttpRequest.newBuilder()
                .uri(URI.create("${dailyProperties.apiUrl}/rooms/$roomName"))
                .header("Authorization", "Bearer ${dailyProperties.apiKey}")
                .header("Content-Type", "application/json")
                .GET()
                .build()

            val getResponse = client.send(getRequest, HttpResponse.BodyHandlers.ofString())

            if (getResponse.statusCode() == 200) {
                // Room exists
                val responseBody = getResponse.body()
                logger.info("Get room response: $responseBody")
                
                try {
                    val roomData = objectMapper.readValue(responseBody, Map::class.java)
                    logger.debug("Parsed room data keys: ${roomData.keys}")
                    
                    // Daily.co API returns room directly at top level
                    val room = roomData as? Map<*, *>
                    
                    // Try to get URL from various possible locations
                    var url = room?.get("url") as? String
                    if (url == null) {
                        // Try nested "room" key if exists
                        val nestedRoom = room?.get("room") as? Map<*, *>
                        url = nestedRoom?.get("url") as? String
                    }
                    
                    // Final safety check: ensure URL is never null
                    val finalUrl = if (url == null || url.isBlank()) {
                        logger.warn("Room URL is null in response. Constructing from domain.")
                        constructRoomUrl(roomName)
                    } else {
                        url
                    }
                    
                    return RoomResponse(
                        id = room?.get("id") as? String,
                        name = (room?.get("name") as? String) ?: roomName,
                        url = finalUrl,
                        config = room?.get("config") as? Map<String, Any>
                    )
                } catch (e: Exception) {
                    logger.error("Failed to parse get room response: ${e.message}", e)
                    // Fallback: construct URL
                    return RoomResponse(
                        id = null,
                        name = roomName,
                        url = constructRoomUrl(roomName),
                        config = null
                    )
                }
            } else {
                logger.debug("Room $roomName not found (status ${getResponse.statusCode()}): ${getResponse.body()}")
            }
        } catch (e: Exception) {
            logger.debug("Room $roomName does not exist, will create: ${e.message}")
        }

        // Room doesn't exist, create it
        val exp = Instant.now().epochSecond + (24 * 60 * 60) // 24 hours from now
        val createRequest = CreateRoomRequest(
            name = roomName,
            privacy = "private",
            properties = RoomProperties(
                exp = exp,
                enable_chat = true,
                enable_screenshare = true,
                // Use audio-only cloud recording for AI scribe
                enable_recording = "cloud-audio-only",
                max_participants = maxParticipants
            )
        )

        val requestBody = objectMapper.writeValueAsString(createRequest)
        val createHttpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${dailyProperties.apiUrl}/rooms"))
            .header("Authorization", "Bearer ${dailyProperties.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        logger.debug("Creating room with request body: $requestBody")
        val createResponse = client.send(createHttpRequest, HttpResponse.BodyHandlers.ofString())

        logger.info("Create room response status: ${createResponse.statusCode()}")
        
        if (createResponse.statusCode() in 200..299) {
            val responseBody = createResponse.body()
            logger.info("Create room response body: $responseBody")
            
            try {
                val roomData = objectMapper.readValue(responseBody, Map::class.java)
                logger.info("Parsed room data keys: ${roomData.keys}")
                logger.debug("Full parsed room data: $roomData")
                
                // Daily.co API returns room object directly at top level
                // The response structure is: { "id": "...", "name": "...", "url": "...", ... }
                val room = roomData as? Map<*, *>
                
                if (room == null) {
                    logger.error("Failed to parse room data as Map. Response: $responseBody")
                    val fallbackUrl = constructRoomUrl(roomName)
                    return RoomResponse(id = null, name = roomName, url = fallbackUrl, config = null)
                }
                
                logger.debug("Extracted room object keys: ${room.keys}")
                
                // Try to get URL from various possible locations
                var url = room.get("url") as? String
                if (url == null) {
                    // Try nested "room" key if exists
                    val nestedRoom = room.get("room") as? Map<*, *>
                    url = nestedRoom?.get("url") as? String
                }
                
                logger.info("Room URL from API: $url")
                
                // If still null, construct it
                if (url == null || url.isBlank()) {
                    logger.warn("Room URL is null or blank after creation. Constructing from domain.")
                    url = constructRoomUrl(roomName)
                    logger.info("Constructed room URL: $url")
                }
                
                // Final safety check: ensure URL is never null
                val finalUrl = url ?: constructRoomUrl(roomName)
                
                val roomResponse = RoomResponse(
                    id = room.get("id") as? String,
                    name = (room.get("name") as? String) ?: roomName,
                    url = finalUrl,
                    config = room.get("config") as? Map<String, Any>
                )
                
                logger.info("Returning room response: id=${roomResponse.id}, name=${roomResponse.name}, url=${roomResponse.url}")
                return roomResponse
            } catch (e: Exception) {
                logger.error("Failed to parse room response: ${e.message}", e)
                logger.error("Response body that failed to parse: $responseBody")
                // Fallback: construct URL and return basic response
                val fallbackUrl = constructRoomUrl(roomName)
                logger.warn("Using fallback room URL: $fallbackUrl")
                return RoomResponse(
                    id = null,
                    name = roomName,
                    url = fallbackUrl,
                    config = null
                )
            }
        } else {
            val errorBody = createResponse.body()
            logger.error("Failed to create room: ${createResponse.statusCode()} - $errorBody")
            throw RuntimeException("Failed to create room: ${createResponse.statusCode()} - $errorBody")
        }
    }

    /**
     * Generate a meeting token for joining a room
     */
    fun generateToken(
        roomName: String,
        userId: String,
        userName: String,
        isOwner: Boolean = false,
        expiresInHours: Int = 2
    ): TokenResponse {
        // Validate API key is configured
        if (!isApiKeyConfigured()) {
            logger.error("Daily.co API key is not configured. Cannot generate token.")
            throw RuntimeException("Daily.co API key is not configured. Please set DAILY_API_KEY environment variable.")
        }
        
        logger.info("Generating token for room: $roomName, user: $userName, isOwner: $isOwner")
        val exp = Instant.now().epochSecond + (expiresInHours * 60 * 60)

        val tokenRequest = CreateTokenRequest(
            properties = TokenProperties(
                room_name = roomName,
                user_id = userId,
                user_name = userName,
                is_owner = isOwner,
                exp = exp
            )
        )

        val requestBody = objectMapper.writeValueAsString(tokenRequest)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("${dailyProperties.apiUrl}/meeting-tokens"))
            .header("Authorization", "Bearer ${dailyProperties.apiKey}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() in 200..299) {
            val tokenData = objectMapper.readValue(response.body(), Map::class.java)
            val token = tokenData["token"] as? String
            val id = tokenData["id"] as? String
            return TokenResponse(token = token, id = id)
        } else {
            throw RuntimeException("Failed to generate token: ${response.statusCode()} - ${response.body()}")
        }
    }
}
