package com.lans.chat;

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
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int PORT = 9876;
    private static final int REQUEST_MANAGE_STORAGE = 1001;

    private static final byte TYPE_TEXT = 0x01;
    private static final byte TYPE_FILE = 0x02;
    private static final byte TYPE_SYSTEM = 0x03;
    private static final byte TYPE_NAME = 0x04;

    // UI
    private TextView infoText;
    private EditText nameField;
    private EditText ipField;
    private Button btnScan;
    private Button btnServer;
    private Button btnClient;
    private TextView messageArea;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        infoText = findViewById(R.id.infoText);
        nameField = findViewById(R.id.nameField);
        ipField = findViewById(R.id.ipField);
        btnScan = findViewById(R.id.btnScan);
        btnServer = findViewById(R.id.btnServer);
        btnClient = findViewById(R.id.btnClient);
        messageArea = findViewById(R.id.messageArea);
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
                clientSocket = socket;
                clientIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                clientOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                clientOut.writeUTF(name);
                clientOut.flush();
                appendMessage("系统", "已连接到服务端 " + ip);
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
                    if (type == TYPE_TEXT) {
                        String sender = clientIn.readUTF();
                        String text = clientIn.readUTF();
                        appendMessage(sender, text);
                    } else if (type == TYPE_FILE) {
                        String sender = clientIn.readUTF();
                        String fileName = clientIn.readUTF();
                        long fileLen = clientIn.readLong();
                        receiveFile(sender, fileName, fileLen);
                    } else if (type == TYPE_SYSTEM) {
                        String text = clientIn.readUTF();
                        appendMessage("系统", text);
                    }
                }
            } catch (IOException e) {
                if (clientConnected) {
                    appendMessage("系统", "连接断开: " + e.getMessage());
                }
            } finally {
                runOnUiThread(() -> {
                    clientConnected = false;
                    btnClient.setText("连接客户端");
                    updateButtonStates();
                    stopKeepService();
                });
            }
        }, "ClientReceiveThread").start();
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
                        clientOut.writeUTF(text);
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
                    CountDownLatch latch = new CountDownLatch(clients.size());
                    for (ClientHandler h : clients) {
                        h.sendFile(name, fileName, fileLen, tempFile, latch);
                    }
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else if (clientConnected) {
                    synchronized (clientOut) {
                        clientOut.writeByte(TYPE_FILE);
                        clientOut.writeUTF(name);
                        clientOut.writeUTF(fileName);
                        clientOut.writeLong(fileLen);
                        try (FileInputStream fis = new FileInputStream(tempFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = fis.read(buf)) != -1) {
                                clientOut.write(buf, 0, n);
                            }
                        }
                        clientOut.flush();
                    }
                } else {
                    appendMessage("系统", "未建立连接，无法发送");
                }
            } catch (IOException e) {
                appendMessage("系统", "文件发送失败: " + e.getMessage());
            } finally {
                if (tempFile != null) tempFile.delete();
            }
        }, "SendFileThread").start();
    }

    // ==================== 接收文件 ====================

    private void receiveFile(String sender, String fileName, long fileLen) throws IOException {
        File dir = getReceiveDir();
        File outFile = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[8192];
            long remaining = fileLen;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = clientIn.read(buf, 0, toRead);
                if (read == -1) break;
                fos.write(buf, 0, read);
                remaining -= read;
            }
        }
        String msg = "[文件] " + fileName + " (" + formatSize(fileLen) + ") 已保存到 " + outFile.getAbsolutePath();
        appendMessage(sender, msg);
        uiHandler.post(() -> infoText.append("\n" + msg));
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
                    if (type == TYPE_TEXT) {
                        String sender = in.readUTF();
                        String text = in.readUTF();
                        appendMessage(sender, text);
                        for (ClientHandler h : clients) {
                            if (h != this) h.sendText(sender, text);
                        }
                    } else if (type == TYPE_FILE) {
                        String sender = in.readUTF();
                        String fileName = in.readUTF();
                        long fileLen = in.readLong();
                        appendMessage(sender, "[文件] " + fileName + " (" + formatSize(fileLen) + ") 正在接收...");
                        File tempFile = File.createTempFile("recv_", ".tmp", getCacheDir());
                        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                            byte[] buf = new byte[8192];
                            long remaining = fileLen;
                            while (remaining > 0) {
                                int toRead = (int) Math.min(buf.length, remaining);
                                int read = in.read(buf, 0, toRead);
                                if (read == -1) break;
                                fos.write(buf, 0, read);
                                remaining -= read;
                            }
                        }
                        // Save locally
                        File outFile = new File(getReceiveDir(), fileName);
                        try (FileInputStream fis = new FileInputStream(tempFile);
                             FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = fis.read(buf)) != -1) {
                                fos.write(buf, 0, n);
                            }
                        }
                        appendMessage(sender, "[文件] " + fileName + " (" + formatSize(fileLen) + ") 已保存到 " + outFile.getAbsolutePath());
                        uiHandler.post(() -> infoText.append("\n[文件] " + fileName + " 已保存到 " + outFile.getAbsolutePath()));
                        // Broadcast to all other clients
                        CountDownLatch latch = new CountDownLatch(clients.size() - 1);
                        for (ClientHandler h : clients) {
                            if (h != this) h.sendFile(sender, fileName, fileLen, tempFile, latch);
                        }
                        try {
                            latch.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        tempFile.delete();
                    }
                }
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
                        out.writeUTF(text);
                        out.flush();
                    }
                } catch (IOException e) {
                    appendMessage("系统", "发送到 " + name + " 失败: " + e.getMessage());
                }
            }, "ForwardText-" + name).start();
        }

        void sendFile(String sender, String fileName, long fileLen, File tempFile, CountDownLatch latch) {
            new Thread(() -> {
                try {
                    synchronized (out) {
                        out.writeByte(TYPE_FILE);
                        out.writeUTF(sender);
                        out.writeUTF(fileName);
                        out.writeLong(fileLen);
                        try (FileInputStream fis = new FileInputStream(tempFile)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = fis.read(buf)) != -1) {
                                out.write(buf, 0, n);
                            }
                        }
                        out.flush();
                    }
                } catch (IOException e) {
                    appendMessage("系统", "发送文件到 " + name + " 失败: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            }, "ForwardFile-" + name).start();
        }

        void sendSystem(String text) {
            if (out == null) return;
            try {
                synchronized (out) {
                    out.writeByte(TYPE_SYSTEM);
                    out.writeUTF(text);
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
            messageArea.append("【" + sender + "】" + text + "\n");
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

    @Override
    protected void onDestroy() {
        serverRunning = false;
        clientConnected = false;
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
