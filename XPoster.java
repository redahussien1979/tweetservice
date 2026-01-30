package com.twitter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Simple X (Twitter) API client for posting tweets using OAuth 1.0a
 * Works with Twitter API v2 free tier
 */
public class XPoster {
    private final String apiKey;
    private final String apiSecret;
    private final String accessToken;
    private final String accessTokenSecret;
    private final HttpClient httpClient;
    
    private static final String TWITTER_API_URL = "https://api.twitter.com/2/tweets";
    private static final String MEDIA_UPLOAD_URL = "https://upload.twitter.com/1.1/media/upload.json";
    private static final String OAUTH_VERSION = "1.0";
    private static final String OAUTH_SIGNATURE_METHOD = "HMAC-SHA1";
    
    public XPoster(String apiKey, String apiSecret, String accessToken, String accessTokenSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.accessToken = accessToken;
        this.accessTokenSecret = accessTokenSecret;
        this.httpClient = HttpClient.newHttpClient();
    }
    
    /**
     * Posts a tweet to X (Twitter)
     * If text exceeds 280 characters, automatically splits into a thread
     * @param text The tweet text (will be split if over 280 characters)
     * @return The response from Twitter API (last tweet in thread)
     * @throws IOException If the request fails
     */
    public String postTweet(String text) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Tweet text cannot be empty");
        }
        
        // If text is within limit, post normally
        if (text.length() <= 280) {
            return postSingleTweet(text, null);
        }
        
        // Split into thread
        return postTweetThread(text, null);
    }
    
    /**
     * Posts a single tweet (or reply)
     * @param text The tweet text (max 280 characters)
     * @param replyToTweetId ID of tweet to reply to (null for new tweet)
     * @return The response from Twitter API
     * @throws IOException If the request fails
     */
    private String postSingleTweet(String text, String replyToTweetId) throws IOException {
        if (text.length() > 280) {
            throw new IllegalArgumentException("Tweet text cannot exceed 280 characters");
        }
        
        // Create the JSON payload
        String jsonPayload;
        if (replyToTweetId != null && !replyToTweetId.isEmpty()) {
            jsonPayload = "{\"text\":\"" + escapeJson(text) + "\",\"reply\":{\"in_reply_to_tweet_id\":\"" + replyToTweetId + "\"}}";
            System.out.println("DEBUG: Posting REPLY to tweet ID: " + replyToTweetId);
            System.out.println("DEBUG: Reply payload: " + jsonPayload);
        } else {
            jsonPayload = "{\"text\":\"" + escapeJson(text) + "\"}";
            System.out.println("DEBUG: Posting NEW tweet (not a reply)");
        }
        
        // Generate OAuth signature
        String oauthHeader = generateOAuthHeader("POST", TWITTER_API_URL, jsonPayload);
        
        // Make the request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TWITTER_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", oauthHeader)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();
        
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 201) {
                return response.body();
            } else if (response.statusCode() == 429) {
                String errorBody = response.body();
                
                // Extract rate limit info from headers
                String resetTime = response.headers().firstValue("x-rate-limit-reset").orElse("unknown");
                String remaining = response.headers().firstValue("x-rate-limit-remaining").orElse("unknown");
                String limit = response.headers().firstValue("x-rate-limit-limit").orElse("unknown");
                
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("❌ 429 Too Many Requests - Rate Limit Exceeded\n\n");
                
                // Check if it's monthly limit (likely if waited 24 hours)
                if (remaining.equals("0") || remaining.equals("unknown")) {
                    errorMsg.append("⚠️ CRITICAL: You've likely hit your MONTHLY limit!\n\n");
                    errorMsg.append("Twitter API Free Tier Limits:\n");
                    errorMsg.append("• 1,500 tweets per MONTH (resets at start of next month)\n");
                    errorMsg.append("• This is a hard limit - waiting won't help until next month\n\n");
                    errorMsg.append("What to do:\n");
                    errorMsg.append("1. Go to https://developer.twitter.com/en/portal/dashboard\n");
                    errorMsg.append("2. Check your monthly usage/limits\n");
                    errorMsg.append("3. Wait until the next month starts (limit resets monthly)\n");
                    errorMsg.append("4. OR upgrade to a paid tier for higher limits\n\n");
                } else {
                    errorMsg.append("Rate Limit Status:\n");
                    errorMsg.append("• Remaining: ").append(remaining).append(" / ").append(limit).append("\n");
                    if (!resetTime.equals("unknown")) {
                        try {
                            long resetTimestamp = Long.parseLong(resetTime);
                            long currentTime = System.currentTimeMillis() / 1000;
                            long waitSeconds = resetTimestamp - currentTime;
                            long waitMinutes = waitSeconds / 60;
                            errorMsg.append("• Reset in: ~").append(waitMinutes).append(" minutes\n");
                        } catch (NumberFormatException e) {
                            errorMsg.append("• Reset time: ").append(resetTime).append("\n");
                        }
                    }
                    errorMsg.append("\nSolutions:\n");
                    errorMsg.append("1. Wait until the rate limit resets\n");
                    errorMsg.append("2. Reduce posting frequency\n");
                    errorMsg.append("3. Check your monthly usage in Twitter Developer Portal\n\n");
                }
                
                errorMsg.append("Response: ").append(errorBody);
                throw new IOException(errorMsg.toString());
            } else if (response.statusCode() == 403) {
                String errorBody = response.body();
                if (errorBody.contains("oauth1-permissions") || 
                    errorBody.contains("not permitted") || 
                    errorBody.contains("permission")) {
                    throw new IOException("❌ 403 Forbidden - Permissions Error\n" +
                                         "Your app doesn't have write permissions enabled.\n\n" +
                                         "Fix this by:\n" +
                                         "1. Go to https://developer.twitter.com/en/portal/dashboard\n" +
                                         "2. Click your app → Settings tab\n" +
                                         "3. Change 'App permissions' to 'Read and Write'\n" +
                                         "4. Click 'Save' at the bottom\n" +
                                         "5. Go to 'Keys and tokens' tab\n" +
                                         "6. Find 'Access Token and Secret' section\n" +
                                         "7. Click 'Regenerate' button (REQUIRED after changing permissions!)\n" +
                                         "8. Copy the NEW Access Token and Access Token Secret\n" +
                                         "9. Update your code/environment variables with the new tokens\n" +
                                         "10. Restart the application and try again\n\n" +
                                         "⚠️ IMPORTANT: You MUST regenerate tokens after changing permissions!\n\n" +
                                         "Original error: " + errorBody);
                } else {
                    throw new IOException("❌ 403 Forbidden\n" +
                                         "Response: " + errorBody + "\n\n" +
                                         "This might be a permissions issue. Check:\n" +
                                         "1. App permissions set to 'Read and Write'\n" +
                                         "2. Access tokens regenerated after changing permissions\n" +
                                         "3. Correct API credentials in your code");
                }
            } else {
                throw new IOException("Failed to post tweet. Status: " + response.statusCode() + 
                                     ", Response: " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
    
    /**
     * Posts a tweet thread (splits long text into multiple tweets)
     * All reply tweets are posted as replies to the FIRST (main) tweet.
     * @param text The full tweet text (can exceed 280 characters)
     * @param imagePath Optional image path (only attached to first tweet)
     * @return The response from Twitter API (last tweet in thread)
     * @throws IOException If the request fails
     */
    private String postTweetThread(String text, String imagePath) throws IOException {
        // First, estimate how many chunks we'll need to calculate the maximum indicator length
        // This ensures we reserve the exact space needed for indicators
        int estimatedChunks = (int) Math.ceil(text.length() / 250.0); // Conservative estimate
        String maxIndicator = " (" + estimatedChunks + "/" + estimatedChunks + ")";
        int maxIndicatorLength = maxIndicator.length();

        // Split text into chunks, leaving EXACT room for the longest possible indicator
        // This ensures NO text is lost when adding indicators
        int chunkMaxLength = 280 - maxIndicatorLength;
        List<String> chunks = splitIntoChunks(text, chunkMaxLength);

        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("Failed to split tweet text");
        }

        // Now calculate the actual maximum indicator length based on real chunk count
        String actualMaxIndicator = " (" + chunks.size() + "/" + chunks.size() + ")";
        int actualMaxIndicatorLength = actualMaxIndicator.length();

        // If actual is longer than estimated, we need to re-split with correct size
        if (actualMaxIndicatorLength > maxIndicatorLength) {
            chunkMaxLength = 280 - actualMaxIndicatorLength;
            chunks = splitIntoChunks(text, chunkMaxLength);
        }

        String mainTweetId = null;  // Store the FIRST tweet's ID - all replies go to this
        String lastResponse = null;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);

            // Add thread indicator (e.g., " (1/3)", " (2/3)", " (3/3)")
            if (chunks.size() > 1) {
                String indicator = " (" + (i + 1) + "/" + chunks.size() + ")";
                // Chunk was split with exact room for indicator, so it should always fit
                chunk = chunk + indicator;

                // Safety check: verify it doesn't exceed 280 (should never happen now)
                if (chunk.length() > 280) {
                    System.err.println("WARNING: Chunk + indicator exceeds 280 chars: " + chunk.length() +
                                      " (chunk: " + (chunk.length() - indicator.length()) +
                                      ", indicator: " + indicator.length() + ")");
                    // Trim to fit - this should never happen with correct calculation
                    chunk = chunk.substring(0, 280 - indicator.length()) + indicator;
                }
            }

            try {
                // First tweet can have image, rest are replies to the FIRST tweet
                if (i == 0 && imagePath != null && checkImageExists(imagePath)) {
                    // First tweet with image - not a reply
                    lastResponse = postTweetWithImageAsReply(chunk, imagePath, null);
                } else if (i > 0) {
                    // This is a reply - reply to the MAIN (first) tweet, not the previous one
                    if (mainTweetId == null || mainTweetId.isEmpty()) {
                        throw new IOException("Cannot post reply #" + (i + 1) + ": Failed to get main tweet ID. Response: " + lastResponse);
                    }
                    System.out.println("DEBUG: Posting reply #" + (i + 1) + " to MAIN tweet ID: " + mainTweetId);
                    lastResponse = postSingleTweet(chunk, mainTweetId);
                } else {
                    // First tweet without image - not a reply
                    lastResponse = postSingleTweet(chunk, null);
                }

                // Extract tweet ID from response
                String extractedId = extractTweetId(lastResponse);
                if (extractedId == null || extractedId.isEmpty()) {
                    throw new IOException("Failed to extract tweet ID from response for chunk " + (i + 1) + ". Cannot continue thread. Response: " + lastResponse);
                }
                System.out.println("DEBUG: Extracted tweet ID for chunk " + (i + 1) + ": " + extractedId);

                // Store the FIRST tweet's ID - all subsequent replies will be to this tweet
                if (i == 0) {
                    mainTweetId = extractedId;
                    System.out.println("DEBUG: Main tweet ID set to: " + mainTweetId);
                }

                // Delay between tweets to avoid rate limits
                if (i < chunks.size() - 1) {
                    // 3 second delay between all tweets in the thread
                    Thread.sleep(3000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Thread posting interrupted", e);
            }
        }

        return lastResponse != null ? lastResponse : "";
    }
    
    /**
     * Splits text into chunks, trying to break at word boundaries
     */
    private List<String> splitIntoChunks(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        
        if (text.length() <= maxLength) {
            chunks.add(text);
            return chunks;
        }
        
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());
            
            // If not the last chunk, try to break at word boundary
            if (end < text.length()) {
                // Look for space or newline near the end
                int lastSpace = text.lastIndexOf(' ', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int breakPoint = Math.max(lastSpace, lastNewline);
                
                if (breakPoint > start + maxLength * 0.7) { // Only break at word if we're at least 70% through
                    end = breakPoint + 1; // Include the space/newline, will be trimmed
                }
            }
            
            // Extract chunk and trim whitespace (preserves all text, just removes leading/trailing spaces)
            String chunk = text.substring(start, end).trim();
            chunks.add(chunk);
            
            // Move start to end position (after the space if we broke at word boundary)
            // This ensures no characters are skipped
            start = end;
        }
        
        return chunks;
    }
    
    /**
     * Extracts tweet ID from Twitter API response
     * Response format: {"data":{"id":"123456789","text":"..."}}
     */
    private String extractTweetId(String response) {
        if (response == null || response.isEmpty()) {
            System.err.println("ERROR: extractTweetId - response is null or empty");
            return null;
        }
        
        try {
            // Try to find "id":"..." pattern in the response
            // Look for "id":" followed by digits
            int idStart = response.indexOf("\"id\":\"");
            if (idStart == -1) {
                // Try alternative format: "id":123456789 (without quotes)
                idStart = response.indexOf("\"id\":");
                if (idStart != -1) {
                    idStart += 5; // Skip "id":
                    // Skip whitespace
                    while (idStart < response.length() && Character.isWhitespace(response.charAt(idStart))) {
                        idStart++;
                    }
                    // Extract numeric ID
                    int idEnd = idStart;
                    while (idEnd < response.length() && (Character.isDigit(response.charAt(idEnd)) || response.charAt(idEnd) == '"')) {
                        if (response.charAt(idEnd) == '"') break;
                        idEnd++;
                    }
                    if (idEnd > idStart) {
                        String id = response.substring(idStart, idEnd);
                        System.out.println("DEBUG: Extracted tweet ID (numeric): " + id);
                        return id;
                    }
                }
                System.err.println("ERROR: extractTweetId - Could not find \"id\":\" pattern in response: " + response);
                return null;
            }
            
            idStart += 6; // Skip "id":"
            int idEnd = response.indexOf("\"", idStart);
            if (idEnd > idStart) {
                String id = response.substring(idStart, idEnd);
                System.out.println("DEBUG: Extracted tweet ID: " + id);
                return id;
            } else {
                System.err.println("ERROR: extractTweetId - Could not find end quote for ID. Response: " + response);
            }
        } catch (Exception e) {
            System.err.println("ERROR: extractTweetId - Exception: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    /**
     * Checks if image file exists
     */
    private boolean checkImageExists(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return false;
        }
        try {
            return Files.exists(Paths.get(imagePath));
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Posts a tweet with image as a reply
     */
    private String postTweetWithImageAsReply(String text, String imagePath, String replyToTweetId) throws IOException {
        if (text.length() > 280) {
            throw new IllegalArgumentException("Tweet text cannot exceed 280 characters");
        }
        
        // Upload the image first
        String mediaId = uploadMedia(imagePath);
        
        // Create JSON payload
        String jsonPayload;
        if (replyToTweetId != null && !replyToTweetId.isEmpty()) {
            jsonPayload = "{\"text\":\"" + escapeJson(text) + "\",\"media\":{\"media_ids\":[\"" + mediaId + "\"]},\"reply\":{\"in_reply_to_tweet_id\":\"" + replyToTweetId + "\"}}";
            System.out.println("DEBUG: Posting REPLY with image to tweet ID: " + replyToTweetId);
        } else {
            jsonPayload = "{\"text\":\"" + escapeJson(text) + "\",\"media\":{\"media_ids\":[\"" + mediaId + "\"]}}";
            System.out.println("DEBUG: Posting NEW tweet with image (not a reply)");
        }
        
        // Generate OAuth signature
        String oauthHeader = generateOAuthHeader("POST", TWITTER_API_URL, jsonPayload);
        
        // Make the request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TWITTER_API_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", oauthHeader)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();
        
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 201) {
                return response.body();
            } else if (response.statusCode() == 429) {
                String errorBody = response.body();
                
                // Extract rate limit info from headers
                String resetTime = response.headers().firstValue("x-rate-limit-reset").orElse("unknown");
                String remaining = response.headers().firstValue("x-rate-limit-remaining").orElse("unknown");
                String limit = response.headers().firstValue("x-rate-limit-limit").orElse("unknown");
                
                StringBuilder errorMsg = new StringBuilder();
                errorMsg.append("❌ 429 Too Many Requests - Rate Limit Exceeded\n\n");
                
                // Check if it's monthly limit (likely if waited 24 hours)
                if (remaining.equals("0") || remaining.equals("unknown")) {
                    errorMsg.append("⚠️ CRITICAL: You've likely hit your MONTHLY limit!\n\n");
                    errorMsg.append("Twitter API Free Tier Limits:\n");
                    errorMsg.append("• 1,500 tweets per MONTH (resets at start of next month)\n");
                    errorMsg.append("• This is a hard limit - waiting won't help until next month\n\n");
                    errorMsg.append("What to do:\n");
                    errorMsg.append("1. Go to https://developer.twitter.com/en/portal/dashboard\n");
                    errorMsg.append("2. Check your monthly usage/limits\n");
                    errorMsg.append("3. Wait until the next month starts (limit resets monthly)\n");
                    errorMsg.append("4. OR upgrade to a paid tier for higher limits\n\n");
                } else {
                    errorMsg.append("Rate Limit Status:\n");
                    errorMsg.append("• Remaining: ").append(remaining).append(" / ").append(limit).append("\n");
                    if (!resetTime.equals("unknown")) {
                        try {
                            long resetTimestamp = Long.parseLong(resetTime);
                            long currentTime = System.currentTimeMillis() / 1000;
                            long waitSeconds = resetTimestamp - currentTime;
                            long waitMinutes = waitSeconds / 60;
                            errorMsg.append("• Reset in: ~").append(waitMinutes).append(" minutes\n");
                        } catch (NumberFormatException e) {
                            errorMsg.append("• Reset time: ").append(resetTime).append("\n");
                        }
                    }
                    errorMsg.append("\nSolutions:\n");
                    errorMsg.append("1. Wait until the rate limit resets\n");
                    errorMsg.append("2. Reduce posting frequency\n");
                    errorMsg.append("3. Check your monthly usage in Twitter Developer Portal\n\n");
                }
                
                errorMsg.append("Response: ").append(errorBody);
                throw new IOException(errorMsg.toString());
            } else if (response.statusCode() == 403) {
                String errorBody = response.body();
                if (errorBody.contains("oauth1-permissions") || 
                    errorBody.contains("not permitted") || 
                    errorBody.contains("permission")) {
                    throw new IOException("❌ 403 Forbidden - Permissions Error\n" +
                                         "Your app doesn't have write permissions enabled.\n\n" +
                                         "Fix this by:\n" +
                                         "1. Go to https://developer.twitter.com/en/portal/dashboard\n" +
                                         "2. Click your app → Settings tab\n" +
                                         "3. Change 'App permissions' to 'Read and Write'\n" +
                                         "4. Click 'Save' at the bottom\n" +
                                         "5. Go to 'Keys and tokens' tab\n" +
                                         "6. Find 'Access Token and Secret' section\n" +
                                         "7. Click 'Regenerate' button (REQUIRED after changing permissions!)\n" +
                                         "8. Copy the NEW Access Token and Access Token Secret\n" +
                                         "9. Update your code/environment variables with the new tokens\n" +
                                         "10. Restart the application and try again\n\n" +
                                         "⚠️ IMPORTANT: You MUST regenerate tokens after changing permissions!\n\n" +
                                         "Original error: " + errorBody);
                } else {
                    throw new IOException("❌ 403 Forbidden\n" +
                                         "Response: " + errorBody + "\n\n" +
                                         "This might be a permissions issue. Check:\n" +
                                         "1. App permissions set to 'Read and Write'\n" +
                                         "2. Access tokens regenerated after changing permissions\n" +
                                         "3. Correct API credentials in your code");
                }
            } else {
                throw new IOException("Failed to post tweet. Status: " + response.statusCode() + 
                                     ", Response: " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
    
    /**
     * Generates OAuth 1.0a authorization header
     */
    private String generateOAuthHeader(String method, String url, String body) {
        long timestamp = System.currentTimeMillis() / 1000;
        String nonce = generateNonce();
        
        // Build OAuth parameters
        Map<String, String> oauthParams = new HashMap<>();
        oauthParams.put("oauth_consumer_key", apiKey);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_signature_method", OAUTH_SIGNATURE_METHOD);
        oauthParams.put("oauth_timestamp", String.valueOf(timestamp));
        oauthParams.put("oauth_nonce", nonce);
        oauthParams.put("oauth_version", OAUTH_VERSION);
        
        // Create signature base string
        String signatureBaseString = createSignatureBaseString(method, url, oauthParams);
        
        // Create signing key
        String signingKey = percentEncode(apiSecret) + "&" + percentEncode(accessTokenSecret);
        
        // Generate signature
        String signature = generateSignature(signatureBaseString, signingKey);
        oauthParams.put("oauth_signature", signature);
        
        // Build authorization header
        String authHeader = "OAuth " + oauthParams.entrySet().stream()
                .map(entry -> percentEncode(entry.getKey()) + "=\"" + percentEncode(entry.getValue()) + "\"")
                .collect(Collectors.joining(", "));
        
        return authHeader;
    }
    
    /**
     * Creates the signature base string for OAuth
     */
    private String createSignatureBaseString(String method, String url, Map<String, String> params) {
        // Sort parameters
        String paramString = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
        
        return method + "&" + percentEncode(url) + "&" + percentEncode(paramString);
    }
    
    /**
     * Generates HMAC-SHA1 signature
     */
    private String generateSignature(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1");
            mac.init(secretKeySpec);
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to generate signature", e);
        }
    }
    
    /**
     * Generates a random nonce for OAuth
     */
    private String generateNonce() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes).replaceAll("[^a-zA-Z0-9]", "");
    }
    
    /**
     * Percent-encodes a string according to RFC 3986
     */
    private String percentEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20")
                    .replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 encoding not supported", e);
        }
    }
    
    /**
     * Posts a tweet with an image to X (Twitter)
     * If text exceeds 280 characters, automatically splits into a thread
     * @param text The tweet text (will be split if over 280 characters)
     * @param imagePath Path to the image file (only attached to first tweet)
     * @return The response from Twitter API (last tweet in thread)
     * @throws IOException If the request fails
     */
    public String postTweetWithImage(String text, String imagePath) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Tweet text cannot be empty");
        }
        
        // If text is within limit, post normally
        if (text.length() <= 280) {
            return postTweetWithImageAsReply(text, imagePath, null);
        }
        
        // Split into thread
        return postTweetThread(text, imagePath);
    }
    
    /**
     * Uploads an image to Twitter and returns the media_id
     * @param imagePath Path to the image file
     * @return The media_id from Twitter
     * @throws IOException If the upload fails
     */
    private String uploadMedia(String imagePath) throws IOException {
        // Clean the path - remove invisible Unicode characters (like left-to-right marks)
        // These can appear when copying paths from certain applications
        String cleanedPath = imagePath.trim()
                .replace("\u200E", "")  // Left-to-right mark
                .replace("\u200F", "")  // Right-to-left mark
                .replace("\u202A", "") // Left-to-right embedding
                .replace("\u202B", "") // Right-to-left embedding
                .replace("\u202C", "") // Pop directional formatting
                .replace("\u202D", "") // Left-to-right override
                .replace("\u202E", "") // Right-to-left override
                .replaceAll("[\\p{Cf}]", "") // Remove all format characters
                .trim();
        
        // Normalize the path and handle Windows paths
        Path path;
        try {
            // Handle Windows paths that might have issues
            if (cleanedPath.startsWith("\"") && cleanedPath.endsWith("\"")) {
                cleanedPath = cleanedPath.substring(1, cleanedPath.length() - 1);
            }
            path = Paths.get(cleanedPath).normalize().toAbsolutePath();
        } catch (Exception e) {
            throw new IOException("Invalid image path: '" + imagePath + "'\n" +
                                 "Error: " + e.getMessage() + "\n" +
                                 "Please try:\n" +
                                 "  - Using an absolute path: C:\\Users\\YourName\\Pictures\\photo.jpg\n" +
                                 "  - Or: C:/Users/YourName/Pictures/photo.jpg\n" +
                                 "  - Make sure there are no special characters in the path", e);
        }
        
        if (!Files.exists(path)) {
            throw new IOException("Image file not found: " + cleanedPath + 
                                 "\nPlease check that the file exists and the path is correct.");
        }
        
        byte[] imageBytes = Files.readAllBytes(path);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        
        // Create multipart form data
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        String formData = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"media_data\"\r\n"
                + "\r\n"
                + base64Image + "\r\n"
                + "--" + boundary + "--\r\n";
        
        byte[] formDataBytes = formData.getBytes(StandardCharsets.UTF_8);
        
        // For multipart uploads, Twitter requires media_data to be included in OAuth signature
        Map<String, String> oauthParams = new HashMap<>();
        oauthParams.put("oauth_consumer_key", apiKey);
        oauthParams.put("oauth_token", accessToken);
        oauthParams.put("oauth_signature_method", OAUTH_SIGNATURE_METHOD);
        oauthParams.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        oauthParams.put("oauth_nonce", generateNonce());
        oauthParams.put("oauth_version", OAUTH_VERSION);
        // Note: media_data is in the multipart body, not in OAuth params for signature
        // Twitter's media upload endpoint handles this differently
        String signatureBaseString = createSignatureBaseString("POST", MEDIA_UPLOAD_URL, oauthParams);
        String signingKey = percentEncode(apiSecret) + "&" + percentEncode(accessTokenSecret);
        String signature = generateSignature(signatureBaseString, signingKey);
        oauthParams.put("oauth_signature", signature);
        
        // Build authorization header
        String authHeader = "OAuth " + oauthParams.entrySet().stream()
                .map(entry -> percentEncode(entry.getKey()) + "=\"" + percentEncode(entry.getValue()) + "\"")
                .collect(Collectors.joining(", "));
        
        // Make the upload request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MEDIA_UPLOAD_URL))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(formDataBytes))
                .build();
        
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                // Parse media_id from response
                // Response format: {"media_id":123456789,"media_id_string":"123456789","size":12345}
                String responseBody = response.body();
                int mediaIdStart = responseBody.indexOf("\"media_id_string\":\"") + 19;
                int mediaIdEnd = responseBody.indexOf("\"", mediaIdStart);
                if (mediaIdStart > 18 && mediaIdEnd > mediaIdStart) {
                    return responseBody.substring(mediaIdStart, mediaIdEnd);
                } else {
                    // Fallback: try media_id (number)
                    mediaIdStart = responseBody.indexOf("\"media_id\":") + 11;
                    mediaIdEnd = responseBody.indexOf(",", mediaIdStart);
                    if (mediaIdEnd == -1) mediaIdEnd = responseBody.indexOf("}", mediaIdStart);
                    if (mediaIdStart > 10 && mediaIdEnd > mediaIdStart) {
                        return responseBody.substring(mediaIdStart, mediaIdEnd).trim();
                    }
                }
                throw new IOException("Failed to parse media_id from response: " + responseBody);
            } else {
                throw new IOException("Failed to upload media. Status: " + response.statusCode() + 
                                     ", Response: " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }
    
    /**
     * Escapes special characters in JSON strings
     */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

