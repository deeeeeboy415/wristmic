package android.net;
public class ConnectivityManager {
    public static class NetworkCallback { public void onAvailable(Network n) {} public void onLost(Network n) {} }
    public volatile boolean offerWifi = false;
    public volatile int requests = 0, releases = 0;
    public void requestNetwork(NetworkRequest r, final NetworkCallback cb) {
        requests++;
        if (offerWifi) new Thread(() -> { try { Thread.sleep(300); } catch (InterruptedException e) {} cb.onAvailable(new Network()); }).start();
    }
    public void unregisterNetworkCallback(NetworkCallback cb) { releases++; }
    public LinkProperties getLinkProperties(Network n) { return new LinkProperties(); }
    public NetworkCapabilities getNetworkCapabilities(Network n) { return new NetworkCapabilities(); }
    public Network getActiveNetwork() { return null; }
}
