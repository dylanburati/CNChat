package com.disciple.basedworld.cnchat;

import android.content.Context;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

class LoggingExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final Context ctx;
    private final String logFile;
    private final Object logFileLock;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    LoggingExceptionHandler(Context ctx, String logFile, final Object logFileLock) {
        this.ctx = ctx;
        this.logFile = logFile;
        this.logFileLock = logFileLock;
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        PrintWriter out = null;
        try {
            synchronized(logFileLock) {
                out = new PrintWriter(new OutputStreamWriter(ctx.openFileOutput(logFile, Context.MODE_APPEND), Charset.forName("UTF-8")), true);
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", Locale.US);
                out.println(dateFormat.format(new Date(System.currentTimeMillis())) + "\n");
                e.printStackTrace(out);
                out.println();
            }
        } catch(FileNotFoundException e1) {
            Log.d("CNChat", "LoggingExceptionHandler.uncaughtException FileNotFound", e);
        } finally {
            if(out != null) {
                out.close();
            }
        }
        defaultHandler.uncaughtException(t, e);
    }
}
