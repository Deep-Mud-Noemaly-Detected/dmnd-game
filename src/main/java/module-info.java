module org.example.dmndgame {
    requires javafx.controls;
    requires javafx.fxml;


    opens org.example.dmndgame to javafx.fxml;
    exports org.example.dmndgame;
}