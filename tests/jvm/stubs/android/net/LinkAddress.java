package android.net;
public class LinkAddress {
    private final java.net.InetAddress a; private final int p;
    public LinkAddress(java.net.InetAddress a, int p) { this.a = a; this.p = p; }
    public java.net.InetAddress getAddress() { return a; }
    public int getPrefixLength() { return p; }
}
