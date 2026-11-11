package com.ndalamo.screens;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Ndalamo's icon in the Windows notification area (SPEC §6.3): it keeps watch folders running
 * after the window is closed, offers Open · Watch folders · Quit, and shows Windows
 * notifications ("Watch folder: 3 files processed · 1 failed"). Windows draws the
 * notification; Ndalamo only gives the icon, title and text. Nothing is sent anywhere.
 *
 * The tray belongs to Java's older AWT toolkit, so its calls run on AWT's thread and the
 * actions hop back to the JavaFX thread.
 */
public final class Tray {

    private TrayIcon icon;
    private volatile long lastNotification;

    /** False on PCs (or sessions) without a notification area; the app then quits on close as usual. */
    public static boolean supported() {
        try {
            return !java.awt.GraphicsEnvironment.isHeadless() && SystemTray.isSupported();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * @param onOpen          shows the window (also on double-click)
     * @param onWatchFolders  shows the watch folders and their log (also when a notification is clicked)
     * @param onQuit          the Quit menu item: the app's usual quit checks
     */
    public Tray(Image picture, Runnable onOpen, Runnable onWatchFolders, Runnable onQuit) {
        BufferedImage awtPicture = toAwt(picture);
        EventQueue.invokeLater(() -> {
            try {
                PopupMenu menu = new PopupMenu();
                MenuItem open = new MenuItem("Open NdalamoPDF");
                open.addActionListener(e -> Platform.runLater(onOpen));
                MenuItem watch = new MenuItem("Watch folders");
                watch.addActionListener(e -> Platform.runLater(onWatchFolders));
                MenuItem quit = new MenuItem("Quit");
                quit.addActionListener(e -> Platform.runLater(onQuit));
                menu.add(open);
                menu.add(watch);
                menu.addSeparator();
                menu.add(quit);
                icon = new TrayIcon(awtPicture, "NdalamoPDF", menu);
                icon.setImageAutoSize(true);
                // Fires on double-click, and when a notification is clicked: shortly after one, open its log.
                icon.addActionListener(e -> Platform.runLater(
                        System.currentTimeMillis() - lastNotification < 15_000 ? onWatchFolders : onOpen));
                SystemTray.getSystemTray().add(icon);
            } catch (AWTException | RuntimeException e) {
                icon = null;
            }
        });
    }

    /** A Windows notification. Only call it when the window isn't in front (SPEC §6.3). */
    public void notify(String title, String text) {
        lastNotification = System.currentTimeMillis();
        EventQueue.invokeLater(() -> {
            if (icon != null) icon.displayMessage(title, text, TrayIcon.MessageType.INFO);
        });
    }

    /** Takes the icon away (when quitting), or Windows leaves a ghost icon until the mouse passes over it. */
    public void remove() {
        EventQueue.invokeLater(() -> {
            if (icon != null) SystemTray.getSystemTray().remove(icon);
            icon = null;
        });
    }

    private static BufferedImage toAwt(Image image) {
        int width = (int) image.getWidth(), height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) out.setRGB(x, y, pixels.getArgb(x, y));
        }
        return out;
    }
}
