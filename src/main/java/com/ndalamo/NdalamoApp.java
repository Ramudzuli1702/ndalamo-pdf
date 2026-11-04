package com.ndalamo;

import com.ndalamo.models.Settings;
import com.ndalamo.screens.Header;
import com.ndalamo.screens.LogoMark;
import com.ndalamo.services.JobService;
import com.ndalamo.services.SettingsService;
import com.ndalamo.utils.Theme;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts Ndalamo: loads the settings, starts the background job service and
 * opens the main window. Screens are added from chunk 2 onwards.
 *
 * Run during development with: mvn javafx:run
 */
public class NdalamoApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(NdalamoApp.class);

    private JobService jobService;

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((thread, e) ->
                log.error("Uncaught exception on {}", thread.getName(), e));

        Settings settings = new SettingsService().load();
        jobService = new JobService(settings.workerThreads());
        log.info("Ndalamo started with {} worker thread(s)", settings.workerThreads());

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: " + Theme.BG + ";");
        root.setTop(new Header());

        stage.setTitle("Ndalamo");
        stage.getIcons().addAll(LogoMark.windowIcons());
        stage.setMinWidth(1024);
        stage.setMinHeight(700);
        stage.setScene(new Scene(root, 1280, 800));
        stage.show();
    }

    @Override
    public void stop() {
        if (jobService != null) jobService.shutdown();
        log.info("Ndalamo closed");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
