package com.twitter;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles saving and loading scheduled tweets to/from a file
 * Shared between GUI and Service
 */
public class ScheduleStorage {
    private static final String SCHEDULE_FILE = "scheduled_tweets.dat";
    private static final String LOCK_DIR = "locks";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final long LOCK_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes - lock expires after this
    
    /**
     * Saves scheduled tweets to a file
     */
    public static void save(List<ScheduledTweet> tweets) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(SCHEDULE_FILE))) {
            for (ScheduledTweet tweet : tweets) {
                writer.println("TWEET_START");
                writer.println("ID:" + tweet.getId());
                writer.println("TEXT:" + escapeNewlines(tweet.getText()));
                writer.println("IMAGEPATH:" + (tweet.getImagePath() == null ? "null" : escapeNewlines(tweet.getImagePath())));
                writer.println("SCHEDULEDTIME:" + tweet.getScheduledTime().format(FORMATTER));
                writer.println("POSTED:" + tweet.isPosted());
                writer.println("TWEET_END");
            }
        } catch (IOException e) {
            System.err.println("Failed to save scheduled tweets: " + e.getMessage());
        }
    }
    
    /**
     * Loads scheduled tweets from a file
     */
    public static List<ScheduledTweet> load() {
        List<ScheduledTweet> tweets = new ArrayList<>();
        
        File file = new File(SCHEDULE_FILE);
        if (!file.exists()) {
            return tweets;
        }
        
        try (BufferedReader reader = new BufferedReader(new FileReader(SCHEDULE_FILE))) {
            String line;
            String id = null;
            String text = null;
            String imagePath = null;
            String scheduledTimeStr = null;
            boolean posted = false;
            
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                if (line.equals("TWEET_START")) {
                    id = null;
                    text = null;
                    imagePath = null;
                    scheduledTimeStr = null;
                    posted = false;
                } else if (line.startsWith("ID:")) {
                    id = line.substring(3);
                } else if (line.startsWith("TEXT:")) {
                    text = unescapeNewlines(line.substring(5));
                } else if (line.startsWith("IMAGEPATH:")) {
                    String path = line.substring(10);
                    imagePath = "null".equals(path) ? null : unescapeNewlines(path);
                } else if (line.startsWith("SCHEDULEDTIME:")) {
                    scheduledTimeStr = line.substring(14);
                } else if (line.startsWith("POSTED:")) {
                    posted = Boolean.parseBoolean(line.substring(7));
                } else if (line.equals("TWEET_END")) {
                    if (text != null && scheduledTimeStr != null) {
                        LocalDateTime scheduledTime = LocalDateTime.parse(scheduledTimeStr, FORMATTER);
                        ScheduledTweet tweet = new ScheduledTweet(text, imagePath, scheduledTime);
                        tweet.setPosted(posted);
                        
                        if (id != null && !id.isEmpty()) {
                            try {
                                java.lang.reflect.Field idField = ScheduledTweet.class.getDeclaredField("id");
                                idField.setAccessible(true);
                                idField.set(tweet, id);
                            } catch (Exception e) {
                                // Keep default ID
                            }
                        }
                        
                        tweets.add(tweet);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load scheduled tweets: " + e.getMessage());
        }
        
        return tweets;
    }
    
    private static String escapeNewlines(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }
    
    private static String unescapeNewlines(String str) {
        if (str == null) return "";
        return str.replace("\\r", "\r")
                  .replace("\\n", "\n")
                  .replace("\\\\", "\\");
    }

    /**
     * Attempts to acquire a lock for posting a specific tweet.
     * Uses file-based locking to prevent duplicate posting between GUI and Service.
     * @param tweetId The unique ID of the tweet to lock
     * @return true if lock was acquired, false if another process is already posting this tweet
     */
    public static boolean tryAcquirePostLock(String tweetId) {
        try {
            // Create locks directory if it doesn't exist
            File lockDir = new File(LOCK_DIR);
            if (!lockDir.exists()) {
                lockDir.mkdirs();
            }

            File lockFile = new File(LOCK_DIR, tweetId + ".lock");

            // Check if lock file exists and is recent (not stale)
            if (lockFile.exists()) {
                long lockAge = System.currentTimeMillis() - lockFile.lastModified();
                if (lockAge < LOCK_TIMEOUT_MS) {
                    // Lock is still valid, another process is posting
                    System.out.println("DEBUG: Lock exists for tweet " + tweetId + ", age: " + (lockAge / 1000) + "s");
                    return false;
                } else {
                    // Lock is stale, remove it
                    System.out.println("DEBUG: Removing stale lock for tweet " + tweetId);
                    lockFile.delete();
                }
            }

            // Try to create the lock file atomically
            // createNewFile returns false if file already exists (atomic check-and-create)
            boolean created = lockFile.createNewFile();
            if (created) {
                System.out.println("DEBUG: Acquired lock for tweet " + tweetId);
                return true;
            } else {
                // Another process created the lock between our check and create
                System.out.println("DEBUG: Failed to acquire lock for tweet " + tweetId + " (race condition)");
                return false;
            }
        } catch (IOException e) {
            System.err.println("ERROR: Failed to acquire lock for tweet " + tweetId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Releases the lock for a specific tweet.
     * @param tweetId The unique ID of the tweet to unlock
     */
    public static void releasePostLock(String tweetId) {
        try {
            File lockFile = new File(LOCK_DIR, tweetId + ".lock");
            if (lockFile.exists()) {
                lockFile.delete();
                System.out.println("DEBUG: Released lock for tweet " + tweetId);
            }
        } catch (Exception e) {
            System.err.println("ERROR: Failed to release lock for tweet " + tweetId + ": " + e.getMessage());
        }
    }

    /**
     * Cleans up all stale lock files (older than LOCK_TIMEOUT_MS).
     * Should be called periodically to prevent lock file buildup.
     */
    public static void cleanupStaleLocks() {
        try {
            File lockDir = new File(LOCK_DIR);
            if (!lockDir.exists()) {
                return;
            }

            File[] lockFiles = lockDir.listFiles((dir, name) -> name.endsWith(".lock"));
            if (lockFiles == null) {
                return;
            }

            long now = System.currentTimeMillis();
            for (File lockFile : lockFiles) {
                long lockAge = now - lockFile.lastModified();
                if (lockAge > LOCK_TIMEOUT_MS) {
                    lockFile.delete();
                    System.out.println("DEBUG: Cleaned up stale lock: " + lockFile.getName());
                }
            }
        } catch (Exception e) {
            System.err.println("ERROR: Failed to cleanup stale locks: " + e.getMessage());
        }
    }
}

