package com.disciple.basedworld.cnchat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TableRow;
import android.widget.TextView;

import com.disciple.basedworld.cnchat.ChatUtils.ChatCrypt;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import static com.disciple.basedworld.cnchat.ChatUtils.Codecs.base64decode;
import static com.disciple.basedworld.cnchat.ChatUtils.Codecs.base64encode;


public class MainActivity extends AppCompatActivity {

    Runnable reckoner = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };
    private ServiceConnection serviceConnection;
    private Resources res;
    private SharedPreferences prefs;

    private final String errLogFile = "err_log";
    private final Object errLogLock = new Object();
    private Button errLogShow;
    private TableRow errLogContainer;
    private TextView errLog;

    private RelativeLayout chatView;
    private ScrollView configView;
    private Animation fromChatView;
    private Animation toConfigView;
    private Animation fromConfigView;
    private Animation toChatView;

    private TextView power;
    private TextView stdOut;
    private EditText textPane;
    private ScrollView scrollView;
    private EditText serverPref;
    private EditText customNamePref;
    private int layoutHeight;

    private Handler uiHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            scrollView.setBackgroundColor(msg.what);
        }
    };
    private String hostName = "";
    private String userName;
    private String uuid = null;
    private Object chatState = null;
    private boolean markdown = false;
    private final java.util.List<String> userNames = new ArrayList<>();
    private final Random random = new Random();

    private URL host;
    private HttpURLConnection conn;
    private final MessageHandler md = new MessageHandler();
    private final Object cipherLock = new Object();
    private final Object outQueueLock = new Object();
    private volatile java.util.List<String> outQueue = new ArrayList<>();
    private Cipher cipherD;
    private Cipher cipherE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler(new LoggingExceptionHandler(this, errLogFile, errLogLock));
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Intent intent = new Intent(this, ShutdownHook.class);
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                ShutdownHook.LocalBinder binder = (ShutdownHook.LocalBinder) service;
                binder.onTaskRemovedHandler.setCallback(reckoner);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        bindService(intent, serviceConnection, 0);
        startService(intent);
        res = getResources();
        prefs = getSharedPreferences("prefs", 0);
        hostName = prefs.getString("hostName", "");

        errLogShow = (Button) findViewById(R.id.errLogShow);
        errLogContainer = (TableRow) findViewById(R.id.errLogContainer);
        errLog = (TextView) findViewById(R.id.errLog);

        chatView = (RelativeLayout) findViewById(R.id.chatView);
        configView = (ScrollView) findViewById(R.id.configView);
        fromChatView = AnimationUtils.loadAnimation(this, R.anim.t2_left_out);
        toConfigView = AnimationUtils.loadAnimation(this, R.anim.t2_right_in);
        fromConfigView = AnimationUtils.loadAnimation(this, R.anim.t1_right_out);
        toChatView = AnimationUtils.loadAnimation(this, R.anim.t1_left_in);
        AnimationListeners al = new AnimationListeners();
        fromChatView.setAnimationListener(al.fromChatViewListener);
        toConfigView.setAnimationListener(al.toConfigViewListener);
        fromConfigView.setAnimationListener(al.fromConfigViewListener);
        toChatView.setAnimationListener(al.toChatViewListener);

        power = (TextView) findViewById(R.id.power);
        stdOut = (TextView) findViewById(R.id.stdOut);
        textPane = (EditText) findViewById(R.id.textPane);
        scrollView = (ScrollView) findViewById(R.id.scrollView);
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                Rect r = new Rect();
                getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
                layoutHeight = r.height();
            }
        });
        scrollView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                Rect r = new Rect();
                getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
                if(r.height() < layoutHeight) {
                    scrollView.post(new Runnable() {
                        @Override
                        public void run() {
                            scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        }
                    });
                }
                layoutHeight = r.height();
            }
        });
        serverPref = (EditText) findViewById(R.id.serverPref);
        serverPref.setText(hostName);
        customNamePref = (EditText) findViewById(R.id.customNamePref);
        String nameRequest = prefs.getString("userName", "");
        if(nameRequest.matches("[^\\n:]+")) {
            userNames.add(nameRequest);
            customNamePref.setText(nameRequest);
        } else {
            Collections.addAll(userNames, "Lil B", "KenM", "Ken Bone", "Tai Lopez", "Hugh Mungus",
                    "Donald Trump", "Hillary Clinton", "Jesus", "VN", "Uncle Phil",
                    "Watery Westin", "A Wild KB");
        }
        Switch markdownPref = (Switch) findViewById(R.id.markdownPref);
        markdownPref.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(chatState == null) {
                    markdown = isChecked;
                }
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("markdown", isChecked);
                editor.apply();
            }
        });
        markdown = prefs.getBoolean("markdown", false);
        markdownPref.setChecked(markdown);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(serviceConnection);
        if(chatState != null) {
            enqueue((char) 4 + userName);
            try {
                md.sendAndReceive();
            } catch(Throwable ignored) {
            }
            chatState = null;
        }
    }

    private void refresh() throws IOException {
        conn = (HttpURLConnection) host.openConnection();
    }

    private void enqueue(String outputLine) {
        byte[] data = outputLine.getBytes(Charset.forName("UTF-8"));
        try {
            byte[] enc;
            synchronized(cipherLock) {
                enc = cipherE.doFinal(data);
            }
            synchronized(outQueueLock) {
                outQueue.add(base64encode(enc));
            }
        } catch(IllegalBlockSizeException | BadPaddingException e) {
            Log.d("CNChat", "enqueue", e);
        }
    }

    private String decrypt(String input) throws IOException {
        try {
            byte[] data = base64decode(input);
            synchronized(cipherLock) {
                data = cipherD.doFinal(data);
            }
            return new String(data, Charset.forName("UTF-8"));
        } catch(IllegalBlockSizeException | BadPaddingException e) {
            Log.d("CNChat", "decrypt", e);
            return null;
        }
    }

    public void animation2(View v) {
        chatView.startAnimation(fromChatView);
        configView.startAnimation(toConfigView);
    }

    public void animation1(View v) {
        configView.startAnimation(fromConfigView);
        chatView.startAnimation(toChatView);
    }

    public void serverSave(View v) {
        String newHostName = String.valueOf(serverPref.getText());
        if(chatState == null) {
            hostName = newHostName;
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("hostName", newHostName);
        editor.apply();
    }

    public void customNameSave(View v) {
        String newUserName = String.valueOf(customNamePref.getText());
        if(chatState == null) {
            userNames.clear();
            userNames.add(newUserName);
        }
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("userName", newUserName);
        editor.apply();
    }

    public void errLogShow(View v) {
        if(errLogContainer.getVisibility() == View.GONE) {
            errLog.setText("");
            BufferedReader in = null;
            try {
                try {
                    synchronized(errLogLock) {
                        in = new BufferedReader(new InputStreamReader(openFileInput(errLogFile), Charset.forName("UTF-8")));
                        String errLine;
                        while((errLine = in.readLine()) != null) {
                            errLog.append(errLine);
                        }
                    }
                } catch(FileNotFoundException e) {
                    errLog.setText(R.string.errLogNotFound);
                    Log.d("CNChat", "errLogShow FileNotFound", e);
                } finally {
                    if(in != null) {
                        in.close();
                    }
                }
            } catch(IOException e) {
                Log.d("CNChat", "errLogShow", e);
            }
            errLogContainer.setVisibility(View.VISIBLE);
            errLogShow.setText(res.getString(R.string.hide));
        } else {
            errLogContainer.setVisibility(View.GONE);
            errLogShow.setText(res.getString(R.string.show));
        }
    }

    public void errLogClear(View v) {
        if(errLogContainer.getVisibility() != View.GONE) {
            errLogContainer.setVisibility(View.GONE);
            errLogShow.setText(res.getString(R.string.show));
        }
        synchronized(errLogLock) {
            PrintWriter out = null;
            try {
                out = new PrintWriter(openFileOutput(errLogFile, Context.MODE_PRIVATE), true);
                out.print("");
            } catch(FileNotFoundException e) {
                Log.d("CNChat", "errLogClear FileNotFound", e);
            } finally {
                if(out != null) {
                    out.close();
                }
            }
        }
    }

    public void chatAction(View v) {
        if(chatState == null) {
            chatSessionStart(v);
        } else if(cipherE != null) {
            String input = String.valueOf(textPane.getText());
            textPane.setText("");
            input = input.replaceAll("[\\x00-\\x07\\x0e-\\x1f\\x7f-\\x9f]", "");
            if(input.contains(":quit")) {
                finish();
            } else if(input.contains(":username ")) {
                String changeRequest = input.substring(input.lastIndexOf(":username ") + 10);
                if(!changeRequest.equals(userName) && changeRequest.matches("[^\\n:]+")) {
                    enqueue((char) 26 + userName + (char) 26 + changeRequest);
                    userName = changeRequest;
                    setTitle("CN Chat: " + userName);
                }
            } else if(input.contains(":format")) {
                enqueue("" + (char) 17);
            } else if(input.contains(":unformat")) {
                enqueue("" + (char) 17 + (char) 17);
            } else if(!input.matches("[ \\t\\xA0\\u1680\\u180e\\u2000-\\u200a\\u202f\\u205f\\u3000" +
                    "\\n\\x0B\\f\\r\\x85\\u2028\\u2029]*")) {
                if(input.startsWith(":dm ")) {
                    enqueue((char) 15 + userName + ": " + input);
                } else enqueue(userName + ": " + input);
            }
        }
    }

    public void chatSessionStart(View v) {
        if(chatState != null) {
            if(cipherE != null) {
                finish();
            }
            return;
        }

        chatState = new Object();
        userName = userNames.remove(random.nextInt(userNames.size()));
        setTitle("CN Chat: " + userName);
        power.setBackgroundColor(Color.parseColor("#23EC153F"));
        power.setText(res.getString(R.string.power1));
        stdOut.setText(res.getString(R.string.message1, hostName));
        stdOut.append("\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                chatSession();
            }
        }).start();
    }

    private void chatSession() {
        try {
            int portNumber = 8080;
            host = new URL("http", hostName, portNumber, "");
            AsyncTask<Void, Void, String> initHandshake = new AsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackground(Void... params) {
                    String recvUuid = null;
                    try {
                        refresh();
                        conn.setDoOutput(true);
                        PrintWriter out = null;
                        try {
                            out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), Charset.forName("UTF-8")), true);
                            long mask = -1L >>> 1;
                            String check = String.format("%01x%015x%016x", random.nextInt(8) + 8, random.nextLong() & (mask >> 4), random.nextLong() & mask);
                            out.print(check);
                        } finally {
                            if(out != null) out.close();
                        }
                        BufferedReader in = null;
                        try {
                            in = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
                            recvUuid = in.readLine();
                        } finally {
                            if(in != null) in.close();
                        }
                        conn.disconnect();
                    } catch(IOException e) {
                        Log.d("CNChat", "initHandshake", e);
                    }
                    return recvUuid;
                }
            };

            try {
                initHandshake.execute();
                uuid = initHandshake.get();
            } catch(ExecutionException | InterruptedException e) {
                Log.d("CNChat", "initHandshake ext", e);
            }
            if(uuid == null) {
                chatState = null;
                throw new RuntimeException("Server did not provide required data to start the encryption handshake.");
            }
            host = new URL(host.getProtocol(), host.getHost(), host.getPort(), "/" + uuid);
            try {
                synchronized(cipherLock) {
                    ChatCrypt chatCrypt = new ChatCrypt(host);
                    cipherD = chatCrypt.cipherD;
                    cipherE = chatCrypt.cipherE;
                    enqueue((char) 6 + userName);
                    if(markdown) {
                        enqueue("" + (char) 17);
                    }
                }
            } catch(Exception e) {
                chatState = null;
                throw new RuntimeException("ChatCrypt: " + e.getMessage());
            }

            boolean up = true;
            while(up && chatState != null) {
                up = false;
                try {
                    up = md.sendAndReceive();
                    Thread.sleep(16);
                } catch(IOException e) {
                    Log.d("CNChat", "sendAndReceive", e);
                } catch(InterruptedException ignored) {
                }
            }
        } catch(IOException e) {
            Log.d("CNChat", "chatSession", e);
        }
    }

    class MessageHandler {
        private final Runnable rainbowRun = new Runnable() {
            @Override
            public void run() {
                float[] hsv = new float[3];
                hsv[0] = 0.0f;
                hsv[2] = 1.0f;
                try {
                    for(int i = 0; i < 361; i++) {
                        hsv[0] += 1.0f;
                        hsv[1] = (float) Math.sin(i * Math.PI / 360.0);
                        uiHandler.sendEmptyMessage(Color.HSVToColor(hsv));
                        Thread.sleep(5L);
                    }
                } catch(InterruptedException ignored) {
                }
            }
        };

        private Thread rainbow = new Thread();

        private boolean sendAndReceive() throws IOException {
            AsyncTask<Void, String, Boolean> netTask = new AsyncTask<Void, String, Boolean>() {
                private boolean sane = true;

                @Override
                protected Boolean doInBackground(Void... params) {
                    try {
                        refresh();
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        PrintWriter out = null;
                        try {
                            out = new PrintWriter(new OutputStreamWriter(conn.getOutputStream(), Charset.forName("UTF-8")), true);
                            synchronized(outQueueLock) {
                                while(outQueue.size() > 0) {
                                    out.print(outQueue.remove(0));
                                    if(outQueue.size() > 0) out.print("\r\n");
                                }
                            }
                        } finally {
                            if(out != null) out.close();
                        }

                        String input;
                        BufferedReader in = null;
                        try {
                            in = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
                            while((input = in.readLine()) != null) {
                                String message = decrypt(input);
                                publishProgress(message);
                                if(!sane) break;
                            }
                        } finally {
                            if(in != null) in.close();
                        }
                        conn.disconnect();
                        return sane;
                    } catch(IOException e) {
                        Log.d("CNChat", "netTask", e);
                        return false;
                    }
                }

                @Override
                protected void onProgressUpdate(String... values) {
                    super.onProgressUpdate(values);
                    sane = handleMessage(values[0]);
                    if(sane) {
                        scrollView.post(new Runnable() {
                            @Override
                            public void run() {
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                            }
                        });
                    }
                }
            };
            boolean retval = false;
            try {
                netTask.execute();
                retval = netTask.get();
            } catch(InterruptedException | ExecutionException e) {
                Log.d("CNChat", "netTask ext", e);
            }
            return retval;
        }

        private boolean handleMessage(String message) {
            if(message == null) return false;
            final int header = message.isEmpty() ? -1 : message.codePointAt(0);
            if(header == 21) {
                if(message.length() == 1) {
                    if(!userNames.isEmpty()) {
                        userName = userNames.remove(random.nextInt(userNames.size()));
                    } else {
                        userName = Integer.toString(36 * 36 * 36 + random.nextInt(35 * 36 * 36 * 36), 36);
                    }
                    enqueue((char) 6 + userName);
                } else {
                    userName = message.substring(1);
                    SpannableString serverMessage = new SpannableString(res.getString(R.string.usernameE));
                    serverMessage.setSpan(new ForegroundColorSpan(Color.parseColor("#DF00A100")), 0, serverMessage.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    serverMessage.setSpan(new TypefaceSpan("monospace"), 0, serverMessage.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    serverMessage.setSpan(new RelativeSizeSpan(0.84f), 0, serverMessage.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    stdOut.append(serverMessage);
                    stdOut.append("\n");
                }
                setTitle("CN Chat: " + userName);
                return true;
            }
            boolean format = (message.length() != (message = message.replace("" + (char) 17, "")).length());
            SpannableString styledMessage = null;
            if(message.length() != (message = message.replaceAll("[\\x0e\\x0f\\x7f-\\x9f]", "")).length()) {
                styledMessage = new SpannableString(message);
                styledMessage.setSpan(new ForegroundColorSpan(Color.parseColor("#DF5100F1")), 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if(message.length() != (message = message.replaceAll("[\\x00-\\x07\\x10-\\x1f]", "")).length()) {
                SpannableString serverMessage = new SpannableString(message);
                serverMessage.setSpan(new ForegroundColorSpan(Color.parseColor("#DF00A100")), 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                serverMessage.setSpan(new TypefaceSpan("monospace"), 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                serverMessage.setSpan(new RelativeSizeSpan(0.84f), 0, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                stdOut.append(serverMessage);
                stdOut.append("\n");
                return true;
            }
            final String command = message.toLowerCase();
            if(command.contains(":color ")) {
                if(rainbow.isAlive()) rainbow.interrupt();
                if(command.contains("white") || command.contains("reset")) {
                    uiHandler.sendEmptyMessage(Color.WHITE);
                } else if(command.contains("rainbow")) {
                    rainbow = new Thread(rainbowRun);
                    rainbow.start();
                } else {
                    final int ccIndex = command.lastIndexOf(":color ") + 7;
                    if(command.contains(",")) {
                        final String[] rgb = command.substring(ccIndex).replaceAll("[^,0-9]", "").split(",");
                        if(rgb.length == 3) {
                            int r = Integer.parseInt(rgb[0]);
                            int g = Integer.parseInt(rgb[1]);
                            int b = Integer.parseInt(rgb[2]);
                            if(r / 256 == 0 && g / 256 == 0 && b / 256 == 0)
                                uiHandler.sendEmptyMessage(Color.argb(255, r, g, b));
                        }
                    }
                    if(command.length() >= ccIndex + 6) {
                        final String customColor = command.substring(ccIndex, ccIndex + 6);
                        if(customColor.matches("[0-9a-f]{6}"))
                            uiHandler.sendEmptyMessage((255 << 24) | Integer.parseInt(customColor, 16));
                    }
                }
            } else {
                if(styledMessage == null) {
                    styledMessage = new SpannableString(message);
                }
                if(format) {
                    SpannableStringBuilder styledMessageBuilder = new SpannableStringBuilder(styledMessage);
                    int[] formatMap = new int[message.length()];
                    for(String regex : new String[]{"(?<!\\\\)(\\*\\*).+(?<!\\\\)(\\*\\*)", "(?<!\\\\)(__).+(?<!\\\\)(__)",
                            "(?<!\\\\)(\\*)[^\\*]+(?<!\\\\|\\*)(\\*)", "(?<!\\\\)(_)[^_]+(?<!\\\\|_)(_)",
                            "(?<!\\\\)(`)[^`]+(?<!\\\\)(`)"}) {
                        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(styledMessageBuilder);
                        boolean backtick = regex.contains("`");
                        while(m.find()) {
                            int currentAction = backtick ? 4 : m.end(1) - m.start();
                            boolean redundant = !backtick;
                            for(int i = m.end(1); redundant && i < m.start(2); i++) {
                                redundant = ((formatMap[i] & currentAction) != 0);
                            }
                            if(redundant) continue;
                            for(int i = m.start(); i < m.end(1); i++) {
                                formatMap[i] |= -1;
                                styledMessageBuilder.replace(i, i + 1, "\000");
                            }
                            for(int i = m.end(1); i < m.start(2); i++) {
                                formatMap[i] |= currentAction;
                            }
                            switch(currentAction) {
                                case 4:
                                    styledMessageBuilder.setSpan(new TypefaceSpan("monospace"), m.end(1), m.start(2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    styledMessageBuilder.setSpan(new RelativeSizeSpan(0.84f), m.end(1), m.start(2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    break;
                                case 2:
                                    styledMessageBuilder.setSpan(new StyleSpan(Typeface.BOLD), m.end(1), m.start(2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                                    break;
                                case 1:
                                    styledMessageBuilder.setSpan(new StyleSpan(Typeface.ITALIC), m.end(1), m.start(2), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            }
                            for(int i = m.start(2); i < m.end(); i++) {
                                formatMap[i] |= -1;
                                styledMessageBuilder.replace(i, i + 1, "\000");
                            }
                        }
                    }
                    for(String escregex : new String[]{"\\\\(?=\\*{1,2}|_{1,2}|`)"}) {
                        Matcher esc = Pattern.compile(escregex).matcher(styledMessageBuilder);
                        while(esc.find()) {
                            formatMap[esc.start()] |= -1;
                        }
                    }
                    for(int i = 0; i < formatMap.length; ) {
                        if(i == -1) {
                            formatMap = intArrayRemove(formatMap, i);
                            styledMessageBuilder.replace(i, i + 1, "");
                        } else {
                            i++;
                        }
                    }
                    stdOut.append(styledMessageBuilder);
                } else {
                    stdOut.append(styledMessage);
                }
                stdOut.append("\n");
            }
            return true;
        }
    }

    private static int[] intArrayRemove(int[] a, int i) {
        System.arraycopy(a, i + 1, a, i, a.length - i - 1);
        return Arrays.copyOf(a, a.length - 1);
    }


    class AnimationListeners {
        private Animation.AnimationListener fromChatViewListener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                chatView.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        };
        private Animation.AnimationListener toConfigViewListener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                configView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        };
        private Animation.AnimationListener fromConfigViewListener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                configView.setVisibility(View.GONE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        };
        private Animation.AnimationListener toChatViewListener = new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                chatView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        };
    }
}
