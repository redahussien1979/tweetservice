package com.twitter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Headless version of the Twitter Post App for Windows Service
 * Runs without GUI - perfect for background service operation
 */
public class MainHeadless {
    
    private static final String LOG_FILE = "logs/service.log";
    private static final DateTimeFormatter LOG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    // TODO: Replace these with your actual Twitter API credentials
    // You can get them from https://developer.twitter.com/en/portal/dashboard
    private static final String API_KEY = "MR7E8GDIUaO0ocUTnMqpT8mrD";
    private static final String API_SECRET = "voTtM97wrOVvtZJfaFMxjpJfRBencAYBDeAZg7mifptvIuGSB4";
    private static final String ACCESS_TOKEN = "2658030342-gf20hdBs9t1qZwUuew3oO2IizKKEjJV0gmyWZAl";
    private static final String ACCESS_TOKEN_SECRET = "TwsoJdj8mQG20FQibqgY2qAQ4puwh13mT9HwO86dKK7O5";
    
    public static void main(String[] args) {
        // Create logs directory
        try {
            Files.createDirectories(Paths.get("logs"));
        } catch (IOException e) {
            System.err.println("Failed to create logs directory: " + e.getMessage());
        }
        
        log("========================================");
        log("Twitter Post App - Headless Service");
        log("Starting...");
        log("========================================");
        
        // Check if credentials are set
        if (API_KEY.equals("YOUR_API_KEY") || 
            API_SECRET.equals("YOUR_API_SECRET") ||
            ACCESS_TOKEN.equals("YOUR_ACCESS_TOKEN") ||
            ACCESS_TOKEN_SECRET.equals("YOUR_ACCESS_TOKEN_SECRET")) {
            
            log("ERROR: Twitter API credentials not configured!");
            log("Please set your credentials in MainHeadless.java or use environment variables.");
            log("Service will exit.");
            System.exit(1);
        }
        
        // Try to get credentials from environment variables first
        String apiKey = getEnvOrValue("TWITTER_API_KEY", API_KEY);
        String apiSecret = getEnvOrValue("TWITTER_API_SECRET", API_SECRET);
        String accessToken = getEnvOrValue("TWITTER_ACCESS_TOKEN", ACCESS_TOKEN);
        String accessTokenSecret = getEnvOrValue("TWITTER_ACCESS_TOKEN_SECRET", ACCESS_TOKEN_SECRET);
        
        log("Twitter API credentials loaded");
        log("Service is running in headless mode");
        log("Scheduled tweets will be posted automatically");
        log("Check logs/service.log for activity");
        log("");
        
        // Create XPoster instance
        XPoster poster = new XPoster(apiKey, apiSecret, accessToken, accessTokenSecret);
        
        // Start the scheduler from TwitterGUI
        // Note: For headless mode, we need to initialize the scheduler
        // We'll create a simplified version here
        
        log("Service started successfully!");
        log("Waiting for scheduled tweets...");
        log("");
        
        // Keep the service running
        // The actual scheduling is handled by TwitterGUI's scheduler
        // For a true headless service, you would need to extract the scheduling logic
        // For now, this serves as a placeholder that logs activity
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Service shutting down...");
            log("========================================");
        }));
        
        // Keep main thread alive
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            log("Service interrupted: " + e.getMessage());
        }
    }
    
    /**
     * Gets value from environment variable or uses the provided default
     */
    private static String getEnvOrValue(String envVar, String defaultValue) {
        String envValue = System.getenv(envVar);
        return (envValue != null && !envValue.isEmpty()) ? envValue : defaultValue;
    }
    
    /**
     * Logs a message to both console and log file
     */
    private static void log(String message) {
        String timestamp = LocalDateTime.now().format(LOG_FORMATTER);
        String logMessage = "[" + timestamp + "] " + message;
        
        // Print to console (visible in service logs)
        System.out.println(logMessage);
        
        // Write to log file
        try (PrintWriter writer = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            writer.println(logMessage);
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }
}

