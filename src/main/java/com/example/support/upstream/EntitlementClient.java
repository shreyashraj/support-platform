package com.example.support.upstream;

import com.example.support.domain.Account;
import com.example.support.domain.AccountIds;
import com.example.support.domain.SupportTier;
import java.io.IOException;
import java.io.StringReader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * NetSuite-ish: a CSV export is the system of record (name, ARR, contract end), and a smaller XML feed carries
 * fresher tier and renewal data. Where both have a value, the feed wins. The feed is optional: if it is down we
 * serve CSV values rather than fail.
 */
public class EntitlementClient {
    private static final Logger log = LoggerFactory.getLogger(EntitlementClient.class);

    private record Snapshot(Map<String, Account> byId, Instant fetchedAt) {}

    private record FeedEntry(String tier, LocalDate renewal) {}

    private final RestClient http;
    private final Guard guard;
    private final Clock clock;
    private final Duration ttl;
    private final ReentrantLock refresh = new ReentrantLock();
    private volatile Snapshot cached;

    public EntitlementClient(RestClient http, Guard guard, Clock clock, Duration ttl) {
        this.http = http;
        this.guard = guard;
        this.clock = clock;
        this.ttl = ttl;
    }

    public Optional<Account> find(String accountId) {
        return Optional.ofNullable(all().get(AccountIds.normalize(accountId)));
    }

    public Map<String, Account> all() {
        Snapshot s = cached;
        if (s != null && s.fetchedAt().plus(ttl).isAfter(clock.instant())) return s.byId();
        refresh.lock();
        try {
            s = cached;
            if (s != null && s.fetchedAt().plus(ttl).isAfter(clock.instant())) return s.byId();
            cached = new Snapshot(load(), clock.instant());
            return cached.byId();
        } finally {
            refresh.unlock();
        }
    }

    public void invalidate() {
        cached = null;
    }

    private Map<String, Account> load() {
        String csv = guard.call(true, () -> http.get().uri("/export/entitlements.csv").retrieve().body(String.class));
        Map<String, FeedEntry> feed = Map.of();
        try {
            String xml = guard.call(true, () -> http.get().uri("/feed/accounts.xml").retrieve().body(String.class));
            feed = parseFeed(xml);
        } catch (UpstreamUnavailableException | UpstreamClientException e) {
            log.warn("entitlement feed unavailable, using CSV values only: {}", e.getMessage());
        }
        return merge(csv, feed);
    }

    private static Map<String, Account> merge(String csv, Map<String, FeedEntry> feed) {
        Map<String, Account> out = new HashMap<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).get()
                .parse(new StringReader(csv))) {
            for (CSVRecord r : parser) {
                String id = AccountIds.normalize(r.get("account_id"));
                FeedEntry f = feed.get(id);
                LocalDate contractEnd = parseDate(r.get("contract_end"));
                SupportTier tier = SupportTier.parse(f != null && f.tier() != null ? f.tier() : r.get("support_tier"));
                LocalDate renewal = f != null && f.renewal() != null ? f.renewal() : contractEnd;
                out.put(id, new Account(id, r.get("name"), tier, contractEnd, renewal, Long.parseLong(r.get("arr_usd"))));
            }
        } catch (IOException | RuntimeException e) {
            throw new UpstreamUnavailableException("entitlements", UpstreamUnavailableException.Kind.ERROR, e);
        }
        return out;
    }

    static Map<String, FeedEntry> parseFeed(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            var doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList accounts = doc.getElementsByTagName("account");
            Map<String, FeedEntry> out = new HashMap<>();
            for (int i = 0; i < accounts.getLength(); i++) {
                Element a = (Element) accounts.item(i);
                out.put(AccountIds.normalize(a.getAttribute("id")),
                        new FeedEntry(text(a, "tier"), parseDate(text(a, "renewal"))));
            }
            return out;
        } catch (Exception e) {
            throw new UpstreamUnavailableException("entitlements", UpstreamUnavailableException.Kind.ERROR, e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList n = parent.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static LocalDate parseDate(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }
}
