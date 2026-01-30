package com.twitter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
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
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    
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
}

