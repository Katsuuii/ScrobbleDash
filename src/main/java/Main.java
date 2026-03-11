
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import java.net.URL;
public class Main extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/fxml/Dashboard.fxml"));
        Scene scene = new Scene(loader.load(), 900, 560);

        URL css = Main.class.getResource("/css/lastfm-theme.css");
        if (css == null) {
            System.err.println("CSS not found! Expected: /css/lastfm-theme.css");
        } else {
            scene.getStylesheets().add(css.toExternalForm());
        }

        stage.setTitle("ScrobbleDash (Last.fm)");
        stage.setScene(scene);
        stage.show();
    }

}
