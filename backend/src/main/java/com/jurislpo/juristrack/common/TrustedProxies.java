package com.jurislpo.juristrack.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The proxies that stand in front of the API ({@code juristrack.security.trusted-proxies}: IP addresses or CIDR ranges such as
 * {@code 10.0.0.0/8}) and the one rule they need: the {@code X-Forwarded-For} header is believed only when the connection itself comes
 * from a trusted proxy, and then the client is the right-most hop that is not a trusted proxy. Whatever a client writes into the header
 * stands to the left of what the proxies add, so it never becomes the client's address. A request from any other address is taken as
 * it is (its own connection address).
 */
public final class TrustedProxies {

    private static final Pattern ADDRESS_LITERAL = Pattern.compile("^[0-9a-fA-F:.]+$");

    private record Network(byte[] address, int prefixLength) {

        boolean contains(byte[] candidate) {
            if (candidate.length != address.length) return false;
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) if (candidate[i] != address[i]) return false;
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) return true;
            int mask = 0xFF << (8 - remainingBits);
            return (candidate[fullBytes] & mask) == (address[fullBytes] & mask);
        }
    }

    private final List<Network> networks;

    private TrustedProxies(List<Network> networks) {
        this.networks = networks;
    }

    /** Parses addresses and CIDR ranges (literals only, never a host name: no DNS lookup); {@link IllegalArgumentException} for anything else. */
    public static TrustedProxies of(List<String> proxies) {
        List<Network> networks = new ArrayList<>();
        for (String proxy : proxies) {
            String trimmedProxy = proxy.trim();
            if (trimmedProxy.isEmpty()) continue;
            int slashIndex = trimmedProxy.indexOf('/');
            String addressText = slashIndex < 0 ? trimmedProxy : trimmedProxy.substring(0, slashIndex);
            byte[] address = bytesOf(addressText);
            if (address == null) throw new IllegalArgumentException("juristrack.security.trusted-proxies: \"" + trimmedProxy + "\" is not an IP address or CIDR range");
            int prefixLength = address.length * 8;
            if (slashIndex >= 0) {
                try {
                    prefixLength = Integer.parseInt(trimmedProxy.substring(slashIndex + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("juristrack.security.trusted-proxies: \"" + trimmedProxy + "\" has no valid prefix length");
                }
                if (prefixLength < 0 || prefixLength > address.length * 8) {
                    throw new IllegalArgumentException("juristrack.security.trusted-proxies: \"" + trimmedProxy + "\" has no valid prefix length");
                }
            }
            networks.add(new Network(address, prefixLength));
        }
        return new TrustedProxies(List.copyOf(networks));
    }

    /** True when {@code address} (an IP literal) is one of the trusted proxies. */
    public boolean isTrusted(String address) {
        byte[] bytes = bytesOf(address);
        return bytes != null && networks.stream().anyMatch(network -> network.contains(bytes));
    }

    /**
     * The client's address: {@code remoteAddress} (the connection) unless it is a trusted proxy, then the right-most hop of
     * {@code forwardedFor} that is not trusted (else {@code realIp}); anything that is not an IP address is ignored.
     */
    public String clientAddress(String remoteAddress, String forwardedFor, String realIp) {
        if (remoteAddress == null || !isTrusted(remoteAddress)) return remoteAddress;
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (bytesOf(hop) == null) return remoteAddress; // not an address: do not guess
                if (!isTrusted(hop)) return hop;
            }
            return remoteAddress; // every hop is one of our proxies
        }
        return realIp != null && bytesOf(realIp.trim()) != null && !isTrusted(realIp.trim()) ? realIp.trim() : remoteAddress;
    }

    private static byte[] bytesOf(String literal) {
        if (literal == null || literal.length() > 45 || !ADDRESS_LITERAL.matcher(literal).matches()) return null;
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
