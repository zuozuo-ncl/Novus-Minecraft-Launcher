package com.mclauncher;

import com.mclauncher.launcher.LauncherApp;
import com.mclauncher.launcher.LoggingConfigurator;
import javafx.application.Application;

public class Main {

    public static void main(String[] args) {

        

        LoggingConfigurator.configure();

        Application.launch(LauncherApp.class, args);
    }
}
