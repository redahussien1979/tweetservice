package com.twitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Represents a scheduled tweet with text, image, and scheduled time
 */
public class ScheduledTweet {
    private String text;
    private String imagePath;
    private LocalDateTime scheduledTime;
    private boolean posted;
    private String id;
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    
    public ScheduledTweet(String text, String imagePath, LocalDateTime scheduledTime) {
        this.text = text;
        this.imagePath = imagePath;
        this.scheduledTime = scheduledTime;
        this.posted = false;
        this.id = String.valueOf(System.currentTimeMillis());
    }
    
    public String getText() {
        return text;
    }
    
    public void setText(String text) {
        this.text = text;
    }
    
    public String getImagePath() {
        return imagePath;
    }
    
    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }
    
    public LocalDateTime getScheduledTime() {
        return scheduledTime;
    }
    
    public void setScheduledTime(LocalDateTime scheduledTime) {
        this.scheduledTime = scheduledTime;
    }
    
    public boolean isPosted() {
        return posted;
    }
    
    public void setPosted(boolean posted) {
        this.posted = posted;
    }
    
    public String getId() {
        return id;
    }
    
    public boolean hasImage() {
        return imagePath != null && !imagePath.trim().isEmpty();
    }
    
    public String getFormattedTime() {
        return scheduledTime.format(FORMATTER);
    }
    
    @Override
    public String toString() {
        String preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
        String imageInfo = hasImage() ? " [📷]" : "";
        return preview + imageInfo + " - " + getFormattedTime();
    }
}

