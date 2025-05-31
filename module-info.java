module filesharing {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.media;
    requires javafx.web;
    requires jmdns;
    requires java.sql;
    requires java.desktop;
    requires java.naming;
    requires org.yaml.snakeyaml;
    requires org.apache.commons.csv;
    requires oshi.core;
    exports filesharing.main;
    exports filesharing.settings;
    exports filesharing.sync;
}