package com.example.support.unit;

import com.example.support.upstream.UpstreamUnavailableException;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class EntitlementFeedTest {

    private static Map<?, ?> parse(String xml) throws Exception {
        Method m = Class.forName("com.example.support.upstream.EntitlementClient").getDeclaredMethod("parseFeed", String.class);
        m.setAccessible(true);
        try {
            return (Map<?, ?>) m.invoke(null, xml);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void parsesTheDocumentedShape() throws Exception {
        var out = parse("<accounts><account id=\"ACC-7\"><tier>Platinum</tier><renewal>2027-01-15</renewal></account></accounts>");
        Assertions.assertTrue(out.containsKey("ACC-007"));
    }

    @Test
    void refusesDocumentsWithEntityDeclarations() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<accounts><account id=\"ACC-1\"><tier>&x;</tier></account></accounts>";
        Assertions.assertThrows(UpstreamUnavailableException.class, () -> parse(xxe));
    }
}
