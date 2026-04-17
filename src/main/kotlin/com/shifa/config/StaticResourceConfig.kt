
// src/main/kotlin/com/shifa/config/StaticResourceConfig.kt
package com.shifa.config

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.nio.file.Paths

@Configuration
class StaticResourceConfig(
    private val appProperties: AppProperties
) : WebMvcConfigurer {
    
    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // Get storage root from configuration (works with Railway volumes)
        val storageRoot = Paths.get(appProperties.storageRoot).toAbsolutePath().normalize()
        val storagePath = storageRoot.toString().replace("\\", "/")
        
        // Ensure path ends with / for proper file serving
        val basePath = if (storagePath.endsWith("/")) storagePath else "$storagePath/"
        
        // Serve doctor photos: /doctors/{filename} -> {storageRoot}/doctors/{filename}
        registry.addResourceHandler("/doctors/**")
            .addResourceLocations("file:$basePath/doctors/")
            .setCachePeriod(3600) // Cache for 1 hour
        
        // Serve patient photos: /patients/{filename} -> {storageRoot}/patients/{filename}
        registry.addResourceHandler("/patients/**")
            .addResourceLocations("file:$basePath/patients/")
            .setCachePeriod(3600)
        
        // Serve patient documents: /patientdocuments/{patientId}/{filename} -> {storageRoot}/patientdocuments/{patientId}/{filename}
        registry.addResourceHandler("/patientdocuments/**")
            .addResourceLocations("file:$basePath/patientdocuments/")
            .setCachePeriod(3600)
            .resourceChain(true)
            .addResolver(org.springframework.web.servlet.resource.PathResourceResolver())
        
        // Serve certificates: /certificates/{filename} -> {storageRoot}/certificates/{filename}
        registry.addResourceHandler("/certificates/**")
            .addResourceLocations("file:$basePath/certificates/")
            .setCachePeriod(3600)
        
        // Serve chat attachments: /chat-attachments/{filename} -> {storageRoot}/chat-attachments/{filename}
        registry.addResourceHandler("/chat-attachments/**")
            .addResourceLocations("file:$basePath/chat-attachments/")
            .setCachePeriod(3600)
        
        // Legacy: Serve /photos/** for backward compatibility
        registry.addResourceHandler("/photos/**")
            .addResourceLocations("file:$basePath/")
            .setCachePeriod(3600)
    }
}
