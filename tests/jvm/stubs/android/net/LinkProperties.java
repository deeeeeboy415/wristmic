package android.net;
public class LinkProperties {
    public java.util.List<LinkAddress> getLinkAddresses() {
        try { return java.util.Collections.singletonList(new LinkAddress(java.net.InetAddress.getByName("127.0.0.1"), 8)); }
        catch (Exception e) { return java.util.Collections.emptyList(); }
    }
}
