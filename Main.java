package com.twitter;

import javax.swing.*;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Main application class for posting tweets to X (Twitter)
 * 
 * Before running, make sure to set your Twitter API credentials:
 * 1. Go to https://developer.twitter.com/en/portal/dashboard
 * 2. Create a new app or use an existing one
 * 3. Get your API Key, API Secret, Access Token, and Access Token Secret
 * 4. Update the credentials in this file or use environment variables
 */
public class Main {
    
    // TODO: Replace these with your actual Twitter API credentials
    // You can get them from https://developer.twitter.com/en/portal/dashboard
    private static final String API_KEY = "MR7E8GDIUaO0ocUTnMqpT8mrD";
    private static final String API_SECRET = "voTtM97wrOVvtZJfaFMxjpJfRBencAYBDeAZg7mifptvIuGSB4";
    private static final String ACCESS_TOKEN = "2658030342-gf20hdBs9t1qZwUuew3oO2IizKKEjJV0gmyWZAl";
    private static final String ACCESS_TOKEN_SECRET = "TwsoJdj8mQG20FQibqgY2qAQ4puwh13mT9HwO86dKK7O5";
    
    // Hardcoded image path - change this to your image file path
    // If the image doesn't exist, the app will post text-only tweets
    private static final String IMAGE_PATH = "C:\\Users\\hp\\Desktop\\JM.jpg";
    
    public static void main(String[] args) {
        // Check if credentials are set
        if (API_KEY.equals("YOUR_API_KEY") || 
            API_SECRET.equals("YOUR_API_SECRET") ||
            ACCESS_TOKEN.equals("YOUR_ACCESS_TOKEN") ||
            ACCESS_TOKEN_SECRET.equals("YOUR_ACCESS_TOKEN_SECRET")) {
            
            System.out.println("⚠️  WARNING: Please set your Twitter API credentials in Main.java");
            System.out.println("Get your credentials from: https://developer.twitter.com/en/portal/dashboard");
            System.out.println();
            System.out.println("You need:");
            System.out.println("  - API Key");
            System.out.println("  - API Secret");
            System.out.println("  - Access Token");
            System.out.println("  - Access Token Secret");
            System.out.println();
            System.out.println("Or use environment variables:");
            System.out.println("  - TWITTER_API_KEY");
            System.out.println("  - TWITTER_API_SECRET");
            System.out.println("  - TWITTER_ACCESS_TOKEN");
            System.out.println("  - TWITTER_ACCESS_TOKEN_SECRET");
            return;
        }
        
        // Try to get credentials from environment variables first
        String apiKey = getEnvOrValue("TWITTER_API_KEY", API_KEY);
        String apiSecret = getEnvOrValue("TWITTER_API_SECRET", API_SECRET);
        String accessToken = getEnvOrValue("TWITTER_ACCESS_TOKEN", ACCESS_TOKEN);
        String accessTokenSecret = getEnvOrValue("TWITTER_ACCESS_TOKEN_SECRET", ACCESS_TOKEN_SECRET);
        
        // Create XPoster instance
        XPoster poster = new XPoster(apiKey, apiSecret, accessToken, accessTokenSecret);
        
        // Check if image exists
        boolean imageExists = checkImageExists(IMAGE_PATH);
        
        // Launch GUI
        SwingUtilities.invokeLater(() -> {
            try {
                // Set system look and feel for better appearance
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                // Use default look and feel if system L&F fails
            }
            
            TwitterGUI gui = new TwitterGUI(poster, IMAGE_PATH, imageExists);
            gui.setVisible(true);
        });
    }
    
    /**
     * Gets value from environment variable or uses the provided default
     */
    private static String getEnvOrValue(String envVar, String defaultValue) {
        String envValue = System.getenv(envVar);
        return (envValue != null && !envValue.isEmpty()) ? envValue : defaultValue;
    }
    
    /**
     * Checks if the image file exists at the given path
     */
    private static boolean checkImageExists(String imagePath) {
        try {
            // Clean the path similar to XPoster
            String cleanedPath = imagePath.trim()
                    .replace("\u200E", "")
                    .replace("\u200F", "")
                    .replaceAll("[\\p{Cf}]", "")
                    .trim();
            
            if (cleanedPath.startsWith("\"") && cleanedPath.endsWith("\"")) {
                cleanedPath = cleanedPath.substring(1, cleanedPath.length() - 1);
            }
            
            return Files.exists(Paths.get(cleanedPath).normalize().toAbsolutePath());
        } catch (Exception e) {
            return false;
        }
    }
}

