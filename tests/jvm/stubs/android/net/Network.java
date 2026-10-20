package android.net;
public class Network {
    public javax.net.SocketFactory getSocketFactory() { return javax.net.SocketFactory.getDefault(); }
    public java.net.InetAddress getByName(String h) throws java.net.UnknownHostException { return java.net.InetAddress.getByName(h); }
    public void bindSocket(java.net.DatagramSocket s) {}
}
