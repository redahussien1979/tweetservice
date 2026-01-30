package com.twitter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Headless service version - runs without GUI for Windows Service
 */
public class MainService {
    
    private static final String LOG_FILE = "logs/service.log";
    private static final DateTimeFormatter LOG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long DELAY_BETWEEN_PASSED_TWEETS_MINUTES = 30; // 30 minutes gap between passed tweets
    private static ScheduledExecutorService scheduler;
    private static List<ScheduledTweet> scheduledTweets;
    private static XPoster poster;
    private static Set<String> queuedTweetIds = new HashSet<>(); // Track tweets queued for delayed posting
    
    // TODO: Replace these with your actual Twitter API credentials
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
        log("Twitter Post App - Service Mode");
        log("Starting...");
        log("========================================");
        
        // Check if credentials are set
        if (API_KEY.equals("YOUR_API_KEY") || 
            API_SECRET.equals("YOUR_API_SECRET") ||
            ACCESS_TOKEN.equals("YOUR_ACCESS_TOKEN") ||
            ACCESS_TOKEN_SECRET.equals("YOUR_ACCESS_TOKEN_SECRET")) {
            
            log("ERROR: Twitter API credentials not configured!");
            log("Please set your credentials in MainService.java");
            System.exit(1);
        }
        
        // Get credentials from environment or use defaults
        String apiKey = getEnvOrValue("TWITTER_API_KEY", API_KEY);
        String apiSecret = getEnvOrValue("TWITTER_API_SECRET", API_SECRET);
        String accessToken = getEnvOrValue("TWITTER_ACCESS_TOKEN", ACCESS_TOKEN);
        String accessTokenSecret = getEnvOrValue("TWITTER_ACCESS_TOKEN_SECRET", ACCESS_TOKEN_SECRET);
        
        log("Twitter API credentials loaded");
        log("Service running in headless mode (no GUI)");
        log("");
        
        // Create XPoster instance
        poster = new XPoster(apiKey, apiSecret, accessToken, accessTokenSecret);
        
        // Load scheduled tweets from file (shared with GUI)
        scheduledTweets = ScheduleStorage.load();
        log("Loaded " + scheduledTweets.size() + " scheduled tweet(s) from file");
        
        // Start scheduler
        startScheduler();
        
        log("Service started successfully!");
        log("Scheduler is running - checking for scheduled tweets every 30 seconds");
        log("Scheduled tweets will be posted automatically");
        log("");
        log("Note: To schedule tweets, use the GUI version (run-gui.bat)");
        log("The service will automatically post scheduled tweets in the background");
        log("");
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("Service shutting down...");
            if (scheduler != null) {
                scheduler.shutdown();
            }
            log("========================================");
        }));
        
        // Keep main thread alive
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            log("Service interrupted: " + e.getMessage());
        }
    }
    
    private static void startScheduler() {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            // Cleanup stale lock files periodically
            ScheduleStorage.cleanupStaleLocks();

            // Reload scheduled tweets from file (to pick up new tweets from GUI)
            List<ScheduledTweet> loadedTweets = ScheduleStorage.load();
            
            // Merge with existing tweets (keep posted status)
            for (ScheduledTweet loaded : loadedTweets) {
                boolean exists = false;
                for (ScheduledTweet existing : scheduledTweets) {
                    if (existing.getId().equals(loaded.getId())) {
                        // Update existing tweet if not posted yet
                        if (!existing.isPosted()) {
                            existing.setText(loaded.getText());
                            existing.setImagePath(loaded.getImagePath());
                            existing.setScheduledTime(loaded.getScheduledTime());
                        }
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    // New tweet, add it
                    scheduledTweets.add(loaded);
                    log("Loaded new scheduled tweet: " + loaded.getText().substring(0, Math.min(30, loaded.getText().length())) + "...");
                }
            }
            
            // Remove tweets that are no longer in the file (unless already posted)
            scheduledTweets.removeIf(tweet -> {
                if (tweet.isPosted()) return false; // Keep posted tweets
                boolean stillExists = loadedTweets.stream()
                    .anyMatch(loaded -> loaded.getId().equals(tweet.getId()));
                if (!stillExists) {
                    log("Removed cancelled tweet: " + tweet.getText().substring(0, Math.min(30, tweet.getText().length())) + "...");
                    queuedTweetIds.remove(tweet.getId()); // Clean up queued set
                }
                return !stillExists;
            });
            
            // Check and post scheduled tweets
            LocalDateTime now = LocalDateTime.now();
            
            // Reload posted status from file to catch updates from other schedulers (GUI)
            // This prevents duplicate posting when both MainService and GUI are running
            for (ScheduledTweet existing : scheduledTweets) {
                for (ScheduledTweet loaded : loadedTweets) {
                    if (existing.getId().equals(loaded.getId()) && loaded.isPosted()) {
                        existing.setPosted(true); // Update posted status from file
                        break;
                    }
                }
            }
            
            // Collect all passed tweets that haven't been posted or queued yet
            List<ScheduledTweet> passedTweets = scheduledTweets.stream()
                .filter(tweet -> !tweet.isPosted() && 
                                !queuedTweetIds.contains(tweet.getId()) &&
                                !tweet.getScheduledTime().isAfter(now) &&
                                tweet.getScheduledTime().plusSeconds(5).isBefore(now))
                .sorted((t1, t2) -> t1.getScheduledTime().compareTo(t2.getScheduledTime())) // Sort by scheduled time (oldest first)
                .collect(Collectors.toList());
            
            // If we have passed tweets, schedule them with delays
            if (!passedTweets.isEmpty()) {
                log("Found " + passedTweets.size() + " passed scheduled tweet(s) - scheduling with " + DELAY_BETWEEN_PASSED_TWEETS_MINUTES + "-minute gaps");
                
                for (int i = 0; i < passedTweets.size(); i++) {
                    ScheduledTweet tweet = passedTweets.get(i);
                    long delayMinutes = i * DELAY_BETWEEN_PASSED_TWEETS_MINUTES;
                    
                    // Mark as queued immediately to prevent duplicate queuing
                    queuedTweetIds.add(tweet.getId());
                    
                    if (delayMinutes == 0) {
                        // First tweet posts immediately
                        log("Scheduling first passed tweet to post immediately");
                        postScheduledTweet(tweet);
                    } else {
                        // Subsequent tweets post with delays
                        log("Scheduling passed tweet #" + (i + 1) + " to post in " + delayMinutes + " minutes");
                        scheduler.schedule(() -> {
                            postScheduledTweet(tweet);
                        }, delayMinutes, TimeUnit.MINUTES);
                    }
                }
            }
        }, 0, 30, TimeUnit.SECONDS); // Check every 30 seconds
    }
    
    private static void postScheduledTweet(ScheduledTweet tweet) {
        // Prevent duplicate posting - check if already posted
        if (tweet.isPosted()) {
            return; // Already posted, skip
        }

        // Try to acquire a file-based lock to prevent GUI from posting the same tweet
        if (!ScheduleStorage.tryAcquirePostLock(tweet.getId())) {
            log("Another process is already posting tweet: " + tweet.getId() + " - skipping");
            return;
        }

        // CRITICAL: Mark as posted in file IMMEDIATELY to prevent other schedulers from posting it
        tweet.setPosted(true);
        ScheduleStorage.save(scheduledTweets);
        log("Marked tweet as posting in file to prevent duplicates");

        new Thread(() -> {
            try {
                log("Posting scheduled tweet: " + tweet.getText().substring(0, Math.min(50, tweet.getText().length())) + "...");

                // Check if it's a thread (over 280 chars) - warn about multiple tweets
                int tweetCount = (int) Math.ceil(tweet.getText().length() / 280.0);
                if (tweetCount > 1) {
                    log("This tweet will be split into " + tweetCount + " tweets (thread)");
                }

                if (tweet.hasImage() && checkImageExists(tweet.getImagePath())) {
                    poster.postTweetWithImage(tweet.getText(), tweet.getImagePath());
                    log("Tweet with image posted successfully!");
                } else {
                    poster.postTweet(tweet.getText());
                    log("Tweet posted successfully!");
                }

                // Remove from queued set since it's now posted
                queuedTweetIds.remove(tweet.getId());

                // Save again to ensure status is persisted (already marked as posted above)
                ScheduleStorage.save(scheduledTweets);

            } catch (Exception e) {
                String errorMsg = e.getMessage();
                log("ERROR: Failed to post scheduled tweet: " + errorMsg);

                // Remove from queued set
                queuedTweetIds.remove(tweet.getId());

                // IMPORTANT: Keep tweet marked as POSTED to prevent duplicate posting
                // If the first tweet in a thread was already sent, retrying would create duplicates
                // User should manually reschedule if they want to retry
                if (errorMsg != null && errorMsg.contains("429")) {
                    log("Rate limit hit - tweet marked as posted to prevent duplicates");
                } else {
                    log("Error occurred - tweet marked as posted to prevent duplicate posting on retry");
                    log("If the tweet was not posted, please delete and reschedule it manually");
                }
                // Always keep as posted - never mark as not posted to avoid duplicates
                ScheduleStorage.save(scheduledTweets);

                e.printStackTrace();
            } finally {
                // Always release the lock when done
                ScheduleStorage.releasePostLock(tweet.getId());
            }
        }).start();
    }
    
    private static boolean checkImageExists(String imagePath) {
        if (imagePath == null || imagePath.isEmpty()) {
            return false;
        }
        
        try {
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
    
    private static String getEnvOrValue(String envVar, String defaultValue) {
        String envValue = System.getenv(envVar);
        return (envValue != null && !envValue.isEmpty()) ? envValue : defaultValue;
    }
    
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

