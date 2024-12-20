import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.Stack;

public class ProxyClient extends Application {
    private TextField urlField;
    private WebView webView;
    private WebEngine webEngine;
    private String currentUrl;
    private Stack<String> backStack = new Stack<>();
    private Stack<String> forwardStack = new Stack<>();
    private String proxyUrl;
    private int proxyPort;
    private ProgressBar loadingProgress;
    private Label statusLabel;

    @Override
    public void start(Stage primaryStage) {
        loadConfig();
        setupUI(primaryStage);
    }

    private void loadConfig() {
        try (InputStream inputStream = new FileInputStream("config/configClent.json");
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            StringBuilder jsonContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }

            JSONObject jsonObject = new JSONObject(jsonContent.toString());
            proxyUrl = jsonObject.getString("url");
            proxyPort = jsonObject.getInt("port");
        } catch (Exception e) {
            System.err.println("Error loading config: " + e.getMessage());
            proxyUrl = "localhost";
            proxyPort = 1234;
        }
    }

    private void setupUI(Stage primaryStage) {
        primaryStage.setTitle("Web Browser");

        VBox root = new VBox(5);
        root.setPadding(new Insets(5));

        // Navigation bar
        HBox navBar = new HBox(5);
        Button backBtn = new Button("←");
        Button forwardBtn = new Button("→");
        Button refreshBtn = new Button("↻");
        urlField = new TextField();
        Button goBtn = new Button("Go");

        backBtn.setOnAction(e -> goBack());
        forwardBtn.setOnAction(e -> goForward());
        refreshBtn.setOnAction(e -> refresh());
        goBtn.setOnAction(e -> loadUrl(urlField.getText()));
        urlField.setOnAction(e -> loadUrl(urlField.getText()));

        HBox.setHgrow(urlField, Priority.ALWAYS);
        navBar.getChildren().addAll(backBtn, forwardBtn, refreshBtn, urlField, goBtn);

        // Progress and status bar
        HBox statusBar = new HBox(5);
        loadingProgress = new ProgressBar(0);
        statusLabel = new Label("Ready");
        statusBar.getChildren().addAll(loadingProgress, statusLabel);
        HBox.setHgrow(loadingProgress, Priority.ALWAYS);

        // Web view
        webView = new WebView();
        webEngine = webView.getEngine();
        VBox.setVgrow(webView, Priority.ALWAYS);

        root.getChildren().addAll(navBar, webView, statusBar);

        setupWebEngineListeners();

        Scene scene = new Scene(root, 1024, 768);
        primaryStage.setScene(scene);
        primaryStage.show();

        // Load initial page
        loadUrl("http://" + proxyUrl + ":" + proxyPort);
    }

    private void setupWebEngineListeners() {
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            switch (newState) {
                case RUNNING:
                    loadingProgress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                    statusLabel.setText("Loading...");
                    break;
                case SUCCEEDED:
                    loadingProgress.setProgress(1);
                    statusLabel.setText("Done");
                    String location = webEngine.getLocation();
                    if (location != null && !location.equals(currentUrl)) {
                        updateHistory(location);
                    }
                    break;
                case FAILED:
                    loadingProgress.setProgress(0);
                    statusLabel.setText("Failed to load page");
                    break;
                default:
                    break;
            }
        });

        webEngine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (newLoc != null) {
                urlField.setText(newLoc);
            }
        });
    }

    private void loadUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }

        try {
            URL targetUrl = new URL(url);
            String finalUrl = "http://" + proxyUrl + ":" + proxyPort + targetUrl.getPath() +
                    (targetUrl.getQuery() != null ? "?" + targetUrl.getQuery() : "");
            webEngine.load(finalUrl);
            currentUrl = url;
            urlField.setText(url);
        } catch (MalformedURLException e) {
            showError("Invalid URL: " + e.getMessage());
        }
    }

    private void updateHistory(String url) {
        if (currentUrl != null && !currentUrl.equals(url)) {
            backStack.push(currentUrl);
            forwardStack.clear();
        }
        currentUrl = url;
    }

    private void goBack() {
        if (!backStack.isEmpty()) {
            forwardStack.push(currentUrl);
            loadUrl(backStack.pop());
        }
    }

    private void goForward() {
        if (!forwardStack.isEmpty()) {
            backStack.push(currentUrl);
            loadUrl(forwardStack.pop());
        }
    }

    private void refresh() {
        webEngine.reload();
    }

    private void showError(String message) {
        statusLabel.setText("Error: " + message);
        webEngine.loadContent(
                "<html><body><h1>Error</h1><p>" + message + "</p></body></html>",
                "text/html"
        );
    }

    public static void main(String[] args) {
        launch(args);
    }
}