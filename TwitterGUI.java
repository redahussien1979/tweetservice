package com.twitter;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * GUI application for posting and scheduling tweets to X (Twitter)
 */
public class TwitterGUI extends JFrame {
    private final XPoster poster;
    private List<String> imagePaths; // Changed to support multiple images
    private boolean imageExists;
    
    // Post Now components
    private JTextArea tweetTextArea;
    private JPanel imagePreviewPanel; // Changed to panel for multiple images
    private JLabel statusLabel;
    private JButton postButton;
    private JButton browseButton;
    private JButton clearImagesButton;
    private JLabel characterCountLabel;
    private JLabel imageStatusLabel;
    
    // Schedule components
    private DefaultTableModel scheduleTableModel;
    private JTable scheduleTable;
    private List<ScheduledTweet> scheduledTweets;
    private ScheduledExecutorService scheduler;
    private Set<String> postingTweetIds = ConcurrentHashMap.newKeySet(); // Thread-safe set for tracking tweets being posted
    
    public TwitterGUI(XPoster poster, String imagePath, boolean imageExists) {
        this.poster = poster;
        this.imagePaths = new ArrayList<>();
        if (imagePath != null && !imagePath.isEmpty()) {
            this.imagePaths.add(imagePath);
        }
        this.imageExists = imageExists;
        
        // Load scheduled tweets from file (shared with service) - use synchronized list for thread safety
        this.scheduledTweets = Collections.synchronizedList(ScheduleStorage.load());
        
        initializeGUI();
        startScheduler();
        
        // Save on window close
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                ScheduleStorage.save(scheduledTweets);
                if (scheduler != null) {
                    scheduler.shutdown();
                }
                System.exit(0);
            }
        });
    }
    
    private void initializeGUI() {
        setTitle("🐦 X (Twitter) Post App");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 650);
        setLocationRelativeTo(null);
        setResizable(true);
        
        // Create tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();
        
        // Tab 1: Post Now
        tabbedPane.addTab("📤 Post Now", createPostNowPanel());
        
        // Tab 2: Schedule Tweets
        tabbedPane.addTab("⏰ Schedule Tweets", createSchedulePanel());
        
        add(tabbedPane);
    }
    
    private JPanel createPostNowPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Top panel - Title and image status
        JPanel topPanel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("Post Tweet Now");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        topPanel.add(titleLabel, BorderLayout.WEST);
        
        JPanel imageControlPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        
        imageStatusLabel = new JLabel();
        updateImageStatus();
        imageStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        imageControlPanel.add(imageStatusLabel);
        
        browseButton = new JButton("📁 Browse Image");
        browseButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        browseButton.setPreferredSize(new Dimension(130, 30));
        browseButton.setFocusPainted(false);
        browseButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        browseButton.addActionListener(e -> browseForImage());
        imageControlPanel.add(browseButton);
        
        topPanel.add(imageControlPanel, BorderLayout.EAST);
        mainPanel.add(topPanel, BorderLayout.NORTH);
        
        // Center panel - Tweet text area and image preview
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        
        // Tweet text area
        JPanel tweetPanel = new JPanel(new BorderLayout(5, 5));
        JLabel tweetLabel = new JLabel("Tweet Text:");
        tweetLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        tweetPanel.add(tweetLabel, BorderLayout.NORTH);
        
        tweetTextArea = new JTextArea(8, 40);
        tweetTextArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        tweetTextArea.setLineWrap(true);
        tweetTextArea.setWrapStyleWord(true);
        tweetTextArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        
        characterCountLabel = new JLabel("0 / 280 characters");
        characterCountLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        characterCountLabel.setForeground(new Color(100, 100, 100));
        
        tweetTextArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateCharCount(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateCharCount(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateCharCount(); }
            
            private void updateCharCount() {
                int count = tweetTextArea.getText().length();
                int numTweets = (int) Math.ceil(count / 280.0);
                if (count > 280) {
                    characterCountLabel.setText(count + " characters - Will create " + numTweets + " tweet thread");
                    characterCountLabel.setForeground(new Color(220, 0, 0));
                } else {
                    characterCountLabel.setText(count + " / 280 characters");
                    if (count > 250) {
                        characterCountLabel.setForeground(new Color(255, 140, 0));
                    } else {
                        characterCountLabel.setForeground(new Color(100, 100, 100));
                    }
                }
            }
        });
        
        JPanel textAreaPanel = new JPanel(new BorderLayout());
        textAreaPanel.add(new JScrollPane(tweetTextArea), BorderLayout.CENTER);
        textAreaPanel.add(characterCountLabel, BorderLayout.SOUTH);
        
        tweetPanel.add(textAreaPanel, BorderLayout.CENTER);
        centerPanel.add(tweetPanel, BorderLayout.CENTER);
        
        // Image preview panel with drag and drop support
        JPanel imagePanel = new JPanel(new BorderLayout(5, 5));
        JLabel imageLabel = new JLabel("Image Preview (Drag & Drop Multiple Images):");
        imageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        imagePanel.add(imageLabel, BorderLayout.NORTH);
        
        // Scrollable panel for multiple images
        imagePreviewPanel = new JPanel();
        imagePreviewPanel.setLayout(new BoxLayout(imagePreviewPanel, BoxLayout.Y_AXIS));
        imagePreviewPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));
        imagePreviewPanel.setPreferredSize(new Dimension(220, 400));
        imagePreviewPanel.setBackground(Color.WHITE);
        
        // Add drag and drop support
        setupDragAndDrop(imagePreviewPanel);
        
        JScrollPane imageScrollPane = new JScrollPane(imagePreviewPanel);
        imageScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        imageScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        imageScrollPane.setPreferredSize(new Dimension(220, 400));
        
        if (imageExists && !imagePaths.isEmpty()) {
            updateImagePreviews();
        } else {
            JLabel placeholderLabel = new JLabel("<html><center>No images selected<br/><br/>Drag & drop images here<br/>or click 'Browse Image'</center></html>");
            placeholderLabel.setHorizontalAlignment(JLabel.CENTER);
            placeholderLabel.setForeground(new Color(150, 150, 150));
            placeholderLabel.setPreferredSize(new Dimension(200, 200));
            imagePreviewPanel.add(placeholderLabel);
        }
        
        imagePanel.add(imageScrollPane, BorderLayout.CENTER);
        
        // Clear images button
        clearImagesButton = new JButton("🗑️ Clear All Images");
        clearImagesButton.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        clearImagesButton.setPreferredSize(new Dimension(200, 25));
        clearImagesButton.setBackground(new Color(220, 53, 69));
        clearImagesButton.setForeground(Color.WHITE);
        clearImagesButton.setFocusPainted(false);
        clearImagesButton.setBorderPainted(false);
        clearImagesButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        clearImagesButton.setEnabled(false);
        clearImagesButton.addActionListener(e -> clearAllImages());
        imagePanel.add(clearImagesButton, BorderLayout.SOUTH);
        
        centerPanel.add(imagePanel, BorderLayout.EAST);
        
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        
        // Bottom panel - Status and Post button
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        
        statusLabel = new JLabel("Ready to post");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusLabel.setForeground(new Color(100, 100, 100));
        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        
        postButton = new JButton("📤 Post Tweet");
        postButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        postButton.setPreferredSize(new Dimension(150, 40));
        postButton.setBackground(new Color(29, 161, 242));
        postButton.setForeground(Color.WHITE);
        postButton.setFocusPainted(false);
        postButton.setBorderPainted(false);
        postButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        postButton.addActionListener(e -> postTweet());
        
        postButton.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                postButton.setBackground(new Color(26, 145, 218));
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                postButton.setBackground(new Color(29, 161, 242));
            }
        });
        
        bottomPanel.add(postButton, BorderLayout.EAST);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        // Set Enter key to post (Ctrl+Enter)
        tweetTextArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "post");
        tweetTextArea.getActionMap().put("post", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                postTweet();
            }
        });
        
        return mainPanel;
    }
    
    private JPanel createSchedulePanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Top panel - Title and Add button
        JPanel topPanel = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("Schedule Tweets");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        topPanel.add(titleLabel, BorderLayout.WEST);
        
        JButton addButton = new JButton("➕ Add Scheduled Tweet");
        addButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        addButton.setPreferredSize(new Dimension(180, 35));
        addButton.setBackground(new Color(29, 161, 242));
        addButton.setForeground(Color.WHITE);
        addButton.setFocusPainted(false);
        addButton.setBorderPainted(false);
        addButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        addButton.addActionListener(e -> showAddScheduleDialog());
        topPanel.add(addButton, BorderLayout.EAST);
        
        mainPanel.add(topPanel, BorderLayout.NORTH);
        
        // Center panel - Scheduled tweets table
        String[] columnNames = {"Tweet", "Image", "Scheduled Time", "Status", "Actions"};
        scheduleTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 4; // Only Actions column is editable
            }
        };
        
        scheduleTable = new JTable(scheduleTableModel);
        scheduleTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        scheduleTable.setRowHeight(35);
        scheduleTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        scheduleTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Set column widths
        scheduleTable.getColumn("Tweet").setPreferredWidth(250);
        scheduleTable.getColumn("Image").setPreferredWidth(80);
        scheduleTable.getColumn("Scheduled Time").setPreferredWidth(150);
        scheduleTable.getColumn("Status").setPreferredWidth(100);
        scheduleTable.getColumn("Actions").setPreferredWidth(100);
        
        // Custom renderer and editor for actions column
        scheduleTable.getColumn("Actions").setCellRenderer(new ButtonRenderer());
        scheduleTable.getColumn("Actions").setCellEditor(new ButtonEditor(new JCheckBox()));
        
        JScrollPane scrollPane = new JScrollPane(scheduleTable);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Scheduled Tweets"));
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Add delete selected and clear all buttons below table
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton deleteSelectedButton = new JButton("🗑️ Delete Selected");
        deleteSelectedButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        deleteSelectedButton.setBackground(new Color(220, 53, 69));
        deleteSelectedButton.setForeground(Color.WHITE);
        deleteSelectedButton.setFocusPainted(false);
        deleteSelectedButton.setBorderPainted(false);
        deleteSelectedButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        deleteSelectedButton.addActionListener(e -> deleteSelectedTweet());
        actionPanel.add(deleteSelectedButton);
        
        JButton clearAllButton = new JButton("🗑️ Clear All");
        clearAllButton.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        clearAllButton.setBackground(new Color(200, 0, 0));
        clearAllButton.setForeground(Color.WHITE);
        clearAllButton.setFocusPainted(false);
        clearAllButton.setBorderPainted(false);
        clearAllButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        clearAllButton.addActionListener(e -> clearAllScheduledTweets());
        actionPanel.add(clearAllButton);
        
        JPanel centerWithActions = new JPanel(new BorderLayout());
        centerWithActions.add(scrollPane, BorderLayout.CENTER);
        centerWithActions.add(actionPanel, BorderLayout.SOUTH);
        
        mainPanel.remove(scrollPane);
        mainPanel.add(centerWithActions, BorderLayout.CENTER);
        
        // Bottom panel - Info
        JLabel infoLabel = new JLabel("💡 Tip: Scheduled tweets will be posted automatically at the specified time");
        infoLabel.setFont(new Font("Segoe UI", Font.ITALIC, 11));
        infoLabel.setForeground(new Color(100, 100, 100));
        mainPanel.add(infoLabel, BorderLayout.SOUTH);
        
        return mainPanel;
    }
    
    private void showAddScheduleDialog() {
        JDialog dialog = new JDialog(this, "Add Scheduled Tweet", true);
        dialog.setSize(600, 500);
        dialog.setLocationRelativeTo(this);
        
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        
        // Tweet text
        JLabel textLabel = new JLabel("Tweet Text:");
        textLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JTextArea textArea = new JTextArea(6, 40);
        textArea.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
        
        JLabel charCountLabel = new JLabel("0 / 280 characters");
        charCountLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        charCountLabel.setForeground(new Color(100, 100, 100));
        
        textArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateCount(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateCount(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateCount(); }
            
            private void updateCount() {
                int count = textArea.getText().length();
                charCountLabel.setText(count + " / 280 characters");
            }
        });
        
        JPanel textPanel = new JPanel(new BorderLayout(5, 5));
        textPanel.add(textLabel, BorderLayout.NORTH);
        textPanel.add(new JScrollPane(textArea), BorderLayout.CENTER);
        textPanel.add(charCountLabel, BorderLayout.SOUTH);
        
        // Image selection
        JPanel imagePanel = new JPanel(new BorderLayout(5, 5));
        JLabel imageLabel = new JLabel("Image (Optional):");
        imageLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        
        JTextField imagePathField = new JTextField();
        imagePathField.setEditable(false);
        JButton browseBtn = new JButton("📁 Browse");
        browseBtn.addActionListener(e -> {
            String path = browseForImageFile();
            if (path != null) {
                imagePathField.setText(path);
            }
        });
        
        JPanel imageControlPanel = new JPanel(new BorderLayout(5, 0));
        imageControlPanel.add(imagePathField, BorderLayout.CENTER);
        imageControlPanel.add(browseBtn, BorderLayout.EAST);
        
        imagePanel.add(imageLabel, BorderLayout.NORTH);
        imagePanel.add(imageControlPanel, BorderLayout.CENTER);
        
        // Date and time selection
        JPanel timePanel = new JPanel(new BorderLayout(5, 5));
        timePanel.setBorder(BorderFactory.createTitledBorder("Schedule Time"));
        
        JPanel timeFieldsPanel = new JPanel(new GridLayout(2, 2, 10, 10));
        
        JLabel dateLabel = new JLabel("Date (YYYY-MM-DD):");
        dateLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JTextField dateField = new JTextField();
        dateField.setText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        dateField.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        
        JLabel timeLabel = new JLabel("Time (HH:mm):");
        timeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JTextField timeField = new JTextField();
        timeField.setText(LocalDateTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("HH:mm")));
        timeField.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        
        timeFieldsPanel.add(dateLabel);
        timeFieldsPanel.add(dateField);
        timeFieldsPanel.add(timeLabel);
        timeFieldsPanel.add(timeField);
        
        timePanel.add(timeFieldsPanel, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dialog.dispose());
        
        JButton addButton = new JButton("Add to Schedule");
        addButton.setBackground(new Color(29, 161, 242));
        addButton.setForeground(Color.WHITE);
        addButton.addActionListener(e -> {
            String text = textArea.getText().trim();
            if (text.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Tweet text cannot be empty!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            // Allow long tweets - they'll be split into threads
            int textLength = text.length();
            if (textLength > 280) {
                int numTweets = (int) Math.ceil(textLength / 280.0);
                int confirm = JOptionPane.showConfirmDialog(dialog,
                    "Tweet exceeds 280 characters (" + textLength + " chars).\n" +
                    "It will be split into " + numTweets + " tweet(s) as a thread.\n\n" +
                    "Continue?",
                    "Long Tweet - Will Create Thread",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
                
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            
            try {
                String dateStr = dateField.getText().trim();
                String timeStr = timeField.getText().trim();
                LocalDateTime scheduledTime = LocalDateTime.parse(dateStr + " " + timeStr, 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                
                if (scheduledTime.isBefore(LocalDateTime.now())) {
                    JOptionPane.showMessageDialog(dialog, "Scheduled time must be in the future!", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                String imgPath = imagePathField.getText().trim();
                if (imgPath.isEmpty()) {
                    imgPath = null;
                }
                
                ScheduledTweet scheduledTweet = new ScheduledTweet(text, imgPath, scheduledTime);
                scheduledTweets.add(scheduledTweet);
                ScheduleStorage.save(scheduledTweets); // Save immediately
                updateScheduleTable();
                dialog.dispose();
                JOptionPane.showMessageDialog(this, "Tweet scheduled successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Invalid date/time format! Use YYYY-MM-DD and HH:mm\nExample: 2024-12-25 14:30", "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        
        buttonPanel.add(cancelButton);
        buttonPanel.add(addButton);
        
        // Combine time panel and button panel
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.add(timePanel, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        panel.add(textPanel, BorderLayout.NORTH);
        panel.add(imagePanel, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        
        dialog.add(panel);
        dialog.setVisible(true);
    }
    
    private void updateScheduleTable() {
        scheduleTableModel.setRowCount(0);
        for (ScheduledTweet tweet : scheduledTweets) {
            String preview = tweet.getText().length() > 40 ? tweet.getText().substring(0, 40) + "..." : tweet.getText();
            String image = tweet.hasImage() ? "📷 Yes" : "No";
            String status = tweet.isPosted() ? "✅ Posted" : "⏳ Pending";
            scheduleTableModel.addRow(new Object[]{preview, image, tweet.getFormattedTime(), status, "Delete"});
        }
    }
    
    private void startScheduler() {
        scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            // Cleanup stale lock files periodically
            ScheduleStorage.cleanupStaleLocks();

            // Reload posted status from file to catch updates from other schedulers (MainService)
            // This prevents duplicate posting when both MainService and GUI are running
            List<ScheduledTweet> loadedTweets = ScheduleStorage.load();
            for (ScheduledTweet existing : scheduledTweets) {
                for (ScheduledTweet loaded : loadedTweets) {
                    if (existing.getId().equals(loaded.getId()) && loaded.isPosted()) {
                        existing.setPosted(true); // Update posted status from file
                        break;
                    }
                }
            }
            
            LocalDateTime now = LocalDateTime.now();
            for (ScheduledTweet tweet : scheduledTweets) {
                // Only post if: not already posted, not currently being posted, and time has passed
                if (!tweet.isPosted() && 
                    !postingTweetIds.contains(tweet.getId()) &&
                    !tweet.getScheduledTime().isAfter(now)) {
                    postScheduledTweet(tweet);
                }
            }
        }, 0, 30, TimeUnit.SECONDS); // Check every 30 seconds
    }
    
    private void postScheduledTweet(ScheduledTweet tweet) {
        // Prevent duplicate posting - check if already posted or currently posting
        if (tweet.isPosted() || postingTweetIds.contains(tweet.getId())) {
            return; // Already posted or currently posting, skip
        }

        // Try to acquire a file-based lock to prevent Service from posting the same tweet
        if (!ScheduleStorage.tryAcquirePostLock(tweet.getId())) {
            System.out.println("Another process is already posting tweet: " + tweet.getId() + " - skipping");
            return;
        }

        // CRITICAL: Mark as posted in file IMMEDIATELY to prevent other schedulers from posting it
        postingTweetIds.add(tweet.getId());
        tweet.setPosted(true);
        ScheduleStorage.save(scheduledTweets); // Save immediately to file

        new Thread(() -> {
            try {
                if (tweet.hasImage() && checkImageExists(tweet.getImagePath())) {
                    poster.postTweetWithImage(tweet.getText(), tweet.getImagePath());
                } else {
                    poster.postTweet(tweet.getText());
                }
                postingTweetIds.remove(tweet.getId()); // Remove from posting set
                ScheduleStorage.save(scheduledTweets); // Save again to ensure status is persisted
                SwingUtilities.invokeLater(() -> {
                    updateScheduleTable();
                    statusLabel.setText("Scheduled tweet posted: " + tweet.getText().substring(0, Math.min(30, tweet.getText().length())) + "...");
                    statusLabel.setForeground(new Color(0, 150, 0));
                });
            } catch (Exception e) {
                postingTweetIds.remove(tweet.getId()); // Remove from posting set on error

                String errorMsg = e.getMessage();
                // IMPORTANT: Keep tweet marked as POSTED to prevent duplicate posting
                // If the first tweet in a thread was already sent, retrying would create duplicates
                if (errorMsg != null && errorMsg.contains("429")) {
                    System.out.println("Rate limit hit - tweet marked as posted to prevent duplicates");
                } else {
                    System.out.println("Error occurred - tweet marked as posted to prevent duplicate posting on retry");
                }
                // Always keep as posted - never mark as not posted to avoid duplicates
                ScheduleStorage.save(scheduledTweets);

                SwingUtilities.invokeLater(() -> {
                    updateScheduleTable();
                    statusLabel.setText("Failed to post scheduled tweet: " + e.getMessage());
                    statusLabel.setForeground(new Color(220, 0, 0));
                });
            } finally {
                // Always release the lock when done
                ScheduleStorage.releasePostLock(tweet.getId());
            }
        }).start();
    }
    
    private void deleteScheduledTweet(int row) {
        if (row >= 0 && row < scheduledTweets.size()) {
            ScheduledTweet tweet = scheduledTweets.get(row);
            String message;
            if (tweet.isPosted()) {
                message = "This tweet has already been posted.\nDo you want to remove it from the list?";
            } else {
                message = "Are you sure you want to cancel/delete this scheduled tweet?\n\n" +
                         "Tweet: " + (tweet.getText().length() > 50 ? tweet.getText().substring(0, 50) + "..." : tweet.getText()) + "\n" +
                         "Scheduled for: " + tweet.getFormattedTime();
            }
            
            int confirm = JOptionPane.showConfirmDialog(this, 
                message, 
                "Confirm Delete", 
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            
            if (confirm == JOptionPane.YES_OPTION) {
                scheduledTweets.remove(row);
                ScheduleStorage.save(scheduledTweets); // Save after deletion
                updateScheduleTable();
                JOptionPane.showMessageDialog(this, 
                    tweet.isPosted() ? "Tweet removed from list." : "Scheduled tweet cancelled successfully!", 
                    "Success", 
                    JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }
    
    private void deleteSelectedTweet() {
        int selectedRow = scheduleTable.getSelectedRow();
        if (selectedRow == -1) {
            JOptionPane.showMessageDialog(this, 
                "Please select a tweet from the table to delete.", 
                "No Selection", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        deleteScheduledTweet(selectedRow);
    }
    
    private void clearAllScheduledTweets() {
        if (scheduledTweets.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "There are no scheduled tweets to clear.", 
                "No Tweets", 
                JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        int totalCount = scheduledTweets.size();
        int postedCount = (int) scheduledTweets.stream().filter(ScheduledTweet::isPosted).count();
        int pendingCount = totalCount - postedCount;
        
        String message = "Are you sure you want to clear ALL scheduled tweets?\n\n" +
                        "Total tweets: " + totalCount + "\n" +
                        "Posted: " + postedCount + "\n" +
                        "Pending: " + pendingCount + "\n\n" +
                        "This action cannot be undone!";
        
        int confirm = JOptionPane.showConfirmDialog(this, 
            message, 
            "Clear All Scheduled Tweets", 
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE);
        
        if (confirm == JOptionPane.YES_OPTION) {
            scheduledTweets.clear();
            postingTweetIds.clear(); // Clear any in-progress posting IDs
            ScheduleStorage.save(scheduledTweets); // Save empty list
            updateScheduleTable();
            JOptionPane.showMessageDialog(this, 
                "All scheduled tweets have been cleared.", 
                "Cleared", 
                JOptionPane.INFORMATION_MESSAGE);
        }
    }
    
    // Button renderer and editor for actions column
    class ButtonRenderer extends JButton implements javax.swing.table.TableCellRenderer {
        public ButtonRenderer() {
            setOpaque(true);
            setFocusPainted(false);
            setBorderPainted(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }
        
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            // Bounds check to prevent IndexOutOfBoundsException
            if (row >= 0 && row < scheduledTweets.size()) {
                ScheduledTweet tweet = scheduledTweets.get(row);
                if (tweet.isPosted()) {
                    setText("Remove");
                    setBackground(new Color(108, 117, 125)); // Gray for posted tweets
                } else {
                    setText("Cancel");
                    setBackground(new Color(220, 53, 69)); // Red for pending tweets
                }
            } else {
                setText("...");
                setBackground(new Color(150, 150, 150));
            }
            setForeground(Color.WHITE);
            setFont(new Font("Segoe UI", Font.PLAIN, 11));
            return this;
        }
    }
    
    class ButtonEditor extends DefaultCellEditor {
        protected JButton button;
        private int row;
        private JTable table;
        
        public ButtonEditor(JCheckBox checkBox) {
            super(checkBox);
            button = new JButton();
            button.setOpaque(true);
            button.setFocusPainted(false);
            button.setBorderPainted(false);
            button.setCursor(new Cursor(Cursor.HAND_CURSOR));
            button.addActionListener(e -> {
                fireEditingStopped();
                if (row >= 0 && row < scheduledTweets.size()) {
                    deleteScheduledTweet(row);
                }
            });
        }
        
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            this.row = row;
            this.table = table;

            // Bounds check to prevent IndexOutOfBoundsException
            if (row >= 0 && row < scheduledTweets.size()) {
                ScheduledTweet tweet = scheduledTweets.get(row);
                if (tweet.isPosted()) {
                    button.setText("Remove");
                    button.setBackground(new Color(108, 117, 125));
                } else {
                    button.setText("Cancel");
                    button.setBackground(new Color(220, 53, 69));
                }
            } else {
                button.setText("...");
                button.setBackground(new Color(150, 150, 150));
            }
            button.setForeground(Color.WHITE);
            button.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            return button;
        }
        
        public Object getCellEditorValue() {
            return button.getText();
        }
        
        public boolean stopCellEditing() {
            return super.stopCellEditing();
        }
        
        protected void fireEditingStopped() {
            super.fireEditingStopped();
        }
    }
    
    private void browseForImage() {
        List<String> paths = browseForImageFiles();
        if (paths != null && !paths.isEmpty()) {
            for (String path : paths) {
                if (checkImageExists(path) && !imagePaths.contains(path)) {
                    imagePaths.add(path);
                }
            }
            imageExists = !imagePaths.isEmpty();
            updateImageStatus();
            updateImagePreviews();
            showStatus("✅ " + paths.size() + " image(s) selected", new Color(0, 150, 0));
        }
    }
    
    private List<String> browseForImageFiles() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Images to Upload");
        fileChooser.setMultiSelectionEnabled(true); // Enable multiple selection
        
        javax.swing.filechooser.FileFilter imageFilter = new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                if (f.isDirectory()) return true;
                String name = f.getName().toLowerCase();
                return name.endsWith(".jpg") || name.endsWith(".jpeg") || 
                       name.endsWith(".png") || name.endsWith(".gif") || 
                       name.endsWith(".webp") || name.endsWith(".bmp");
            }
            
            @Override
            public String getDescription() {
                return "Image Files (*.jpg, *.jpeg, *.png, *.gif, *.webp, *.bmp)";
            }
        };
        fileChooser.setFileFilter(imageFilter);
        
        if (!imagePaths.isEmpty()) {
            File currentFile = new File(imagePaths.get(0));
            if (currentFile.getParentFile() != null && currentFile.getParentFile().exists()) {
                fileChooser.setCurrentDirectory(currentFile.getParentFile());
            }
        } else {
            String home = System.getProperty("user.home");
            File desktop = new File(home, "Desktop");
            if (desktop.exists()) {
                fileChooser.setCurrentDirectory(desktop);
            }
        }
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = fileChooser.getSelectedFiles();
            List<String> paths = new ArrayList<>();
            for (File file : selectedFiles) {
                paths.add(file.getAbsolutePath());
            }
            return paths;
        }
        return null;
    }
    
    private String browseForImageFile() {
        List<String> paths = browseForImageFiles();
        if (paths != null && !paths.isEmpty()) {
            return paths.get(0); // Return first for backward compatibility
        }
        return null;
    }
    
    private void updateImageStatus() {
        if (imageExists && !imagePaths.isEmpty()) {
            int count = imagePaths.size();
            if (count == 1) {
                String fileName = new File(imagePaths.get(0)).getName();
                imageStatusLabel.setText("📷 Image: " + fileName);
            } else {
                imageStatusLabel.setText("📷 Images: " + count + " selected");
            }
            imageStatusLabel.setForeground(new Color(0, 150, 0));
        } else {
            imageStatusLabel.setText("📝 Text-only mode");
            imageStatusLabel.setForeground(new Color(150, 150, 150));
        }
    }
    
    private void updateImagePreviews() {
        if (imagePreviewPanel == null) return;
        
        SwingUtilities.invokeLater(() -> {
            imagePreviewPanel.removeAll();
            
            if (imagePaths.isEmpty()) {
                JLabel placeholderLabel = new JLabel("<html><center>No images selected<br/><br/>Drag & drop images here<br/>or click 'Browse Image'</center></html>");
                placeholderLabel.setHorizontalAlignment(JLabel.CENTER);
                placeholderLabel.setForeground(new Color(150, 150, 150));
                placeholderLabel.setPreferredSize(new Dimension(200, 200));
                imagePreviewPanel.add(placeholderLabel);
                clearImagesButton.setEnabled(false);
            } else {
                for (int i = 0; i < imagePaths.size(); i++) {
                    String imagePath = imagePaths.get(i);
                    JPanel imageItemPanel = createImagePreviewItem(imagePath, i);
                    imagePreviewPanel.add(imageItemPanel);
                    imagePreviewPanel.add(Box.createVerticalStrut(5));
                }
                clearImagesButton.setEnabled(true);
            }
            
            imagePreviewPanel.revalidate();
            imagePreviewPanel.repaint();
        });
    }
    
    private JPanel createImagePreviewItem(String imagePath, int index) {
        JPanel itemPanel = new JPanel(new BorderLayout(5, 5));
        itemPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(200, 200, 200), 1),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        itemPanel.setBackground(Color.WHITE);
        itemPanel.setMaximumSize(new Dimension(200, 180));
        
        try {
            File imageFile = new File(imagePath);
            if (imageFile.exists()) {
                ImageIcon imageIcon = new ImageIcon(ImageIO.read(imageFile));
                Image image = imageIcon.getImage();
                
                int width = image.getWidth(null);
                int height = image.getHeight(null);
                double scale = Math.min(150.0 / width, 150.0 / height);
                int scaledWidth = (int) (width * scale);
                int scaledHeight = (int) (height * scale);
                
                Image scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
                JLabel imageLabel = new JLabel(new ImageIcon(scaledImage));
                imageLabel.setHorizontalAlignment(JLabel.CENTER);
                itemPanel.add(imageLabel, BorderLayout.CENTER);
                
                // Image info and remove button
                JPanel infoPanel = new JPanel(new BorderLayout());
                String fileName = imageFile.getName();
                JLabel nameLabel = new JLabel(fileName.length() > 20 ? fileName.substring(0, 17) + "..." : fileName);
                nameLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
                nameLabel.setForeground(new Color(100, 100, 100));
                infoPanel.add(nameLabel, BorderLayout.CENTER);
                
                JButton removeBtn = new JButton("✕");
                removeBtn.setFont(new Font("Segoe UI", Font.BOLD, 10));
                removeBtn.setPreferredSize(new Dimension(20, 20));
                removeBtn.setBackground(new Color(220, 53, 69));
                removeBtn.setForeground(Color.WHITE);
                removeBtn.setFocusPainted(false);
                removeBtn.setBorderPainted(false);
                removeBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
                int finalIndex = index;
                removeBtn.addActionListener(e -> removeImage(finalIndex));
                infoPanel.add(removeBtn, BorderLayout.EAST);
                
                itemPanel.add(infoPanel, BorderLayout.SOUTH);
            }
        } catch (IOException e) {
            JLabel errorLabel = new JLabel("Preview unavailable");
            errorLabel.setForeground(new Color(150, 150, 150));
            itemPanel.add(errorLabel, BorderLayout.CENTER);
        }
        
        return itemPanel;
    }
    
    private void removeImage(int index) {
        if (index >= 0 && index < imagePaths.size()) {
            imagePaths.remove(index);
            imageExists = !imagePaths.isEmpty();
            updateImageStatus();
            updateImagePreviews();
        }
    }
    
    private void clearAllImages() {
        imagePaths.clear();
        imageExists = false;
        updateImageStatus();
        updateImagePreviews();
        showStatus("✅ All images cleared", new Color(100, 100, 100));
    }
    
    private void setupDragAndDrop(JPanel panel) {
        new DropTarget(panel, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                    panel.setBackground(new Color(230, 240, 255));
                } else {
                    dtde.rejectDrag();
                }
            }
            
            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                }
            }
            
            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
            }
            
            @Override
            public void dragExit(DropTargetEvent dte) {
                panel.setBackground(Color.WHITE);
            }
            
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable transferable = dtde.getTransferable();
                    
                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                        
                        List<String> validPaths = new ArrayList<>();
                        for (File file : files) {
                            if (file.isFile() && isValidImageFile(file)) {
                                String path = file.getAbsolutePath();
                                if (!imagePaths.contains(path)) {
                                    validPaths.add(path);
                                }
                            }
                        }
                        
                        if (!validPaths.isEmpty()) {
                            imagePaths.addAll(validPaths);
                            imageExists = true;
                            updateImageStatus();
                            updateImagePreviews();
                            showStatus("✅ " + validPaths.size() + " image(s) added via drag & drop", new Color(0, 150, 0));
                        } else {
                            showStatus("⚠️ No valid image files found", new Color(255, 140, 0));
                        }
                        
                        dtde.dropComplete(true);
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception e) {
                    dtde.rejectDrop();
                    showStatus("❌ Error: " + e.getMessage(), new Color(220, 0, 0));
                } finally {
                    panel.setBackground(Color.WHITE);
                }
            }
        });
    }
    
    private boolean isValidImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || 
               name.endsWith(".png") || name.endsWith(".gif") || 
               name.endsWith(".webp") || name.endsWith(".bmp");
    }
    
    private void postTweet() {
        String tweet = tweetTextArea.getText().trim();
        
        if (tweet.isEmpty()) {
            showStatus("❌ Error: Tweet cannot be empty", new Color(220, 0, 0));
            return;
        }
        
        // Check if tweet exceeds limit and show warning
        int tweetLength = tweet.length();
        if (tweetLength > 280) {
            int numTweets = (int) Math.ceil(tweetLength / 280.0);
            int confirm = JOptionPane.showConfirmDialog(this, 
                "Tweet exceeds 280 characters (" + tweetLength + " chars).\n" +
                "It will be split into " + numTweets + " tweet(s) as a thread.\n\n" +
                "Continue?",
                "Long Tweet - Will Create Thread",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
            
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        
        postButton.setEnabled(false);
        postButton.setText("⏳ Posting...");
        
        new Thread(() -> {
            try {
                int numTweets = tweetLength > 280 ? (int) Math.ceil(tweetLength / 280.0) : 1;
                
                String response;
                String selectedImagePath = null;
                
                // Handle multiple images - Twitter only supports 1 image per tweet
                if (imageExists && !imagePaths.isEmpty()) {
                    // Find first valid image
                    for (String path : imagePaths) {
                        if (checkImageExists(path)) {
                            selectedImagePath = path;
                            break;
                        }
                    }
                    
                    // If multiple images, show info message (Twitter only supports 1 image)
                    if (imagePaths.size() > 1 && selectedImagePath != null) {
                        String firstImageName = new File(selectedImagePath).getName();
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(TwitterGUI.this,
                                "Note: Twitter supports only 1 image per tweet.\n" +
                                "Using first image: " + firstImageName + "\n\n" +
                                "Other " + (imagePaths.size() - 1) + " image(s) will be ignored.",
                                "Multiple Images Detected",
                                JOptionPane.INFORMATION_MESSAGE);
                        });
                    }
                }
                
                if (selectedImagePath != null) {
                    if (numTweets > 1) {
                        showStatus("📤 Posting thread (" + numTweets + " tweets) with image...", new Color(100, 100, 100));
                    } else {
                        showStatus("📤 Posting tweet with image...", new Color(100, 100, 100));
                    }
                    response = poster.postTweetWithImage(tweet, selectedImagePath);
                    if (numTweets > 1) {
                        showStatus("✅ Thread (" + numTweets + " tweets) with image posted successfully!", new Color(0, 150, 0));
                    } else {
                        showStatus("✅ Tweet with image posted successfully!", new Color(0, 150, 0));
                    }
                } else {
                    if (numTweets > 1) {
                        showStatus("📤 Posting thread (" + numTweets + " tweets)...", new Color(100, 100, 100));
                    } else {
                        showStatus("📤 Posting tweet...", new Color(100, 100, 100));
                    }
                    response = poster.postTweet(tweet);
                    if (numTweets > 1) {
                        showStatus("✅ Thread (" + numTweets + " tweets) posted successfully!", new Color(0, 150, 0));
                    } else {
                        showStatus("✅ Tweet posted successfully!", new Color(0, 150, 0));
                    }
                }
                
                SwingUtilities.invokeLater(() -> {
                    tweetTextArea.setText("");
                    // Clear images after successful post
                    imagePaths.clear();
                    imageExists = false;
                    updateImageStatus();
                    updateImagePreviews();
                });
                
            } catch (IllegalArgumentException e) {
                showStatus("❌ Error: " + e.getMessage(), new Color(220, 0, 0));
            } catch (IOException e) {
                showStatus("❌ Failed: " + e.getMessage(), new Color(220, 0, 0));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    postButton.setEnabled(true);
                    postButton.setText("📤 Post Tweet");
                });
            }
        }).start();
    }
    
    private void showStatus(String message, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(message);
            statusLabel.setForeground(color);
        });
    }
    
    private boolean checkImageExists(String imagePath) {
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
    
    @Override
    public void dispose() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        super.dispose();
    }
}

