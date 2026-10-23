package com.jurislpo.juristrack.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The client's address is believed from {@code X-Forwarded-For} only behind a trusted proxy, and then it is the right-most hop that is not one. */
class TrustedProxiesTest {

    private final TrustedProxies proxies = TrustedProxies.of(List.of("127.0.0.1", "::1", "10.1.0.0/16"));

    @Test
    void aCallerThatIsNotAProxyCannotChooseItsAddress() {
        assertThat(proxies.clientAddress("203.0.113.9", "1.2.3.4", "5.6.7.8")).isEqualTo("203.0.113.9");
        assertThat(proxies.clientAddress("10.2.0.1", "1.2.3.4", null)).as("10.2.0.1 is outside 10.1.0.0/16").isEqualTo("10.2.0.1");
    }

    @Test
    void behindAProxyTheClientIsTheRightMostHopThatIsNotAProxy() {
        assertThat(proxies.clientAddress("127.0.0.1", "198.51.100.7", null)).isEqualTo("198.51.100.7");
        // the client wrote "1.2.3.4" itself; the proxy appended the real address
        assertThat(proxies.clientAddress("127.0.0.1", "1.2.3.4, 198.51.100.7", null)).isEqualTo("198.51.100.7");
        assertThat(proxies.clientAddress("127.0.0.1", "198.51.100.7, 10.1.4.4", null)).isEqualTo("198.51.100.7");
        assertThat(proxies.clientAddress("127.0.0.1", null, "198.51.100.8")).isEqualTo("198.51.100.8");
        assertThat(proxies.clientAddress("127.0.0.1", null, null)).isEqualTo("127.0.0.1");
    }

    @Test
    void anythingThatIsNotAnAddressIsNotBelieved() {
        assertThat(proxies.clientAddress("127.0.0.1", "unknown", null)).isEqualTo("127.0.0.1");
        assertThat(proxies.clientAddress("127.0.0.1", "1.2.3.4, <script>", null)).isEqualTo("127.0.0.1");
    }

    @Test
    void aHostNameIsRefusedInTheConfiguration() {
        assertThatThrownBy(() -> TrustedProxies.of(List.of("proxy.internal"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustedProxies.of(List.of("10.0.0.0/40"))).isInstanceOf(IllegalArgumentException.class);
    }
}
