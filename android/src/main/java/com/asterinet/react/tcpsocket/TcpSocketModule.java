package com.asterinet.react.tcpsocket;


import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Base64;
import android.net.Network;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.Callback;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

public class TcpSocketModule extends ReactContextBaseJavaModule implements TcpReceiverTask.OnDataReceivedListener {
    private static final String TAG = "TcpSockets";
    private static final int N_THREADS = 2;
    private final ReactApplicationContext mReactContext;
    private final ConcurrentHashMap<Integer, TcpSocketClient> socketClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Network> mNetworkMap = new ConcurrentHashMap<>();
    private final CurrentNetwork currentNetwork = new CurrentNetwork();
    private final ExecutorService executorService = Executors.newFixedThreadPool(N_THREADS);

    public TcpSocketModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mReactContext = reactContext;
    }

    @Override
    public @NonNull
    String getName() {
        return TAG;
    }

    private void sendEvent(String eventName, WritableMap params) {
        mReactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    /**
     * Creates a TCP Socket and establish a connection with the given host
     *
     * @param cId     socket ID
     * @param host    socket IP address
     * @param port    socket port to be bound
     * @param options extra options
     */
    @SuppressLint("StaticFieldLeak")
    @SuppressWarnings("unused")
    @ReactMethod
    public void connect(@NonNull final Integer cId, @NonNull final String host, @NonNull final Integer port, @NonNull final ReadableMap options) {
        new GuardedAsyncTask<Void, Void>(mReactContext.getExceptionHandler()) {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                TcpSocketClient client = socketClients.get(cId);
                if (client != null) {
                    onError(cId, TAG + "createSocket called twice with the same id.");
                    return;
                }
                try {
                    // Get the network interface
                    final String localAddress = options.hasKey("localAddress") ? options.getString("localAddress") : null;
                    final String iface = options.hasKey("interface") ? options.getString("interface") : null;
                    selectNetwork(iface, localAddress);
                    client = new TcpSocketClient(TcpSocketModule.this, cId, null);
                    socketClients.put(cId, client);
                    client.connect(mReactContext, host, port, options, currentNetwork.getNetwork());
                    onConnect(cId, host, port);
                } catch (Exception e) {
                    onError(cId, e.getMessage());
                }
            }
        }.executeOnExecutor(executorService);
    }

    @SuppressLint("StaticFieldLeak")
    @SuppressWarnings("unused")
    @ReactMethod
    public void write(@NonNull final Integer cId, @NonNull final String base64String, @Nullable final Callback callback) {
        new GuardedAsyncTask<Void, Void>(mReactContext.getExceptionHandler()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                TcpSocketClient socketClient = socketClients.get(cId);
                if (socketClient == null) {
                    return;
                }
                try {
                    socketClient.write(Base64.decode(base64String, Base64.NO_WRAP));
                    if (callback != null) {
                        callback.invoke();
                    }
                } catch (IOException e) {
                    if (callback != null) {
                        callback.invoke(e.toString());
                    }
                    onError(cId, e.toString());
                }
            }
        }.executeOnExecutor(executorService);
    }

    @SuppressLint("StaticFieldLeak")
    @SuppressWarnings("unused")
    @ReactMethod
    public void writeImage(@NonNull final Integer cId, @NonNull final String base64String, @Nullable final ReadableMap options, @Nullable final Callback callback) {
        new GuardedAsyncTask<Void, Void>(mReactContext.getExceptionHandler()) {
            private Vector<Byte> Command = null;

            private void addStrToCommand(String str) {
                byte[] bs = null;
                if (!str.equals("")) {
                    try {
                        bs = str.getBytes("GB2312");
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                    for (int i = 0; i < bs.length; i++) {
                        this.Command.add(Byte.valueOf(bs[i]));
                    }
                }
            }

            public Vector<Byte> getCommand() {
                return this.Command;
            }

            @Override
            protected void doInBackgroundGuarded(Void... params) {
                TcpSocketClient socketClient = socketClients.get(cId);
                if (socketClient == null) {
                    return;
                }
                try {
                    int nWidth = 490;
                    int labelWidth = 70;
                    int labelHeight = 100;
                    if(options!=null){
                        nWidth = options.hasKey("width") ? options.getInt("width") : 490;
                        labelWidth = options.hasKey("label_width")?options.getInt("label_width") : 70;
                        labelHeight = options.hasKey("label_height")?options.getInt("label_height") : 100;
                    }

                    byte[] bytes = Base64.decode(base64String, Base64.DEFAULT);
                    Bitmap mBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    int nMode = 0;
                    if (mBitmap != null) {
                        int width = (nWidth + 7) / 8 * 8;
                        int height = mBitmap.getHeight() * width / mBitmap.getWidth();
                        Log.d("BMP", "bmp.getWidth() " + mBitmap.getWidth());
                        Bitmap grayBitmap = PrintUtils.toGrayscale(mBitmap);
                        Bitmap rszBitmap = PrintUtils.resizeImage(grayBitmap, width, height);
                        byte[] src = PrintUtils.bitmapToBWPix(rszBitmap);
                        height = src.length / width;
                        width /= 8;

                        this.Command = new Vector(4096, 1024);

                        byte[] bs = null;

                        String str = "SIZE " + labelWidth + " mm, " + labelHeight + " mm\r\nDIRECTION 1\r\nCLS\r\nBITMAP 0,0," + width + "," + height + "," + 0 + ",";
                        this.addStrToCommand(str);
                        byte[] codecontent = PrintUtils.pixToTscCmd(src);

                        for (int k = 0; k < codecontent.length; ++k) {
                            this.Command.add(Byte.valueOf(codecontent[k]));
                        }

                        Log.d("TSCCommand", "codecontent" + codecontent);
                        addStrToCommand("\r\nPRINT 1,1\r\n");

                        Vector<Byte> finalBytes = this.getCommand();
                        byte[] tosend = new byte[finalBytes.size()];
                        for(int i=0;i<finalBytes.size();i++){
                            tosend[i]= finalBytes.get(i);
                        }

                        socketClient.write(tosend);
                    }

                    if (callback != null) {
                        callback.invoke();
                    }
                } catch (IOException e) {
                    if (callback != null) {
                        callback.invoke(e.toString());
                    }
                    onError(cId, e.toString());
                }
            }
        }.executeOnExecutor(executorService);
    }

    @SuppressLint("StaticFieldLeak")
    @SuppressWarnings("unused")
    @ReactMethod
    public void end(final Integer cId) {
        new GuardedAsyncTask<Void, Void>(mReactContext.getExceptionHandler()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                TcpSocketClient socketClient = socketClients.get(cId);
                if (socketClient == null) {
                    return;
                }
                socketClient.close();
                socketClients.remove(cId);
            }
        }.executeOnExecutor(executorService);
    }

    @SuppressWarnings("unused")
    @ReactMethod
    public void destroy(final Integer cId) {
        end(cId);
    }

    @SuppressLint("StaticFieldLeak")
    @SuppressWarnings("unused")
    @ReactMethod
    public void listen(final Integer cId, final ReadableMap options) {
        new GuardedAsyncTask<Void, Void>(mReactContext.getExceptionHandler()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                try {
                    TcpSocketServer server = new TcpSocketServer(socketClients, TcpSocketModule.this, cId, options);
                    socketClients.put(cId, server);
                    int port = options.getInt("port");
                    String host = options.getString("host");
                    onConnect(cId, host, port);
                } catch (Exception uhe) {
                    onError(cId, uhe.getMessage());
                }
            }
        }.executeOnExecutor(executorService);
    }

    @SuppressWarnings("unused")
    @ReactMethod
    public void setNoDelay(@NonNull final Integer cId, final boolean noDelay) {
        final TcpSocketClient client = socketClients.get(cId);
        if (client == null) {
            onError(cId, TAG + "socket not found.");
            return;
        }
        try {
            client.setNoDelay(noDelay);
        } catch (IOException e) {
            onError(cId, e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    @ReactMethod
    public void setKeepAlive(@NonNull final Integer cId, final boolean enable, final int initialDelay) {
        final TcpSocketClient client = socketClients.get(cId);
        if (client == null) {
            onError(cId, TAG + "socket not found.");
            return;
        }
        try {
            client.setKeepAlive(enable, initialDelay);
        } catch (IOException e) {
            onError(cId, e.getMessage());
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void requestNetwork(final int transportType) throws InterruptedException {
        final NetworkRequest.Builder requestBuilder = new NetworkRequest.Builder();
        requestBuilder.addTransportType(transportType);
        final CountDownLatch awaitingNetwork = new CountDownLatch(1); // only needs to be counted down once to release waiting threads
        final ConnectivityManager cm = (ConnectivityManager) mReactContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        cm.requestNetwork(requestBuilder.build(), new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                currentNetwork.setNetwork(network);
                awaitingNetwork.countDown(); // Stop waiting
            }

            @Override
            public void onUnavailable() {
                awaitingNetwork.countDown(); // Stop waiting
            }
        });
        // Timeout if there the network is unreachable
        ScheduledThreadPoolExecutor exec = new ScheduledThreadPoolExecutor(1);
        exec.schedule(new Runnable() {
            public void run() {
                awaitingNetwork.countDown(); // Stop waiting
            }
        }, 5, TimeUnit.SECONDS);
        awaitingNetwork.await();
    }

    // REQUEST NETWORK

    /**
     * Returns a network given its interface name:
     * "wifi" -> WIFI
     * "cellular" -> Cellular
     * etc...
     */
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void selectNetwork(@Nullable final String iface, @Nullable final String ipAddress) throws InterruptedException, IOException {
        currentNetwork.setNetwork(null);
        if (iface == null) return;
        if (ipAddress != null) {
            final Network cachedNetwork = mNetworkMap.get(iface + ipAddress);
            if (cachedNetwork != null) {
                currentNetwork.setNetwork(cachedNetwork);
                return;
            }
        }
        switch (iface) {
            case "wifi":
                requestNetwork(NetworkCapabilities.TRANSPORT_WIFI);
                break;
            case "cellular":
                requestNetwork(NetworkCapabilities.TRANSPORT_CELLULAR);
                break;
            case "ethernet":
                requestNetwork(NetworkCapabilities.TRANSPORT_ETHERNET);
                break;
        }
        if (currentNetwork.getNetwork() == null) {
            throw new IOException("Interface " + iface + " unreachable");
        } else if (ipAddress != null && !ipAddress.equals("0.0.0.0"))
            mNetworkMap.put(iface + ipAddress, currentNetwork.getNetwork());
    }

    // TcpReceiverTask.OnDataReceivedListener

    @Override
    public void onConnect(Integer id, String host, int port) {
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        WritableMap addressParams = Arguments.createMap();
        addressParams.putString("address", host);
        addressParams.putInt("port", port);
        eventParams.putMap("address", addressParams);

        sendEvent("connect", eventParams);
    }

    @Override
    public void onData(Integer id, byte[] data) {
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putString("data", Base64.encodeToString(data, Base64.NO_WRAP));

        sendEvent("data", eventParams);
    }

    @Override
    public void onClose(Integer id, String error) {
        if (error != null) {
            onError(id, error);
        }
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putBoolean("hadError", error != null);

        sendEvent("close", eventParams);
    }

    @Override
    public void onError(Integer id, String error) {
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putString("error", error);

        sendEvent("error", eventParams);
    }

    @Override
    public void onConnection(Integer serverId, Integer clientId, InetSocketAddress socketAddress) {
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", serverId);

        WritableMap infoParams = Arguments.createMap();
        infoParams.putInt("id", clientId);

        final InetAddress address = socketAddress.getAddress();

        WritableMap addressParams = Arguments.createMap();
        addressParams.putString("address", address.getHostAddress());
        addressParams.putInt("port", socketAddress.getPort());
        addressParams.putString("family", address instanceof Inet6Address ? "IPv6" : "IPv4");

        infoParams.putMap("address", addressParams);
        eventParams.putMap("info", infoParams);

        sendEvent("connection", eventParams);
    }

    private static class CurrentNetwork {
        @Nullable
        Network network = null;

        private CurrentNetwork() {
        }

        @Nullable
        private Network getNetwork() {
            return network;
        }

        private void setNetwork(@Nullable final Network network) {
            this.network = network;
        }
    }
}
