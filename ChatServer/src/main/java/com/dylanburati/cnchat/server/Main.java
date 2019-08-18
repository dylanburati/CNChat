package com.dylanburati.cnchat.server;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class Main {
    public static void main(String[] args) throws IOException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        System.out.format("\nCNChat started at %s\n\n", dateFormat.format(new Date()));

        String workingDirectory = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getFile()).
                getParent() + System.getProperty("file.separator");
        String preferenceStorePath = workingDirectory + "user_preferences.json";

        final String configPath = workingDirectory + "config.json";

        new ChatServer(configPath, preferenceStorePath);
    }
}
