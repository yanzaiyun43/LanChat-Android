package com.lans.chat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.LinearLayout;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 9876;
    private static final int REQUEST_MANAGE_STORAGE = 1001;

    private static final byte TYPE_TEXT = 0x01;
    private static final byte TYPE_SYSTEM = 0x03;
    private static final byte TYPE_FILE_START = 0x06;
    private static final byte TYPE_FILE_CHUNK = 0x07;
    private static final byte TYPE_FILE_RESUME = 0x08;
    private static final byte TYPE_FILE_END = 0x09;
    private static final byte TYPE_PING = 0x0A;
    private static final byte TYPE_PONG = 0x0B;

    private static final int CHUNK_SIZE = 256 * 1024;
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private static final int SOCKET_TIMEOUT_MS = 90_000;
    private static final long RESUME_WAIT_MS = 15_000L;

    // UI
    private TextView infoText;
    private EditText nameField;
    private EditText passwordField;
    private EditText ipField;
    private Button btnScan;
    private Button btnServer;
    private Button btnClient;
    private LinearLayout messageContainer;
    private ScrollView scrollView;
    private EditText messageField;
    private Button btnSend;
    private Button btnFile;

    // Server state
    private ServerSocket serverSocket;
    private final List<ClientHandler> clients = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean serverRunning = false;

    // Client state
    private Socket clientSocket;
    private DataInputStream clientIn;
    private DataOutputStream clientOut;
    private volatile boolean clientConnected = false;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String[]> filePickerLauncher;

    // 心跳
    private volatile long lastPongTime;
    private Thread heartbeatThread;

    // 文件断点续传：接收会话与发送方等待的 RESUME 响应
    private final java.util.concurrent.ConcurrentHashMap<String, FileReceiveSession> receiveSessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> resumeValues = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, CountDownLatch> resumeLatches = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> pendingEndNames = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        infoText = findViewById(R.id.infoText);
        nameField = findViewById(R.id.nameField);
        passwordField = findViewById(R.id.passwordField);
        ipField = findViewById(R.id.ipField);
        btnScan = findViewById(R.id.btnScan);
        btnServer = findViewById(R.id.btnServer);
        btnClient = findViewById(R.id.btnClient);
        messageContainer = findViewById(R.id.messageContainer);
        scrollView = findViewById(R.id.scrollView);
        messageField = findViewById(R.id.messageField);
        btnSend = findViewById(R.id.btnSend);
        btnFile = findViewById(R.id.btnFile);

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) sendFile(uri);
                });

        String localIp = getLocalIp();
        infoText.setText("本机IP: " + localIp + "  端口: " + PORT);
        ipField.setText(localIp);
        nameField.setText(getDefaultName());

        btnServer.setOnClickListener(v -> toggleServer());
        btnClient.setOnClickListener(v -> toggleClient());
        btnScan.setOnClickListener(v -> scanLanServers());
        btnSend.setOnClickListener(v -> sendText());
        btnFile.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));

        updateButtonStates();
        appendMessage("系统", "一方点「开启服务端」，多人填写服务端 IP 后点「连接客户端」");
        checkStoragePermission();
    }

    private void checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                uiHandler.post(() -> {
                    infoText.setText("保存文件到公开目录需授予「所有文件访问」权限，正在跳转设置页面...");
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                });
            }
        }
    }

    // ==================== 服务端 ====================

    private void startServer() {
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            appendMessage("系统", "请输入昵称");
            return;
        }
        serverRunning = true;
        btnServer.setText("关闭服务端");
        updateButtonStates();
        startKeepService();
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                appendMessage("系统", "服务端已启动 (端口 " + PORT + ")，等待客户端连接...");
                while (serverRunning && !serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                        String clientName;
                        try {
                            clientName = in.readUTF();
                        } catch (IOException e) {
                            try { socket.close(); } catch (IOException ignored) {}
                            continue;
                        }
                        ClientHandler handler = new ClientHandler(socket, clientName, in);
                        clients.add(handler);
                        handler.start();
                        appendMessage("系统", clientName + " 加入了聊天 (" + socket.getInetAddress().getHostAddress() + ")");
                        broadcastSystem(clientName + " 加入了聊天");
                    } catch (IOException e) {
                        if (serverRunning) appendMessage("系统", "接受连接失败: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (serverRunning) appendMessage("系统", "服务端启动失败: " + e.getMessage());
            } finally {
                serverRunning = false;
                runOnUiThread(() -> {
                    btnServer.setText("开启服务端");
                    updateButtonStates();
                    stopKeepService();
                });
            }
        }, "ServerThread").start();
    }

    private void stopServer() {
        serverRunning = false;
        btnServer.setText("开启服务端");
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (ClientHandler h : clients) {
            try { h.close(); } catch (IOException ignored) {}
        }
        clients.clear();
        appendMessage("系统", "服务端已关闭");
        updateButtonStates();
        stopKeepService();
    }

    // ==================== 客户端 ====================

    private void connectClient() {
        String ip = ipField.getText().toString().trim();
        String name = nameField.getText().toString().trim();
        if (ip.isEmpty()) {
            appendMessage("系统", "请输入服务端 IP");
            return;
        }
        if (name.isEmpty()) {
            appendMessage("系统", "请输入昵称");
            return;
        }
        if (serverRunning && ip.equals(getLocalIp())) {
            appendMessage("系统", "服务端已开启，无需连接到自己");
            return;
        }
        clientConnected = true;
        btnClient.setText("断开连接");
        updateButtonStates();
        startKeepService();
        new Thread(() -> {
            try {
                appendMessage("系统", "正在连接 " + ip + ":" + PORT + " ...");
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(ip, PORT), 5000);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                clientSocket = socket;
                clientIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                clientOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                clientOut.writeUTF(name);
                clientOut.flush();
                appendMessage("系统", "已连接到服务端 " + ip);
                startHeartbeat();
                startClientReceiveLoop();
            } catch (IOException e) {
                appendMessage("系统", "连接失败: " + e.getMessage());
                runOnUiThread(() -> {
                    clientConnected = false;
                    btnClient.setText("连接客户端");
                    updateButtonStates();
                    stopKeepService();
                });
            }
        }, "ClientThread").start();
    }

    private void disconnectClient() {
        clientConnected = false;
        stopHeartbeat();
        btnClient.setText("连接客户端");
        try { if (clientIn != null) clientIn.close(); } catch (IOException ignored) {}
        try { if (clientOut != null) clientOut.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        clientIn = null;
        clientOut = null;
        clientSocket = null;
        appendMessage("系统", "已断开连接");
        updateButtonStates();
        stopKeepService();
    }

    private void startClientReceiveLoop() {
        new Thread(() -> {
            try {
                while (clientConnected && clientSocket != null && !clientSocket.isClosed()) {
                    int type = clientIn.read();
                    if (type == -1) break;
                    handleReceived((byte) type, clientIn, clientOut, null);
                }
            } catch (java.net.SocketTimeoutException e) {
                if (clientConnected) appendMessage("系统", "连接超时（心跳无响应），请检查网络");
            } catch (IOException e) {
                if (clientConnected) {
                    appendMessage("系统", "连接断开: " + e.getMessage());
                }
            } finally {
                stopHeartbeat();
                runOnUiThread(() -> {
                    clientConnected = false;
                    btnClient.setText("连接客户端");
                    updateButtonStates();
                    stopKeepService();
                });
            }
        }, "ClientReceiveThread").start();
    }

    // ==================== 接收处理（客户端与服务端共用） ====================

    private void handleReceived(byte type, DataInputStream in, DataOutputStream out, ClientHandler handler) throws IOException {
        SecretKeySpec key = getKey();
        switch (type) {
            case TYPE_TEXT: {
                String sender = in.readUTF();
                String text = readEncUtf(in, key);
                appendMessage(sender, text);
                if (handler != null) {
                    for (ClientHandler h : clients) {
                        if (h != handler) h.sendText(sender, text);
                    }
                }
                break;
            }
            case TYPE_SYSTEM: {
                String text = readEncUtf(in, key);
                appendMessage("系统", text);
                break;
            }
            case TYPE_PING: {
                if (out != null) {
                    synchronized (out) {
                        out.writeByte(TYPE_PONG);
                        out.flush();
                    }
                }
                break;
            }
            case TYPE_PONG: {
                lastPongTime = System.currentTimeMillis();
                break;
            }
            case TYPE_FILE_START: {
                handleFileStart(in, out, handler);
                break;
            }
            case TYPE_FILE_CHUNK: {
                handleFileChunk(in, out, handler, key);
                break;
            }
            case TYPE_FILE_RESUME: {
                String fileId = in.readUTF();
                int completed = in.readInt();
                if (handler != null) {
                    handler.onDownstreamResume(fileId, completed);
                } else {
                    resumeValues.put(fileId, completed);
                    CountDownLatch latch = resumeLatches.get(fileId);
                    if (latch != null) latch.countDown();
                }
                break;
            }
            case TYPE_FILE_END: {
                String fileId = in.readUTF();
                String fileName = pendingEndNames.remove(fileId);
                if (fileName != null) {
                    appendMessage("系统", "「" + fileName + "」传输完成");
                }
                break;
            }
            default:
                break;
        }
    }

    private void handleFileStart(DataInputStream in, DataOutputStream out, ClientHandler handler) throws IOException {
        String sender = in.readUTF();
        String fileId = in.readUTF();
        String fileName = in.readUTF();
        long fileLen = in.readLong();
        int chunkSize = in.readInt();
        int totalChunks = in.readInt();

        File partFile = new File(getCacheDir(), fileId + ".part");
        File progressFile = new File(getCacheDir(), fileId + ".progress");
        BitSet received = new BitSet(totalChunks);
        if (partFile.exists() && progressFile.exists() && partFile.length() == fileLen) {
            BitSet saved = loadProgress(progressFile);
            if (saved != null && saved.length() <= totalChunks) {
                received = saved;
            }
        } else {
            partFile.delete();
            progressFile.delete();
        }
        int completed = received.cardinality();

        RandomAccessFile raf = new RandomAccessFile(partFile, "rw");
        receiveSessions.put(fileId, new FileReceiveSession(sender, fileName, fileId, fileLen,
                chunkSize, totalChunks, received, raf, partFile, progressFile));

        if (completed > 0) {
            appendMessage("系统", "「" + fileName + "」检测到已有进度 " + completed + "/" + totalChunks + "，断点续传");
        }
        if (out != null) {
            synchronized (out) {
                out.writeByte(TYPE_FILE_RESUME);
                out.writeUTF(fileId);
                out.writeInt(completed);
                out.flush();
            }
        }

        if (handler != null) {
            for (ClientHandler h : clients) {
                if (h != handler) h.beginRelayAsync(sender, fileId, fileName, fileLen, chunkSize, totalChunks);
            }
        }
    }

    private void handleFileChunk(DataInputStream in, DataOutputStream out, ClientHandler handler, SecretKeySpec key) throws IOException {
        String fileId = in.readUTF();
        int index = in.readInt();
        byte[] enc = readEncBytes(in);
        FileReceiveSession session = receiveSessions.get(fileId);
        if (session != null && !session.received.get(index)) {
            try {
                byte[] plain = CryptoUtil.decrypt(key, enc);
                synchronized (session.raf) {
                    session.raf.seek((long) index * session.chunkSize);
                    session.raf.write(plain);
                }
                session.received.set(index);
                saveProgress(session.progressFile, session.received);
            } catch (Exception e) {
                throw new IOException("分片解密失败，请检查双方加密密码是否一致", e);
            }
            int done = session.received.cardinality();
            int nextReport = Math.max(1, session.totalChunks / 20);
            if (done % nextReport == 0 || done == session.totalChunks) {
                appendMessage("系统", "接收进度: " + done + "/" + session.totalChunks);
            }
            if (done == session.totalChunks) {
                finishReceive(session);
                if (out != null) {
                    synchronized (out) {
                        out.writeByte(TYPE_FILE_END);
                        out.writeUTF(fileId);
                        out.flush();
                    }
                }
            }
        }
        if (handler != null) {
            for (ClientHandler h : clients) {
                if (h != handler) h.relayChunk(fileId, index, enc);
            }
        }
    }

    private void finishReceive(FileReceiveSession session) {
        try {
            session.raf.close();
        } catch (IOException ignored) {
        }
        File outFile = uniqueTarget(new File(getReceiveDir(), session.fileName));
        try (FileInputStream fis = new FileInputStream(session.partFile);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        } catch (IOException e) {
            appendMessage("系统", "文件保存失败: " + e.getMessage());
            return;
        }
        session.partFile.delete();
        session.progressFile.delete();
        receiveSessions.remove(session.fileId);
        String msg = "[文件] " + session.fileName + " (" + formatSize(session.fileLen) + ") 已保存到 " + outFile.getAbsolutePath();
        appendMessage(session.sender, msg);
        uiHandler.post(() -> infoText.append("\n" + msg));
    }

    // ==================== 心跳 ====================

    private void startHeartbeat() {
        stopHeartbeat();
        lastPongTime = System.currentTimeMillis();
        heartbeatThread = new Thread(() -> {
            while (clientConnected && clientSocket != null && !clientSocket.isClosed()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                if (!clientConnected || clientSocket == null || clientSocket.isClosed()) return;
                if (System.currentTimeMillis() - lastPongTime > 3 * HEARTBEAT_INTERVAL_MS) {
                    appendMessage("系统", "心跳超时，连接可能不稳定");
                    lastPongTime = System.currentTimeMillis();
                    continue;
                }
                try {
                    DataOutputStream out = clientOut;
                    if (out != null) {
                        synchronized (out) {
                            out.writeByte(TYPE_PING);
                            out.flush();
                        }
                    }
                } catch (IOException ignored) {
                }
            }
        }, "HeartbeatThread");
        heartbeatThread.start();
    }

    private void stopHeartbeat() {
        Thread t = heartbeatThread;
        heartbeatThread = null;
        if (t != null) t.interrupt();
    }

    // ==================== 发送 ====================

    private void sendText() {
        String text = messageField.getText().toString().trim();
        if (text.isEmpty()) return;
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            appendMessage("系统", "请输入昵称");
            return;
        }

        if (serverRunning) {
            appendMessage("我", text);
            for (ClientHandler h : clients) {
                h.sendText(name, text);
            }
            messageField.setText("");
        } else if (clientConnected) {
            new Thread(() -> {
                try {
                    synchronized (clientOut) {
                        clientOut.writeByte(TYPE_TEXT);
                        clientOut.writeUTF(name);
                        writeEncUtf(clientOut, getKey(), text);
                        clientOut.flush();
                    }
                    appendMessage("我", text);
                    runOnUiThread(() -> messageField.setText(""));
                } catch (IOException e) {
                    appendMessage("系统", "发送失败: " + e.getMessage());
                }
            }, "SendTextThread").start();
        } else {
            appendMessage("系统", "未建立连接，无法发送");
        }
    }

    private void sendFile(Uri uri) {
        String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            appendMessage("系统", "请输入昵称");
            return;
        }
        String fileName = getDisplayName(uri);
        new Thread(() -> {
            File tempFile = null;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) {
                    appendMessage("系统", "无法读取文件");
                    return;
                }
                tempFile = File.createTempFile("send_", ".tmp", getCacheDir());
                long fileLen = 0;
                try (OutputStream os = new FileOutputStream(tempFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        fileLen += n;
                    }
                }
                is.close();

                appendMessage("我", "[文件] " + fileName + " (" + formatSize(fileLen) + ")");

                if (serverRunning) {
                    sendFileAsServer(name, fileName, fileLen, tempFile);
                } else if (clientConnected) {
                    sendFileAsClient(name, fileName, fileLen, tempFile);
                } else {
                    appendMessage("系统", "未建立连接，无法发送");
                }
            } catch (Exception e) {
                appendMessage("系统", "文件发送失败: " + e.getMessage());
            } finally {
                if (tempFile != null) tempFile.delete();
            }
        }, "SendFileThread").start();
    }

    private void sendFileAsServer(String name, String fileName, long fileLen, File tempFile) throws Exception {
        String fileId = makeFileId(fileName, fileLen);
        int totalChunks = (int) ((fileLen + CHUNK_SIZE - 1) / CHUNK_SIZE);
        SecretKeySpec key = getKey();
        pendingEndNames.put(fileId, fileName);
        CountDownLatch latch = new CountDownLatch(clients.size());
        for (ClientHandler h : clients) {
            new Thread(() -> {
                try {
                    int start = h.beginRelayAndWait(name, fileId, fileName, fileLen, CHUNK_SIZE, totalChunks);
                    if (start > 0) {
                        appendMessage("系统", h.name + " 已有 " + start + "/" + totalChunks + " 分片，断点续传");
                    }
                    transferChunks(tempFile, fileLen, start, totalChunks, fileId, key,
                            (index, enc) -> h.relayChunk(fileId, index, enc));
                } catch (Exception e) {
                    appendMessage("系统", "发送文件到 " + h.name + " 失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, "SendFile-" + h.name).start();
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendFileAsClient(String name, String fileName, long fileLen, File tempFile) throws Exception {
        DataOutputStream out = clientOut;
        if (out == null) throw new IOException("未连接");
        String fileId = makeFileId(fileName, fileLen);
        int totalChunks = (int) ((fileLen + CHUNK_SIZE - 1) / CHUNK_SIZE);
        SecretKeySpec key = getKey();
        pendingEndNames.put(fileId, fileName);
        synchronized (out) {
            out.writeByte(TYPE_FILE_START);
            out.writeUTF(name);
            out.writeUTF(fileId);
            out.writeUTF(fileName);
            out.writeLong(fileLen);
            out.writeInt(CHUNK_SIZE);
            out.writeInt(totalChunks);
            out.flush();
        }
        int start = waitResume(fileId);
        if (start > 0) {
            appendMessage("系统", "接收方已有 " + start + "/" + totalChunks + " 分片，断点续传中...");
        }
        transferChunks(tempFile, fileLen, start, totalChunks, fileId, key,
                (index, enc) -> {
                    synchronized (out) {
                        out.writeByte(TYPE_FILE_CHUNK);
                        out.writeUTF(fileId);
                        out.writeInt(index);
                        writeEncBytes(out, enc);
                        out.flush();
                    }
                });
    }

    private interface ChunkSender {
        void send(int index, byte[] enc) throws IOException;
    }

    private void transferChunks(File tempFile, long fileLen, int startChunk, int totalChunks,
                                String fileId, SecretKeySpec key, ChunkSender sender) throws Exception {
        try (FileInputStream fis = new FileInputStream(tempFile)) {
            long skip = (long) startChunk * CHUNK_SIZE;
            long skipped = 0;
            while (skipped < skip) {
                long s = fis.skip(skip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] plain = new byte[CHUNK_SIZE];
            int nextReport = Math.max(1, totalChunks / 20);
            for (int i = startChunk; i < totalChunks; i++) {
                int n = fis.read(plain);
                if (n <= 0) break;
                byte[] enc = CryptoUtil.encrypt(key, java.util.Arrays.copyOf(plain, n));
                sender.send(i, enc);
                int done = i + 1;
                if (done % nextReport == 0 || done == totalChunks) {
                    appendMessage("系统", "发送进度: " + done + "/" + totalChunks);
                }
            }
        }
    }

    private int waitResume(String fileId) {
        CountDownLatch latch = new CountDownLatch(1);
        resumeLatches.put(fileId, latch);
        try {
            latch.await(RESUME_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Integer v = resumeValues.remove(fileId);
        resumeLatches.remove(fileId);
        return v == null ? 0 : v;
    }

    // ==================== 广播 ====================

    private void broadcastSystem(String text) {
        for (ClientHandler h : clients) {
            h.sendSystem(text);
        }
    }

    // ==================== ClientHandler ====================

    private class ClientHandler extends Thread {
        private final Socket socket;
        private final String name;
        private final DataInputStream in;
        private DataOutputStream out;
        private volatile boolean running = true;

        private final java.util.concurrent.ConcurrentHashMap<String, Integer> myResumeValues = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, CountDownLatch> myResumeLatches = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, Integer> downstreamStart = new java.util.concurrent.ConcurrentHashMap<>();

        ClientHandler(Socket socket, String name, DataInputStream in) throws IOException {
            this.socket = socket;
            this.name = name;
            this.in = in;
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override
        public void run() {
            try {
                while (running && !socket.isClosed()) {
                    int type = in.read();
                    if (type == -1) break;
                    handleReceived((byte) type, in, out, this);
                }
            } catch (java.net.SocketTimeoutException e) {
                running = false;
                appendMessage("系统", name + " 心跳超时，已断开");
                broadcastSystem(name + " 心跳超时，已断开");
            } catch (IOException e) {
                // ignore - handled in finally
            } finally {
                try { out.close(); } catch (IOException ignored) {}
                try { in.close(); } catch (IOException ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
                clients.remove(this);
                if (running) {
                    appendMessage("系统", name + " 离开了聊天");
                    broadcastSystem(name + " 离开了聊天");
                }
            }
        }

        void sendText(String sender, String text) {
            if (out == null) return;
            new Thread(() -> {
                try {
                    synchronized (out) {
                        out.writeByte(TYPE_TEXT);
                        out.writeUTF(sender);
                        writeEncUtf(out, getKey(), text);
                        out.flush();
                    }
                } catch (IOException e) {
                    appendMessage("系统", "发送到 " + name + " 失败: " + e.getMessage());
                }
            }, "ForwardText-" + name).start();
        }

        void onDownstreamResume(String fileId, int completed) {
            myResumeValues.put(fileId, completed);
            CountDownLatch latch = myResumeLatches.remove(fileId);
            if (latch != null) latch.countDown();
        }

        int beginRelayAndWait(String sender, String fileId, String fileName, long fileLen,
                              int chunkSize, int totalChunks) throws IOException {
            synchronized (out) {
                out.writeByte(TYPE_FILE_START);
                out.writeUTF(sender);
                out.writeUTF(fileId);
                out.writeUTF(fileName);
                out.writeLong(fileLen);
                out.writeInt(chunkSize);
                out.writeInt(totalChunks);
                out.flush();
            }
            CountDownLatch latch = new CountDownLatch(1);
            myResumeLatches.put(fileId, latch);
            try {
                latch.await(RESUME_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Integer v = myResumeValues.remove(fileId);
            myResumeLatches.remove(fileId);
            int start = (v == null) ? 0 : v;
            downstreamStart.put(fileId, start);
            return start;
        }

        void beginRelayAsync(String sender, String fileId, String fileName, long fileLen,
                             int chunkSize, int totalChunks) {
            new Thread(() -> {
                try {
                    beginRelayAndWait(sender, fileId, fileName, fileLen, chunkSize, totalChunks);
                } catch (IOException e) {
                    appendMessage("系统", "转发文件信息到 " + name + " 失败: " + e.getMessage());
                }
            }, "RelayStart-" + name).start();
        }

        void relayChunk(String fileId, int index, byte[] enc) {
            try {
                Integer start = downstreamStart.get(fileId);
                if (start != null && index < start) return;
                synchronized (out) {
                    out.writeByte(TYPE_FILE_CHUNK);
                    out.writeUTF(fileId);
                    out.writeInt(index);
                    writeEncBytes(out, enc);
                    out.flush();
                }
            } catch (IOException e) {
                appendMessage("系统", "转发分片到 " + name + " 失败: " + e.getMessage());
            }
        }

        void sendSystem(String text) {
            if (out == null) return;
            try {
                synchronized (out) {
                    out.writeByte(TYPE_SYSTEM);
                    writeEncUtf(out, getKey(), text);
                    out.flush();
                }
            } catch (IOException e) {
                // ignore
            }
        }

        void close() throws IOException {
            running = false;
            socket.close();
        }
    }

    // ==================== 辅助 ====================

    private void appendMessage(String sender, String text) {
        uiHandler.post(() -> {
            View msgView = LayoutInflater.from(MainActivity.this).inflate(R.layout.message_item, messageContainer, false);
            TextView msgText = msgView.findViewById(R.id.messageText);
            Button copyBtn = msgView.findViewById(R.id.copyButton);
            msgText.setText("【" + sender + "】" + text);
            if (sender.equals("系统")) {
                copyBtn.setVisibility(View.GONE);
            } else {
                copyBtn.setOnClickListener(v -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("msg", text));
                    Toast.makeText(MainActivity.this, "已复制: " + text, Toast.LENGTH_SHORT).show();
                });
            }
            messageContainer.addView(msgView);
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void updateButtonStates() {
        boolean serverMode = serverRunning;
        boolean clientMode = clientConnected;
        boolean connected = serverMode || clientMode;

        btnServer.setText(serverMode ? "关闭服务端" : "开启服务端");
        btnClient.setText(clientMode ? "断开连接" : "连接客户端");
        btnServer.setEnabled(!clientMode);
        btnClient.setEnabled(!serverMode);
        btnSend.setEnabled(connected);
        btnFile.setEnabled(connected);
    }

    private void toggleServer() {
        if (serverRunning) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void toggleClient() {
        if (clientConnected) {
            disconnectClient();
        } else {
            connectClient();
        }
    }

    // ==================== 局域网扫描 ====================

    private void scanLanServers() {
        btnScan.setEnabled(false);
        btnScan.setText("扫描中...");
        appendMessage("系统", "开始扫描局域网服务器...");

        new Thread(() -> {
            List<String> foundServers = new ArrayList<>();
            try {
                String localIp = getLocalIp();
                int prefixLength = getPrefixLength();
                if (prefixLength < 0) {
                    appendMessage("系统", "无法获取子网信息，扫描失败");
                    runOnUiThread(() -> {
                        btnScan.setEnabled(true);
                        btnScan.setText("扫描");
                    });
                    return;
                }

                int ipInt = ipStringToInt(localIp);
                int mask = 0xFFFFFFFF << (32 - prefixLength);
                int network = ipInt & mask;
                int broadcast = network | ~mask;

                ExecutorService executor = Executors.newFixedThreadPool(20);
                for (int i = network + 1; i < broadcast; i++) {
                    if (i == ipInt) continue;
                    final int ip = i;
                    executor.submit(() -> {
                        String targetIp = ipIntToString(ip);
                        if (checkPortOpen(targetIp, PORT, 300)) {
                            foundServers.add(targetIp);
                        }
                    });
                }
                executor.shutdown();
                executor.awaitTermination(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                appendMessage("系统", "扫描异常: " + e.getMessage());
            }

            runOnUiThread(() -> {
                btnScan.setEnabled(true);
                btnScan.setText("扫描");
            });

            if (foundServers.isEmpty()) {
                appendMessage("系统", "扫描完成，未找到服务端");
            } else {
                appendMessage("系统", "扫描完成，找到 " + foundServers.size() + " 个服务端");
                runOnUiThread(() -> showServerListDialog(foundServers));
            }
        }, "ScanThread").start();
    }

    private void showServerListDialog(List<String> servers) {
        String[] items = servers.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("发现的服务端（点击自动连接）")
                .setItems(items, (dialog, which) -> {
                    String selectedIp = servers.get(which);
                    ipField.setText(selectedIp);
                    appendMessage("系统", "选择了服务端: " + selectedIp + "，正在连接...");
                    connectClient();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static boolean checkPortOpen(String ip, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int ipStringToInt(String ip) {
        String[] parts = ip.split("\\.");
        int result = 0;
        for (String part : parts) {
            result = (result << 8) | Integer.parseInt(part);
        }
        return result;
    }

    private static String ipIntToString(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private static int getPrefixLength() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    if (addr.getAddress() instanceof Inet4Address) {
                        return addr.getNetworkPrefixLength();
                    }
                }
            }
        } catch (SocketException ignored) {}
        return -1;
    }

    private void startKeepService() {
        Intent serviceIntent = new Intent(this, ChatService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopKeepService() {
        Intent serviceIntent = new Intent(this, ChatService.class);
        stopService(serviceIntent);
    }

    private File getReceiveDir() {
        File dir = new File("/storage/emulated/0/局域网聊天");
        if (Environment.isExternalStorageManager()) {
            try {
                if (!dir.exists()) dir.mkdirs();
                if (dir.exists()) return dir;
            } catch (Exception ignored) {}
        }
        dir = new File(getExternalFilesDir(null), "received");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String getDisplayName(Uri uri) {
        String result = "unknown_file";
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    result = cursor.getString(idx);
                }
            }
        }
        return result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            // ignore
        }
        return "127.0.0.1";
    }

    private String getDefaultName() {
        return "用户" + (int) (Math.random() * 1000);
    }

    private SecretKeySpec getKey() {
        return CryptoUtil.deriveKey(passwordField.getText().toString());
    }

    private static void writeEncBytes(DataOutputStream out, byte[] enc) throws IOException {
        out.writeInt(enc.length);
        out.write(enc);
    }

    private static byte[] readEncBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("密文长度异常");
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    private static void writeEncUtf(DataOutputStream out, SecretKeySpec key, String text) throws IOException {
        try {
            writeEncBytes(out, CryptoUtil.encrypt(key, text.getBytes("UTF-8")));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("加密失败", e);
        }
    }

    private static String readEncUtf(DataInputStream in, SecretKeySpec key) throws IOException {
        try {
            return new String(CryptoUtil.decrypt(key, readEncBytes(in)), "UTF-8");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("解密失败，请检查双方加密密码是否一致", e);
        }
    }

    private static String makeFileId(String fileName, long fileLen) {
        String raw = fileName + "|" + fileLen;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] h = digest.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static void saveProgress(File f, BitSet bs) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject(bs);
        } catch (IOException ignored) {
        }
    }

    private static BitSet loadProgress(File f) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(f))) {
            Object o = ois.readObject();
            return (o instanceof BitSet) ? (BitSet) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static File uniqueTarget(File target) {
        if (!target.exists()) return target;
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File dir = target.getParentFile();
        for (int i = 1; i < 1000; i++) {
            File f = new File(dir, base + "(" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return target;
    }

    private static class FileReceiveSession {
        final String sender;
        final String fileName;
        final String fileId;
        final long fileLen;
        final int chunkSize;
        final int totalChunks;
        final BitSet received;
        final RandomAccessFile raf;
        final File partFile;
        final File progressFile;

        FileReceiveSession(String sender, String fileName, String fileId, long fileLen,
                           int chunkSize, int totalChunks, BitSet received,
                           RandomAccessFile raf, File partFile, File progressFile) {
            this.sender = sender;
            this.fileName = fileName;
            this.fileId = fileId;
            this.fileLen = fileLen;
            this.chunkSize = chunkSize;
            this.totalChunks = totalChunks;
            this.received = received;
            this.raf = raf;
            this.partFile = partFile;
            this.progressFile = progressFile;
        }
    }

    @Override
    protected void onDestroy() {
        serverRunning = false;
        clientConnected = false;
        stopHeartbeat();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        for (ClientHandler h : clients) {
            try { h.close(); } catch (IOException ignored) {}
        }
        try { if (clientIn != null) clientIn.close(); } catch (IOException ignored) {}
        try { if (clientOut != null) clientOut.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        stopKeepService();
        super.onDestroy();
    }
}
