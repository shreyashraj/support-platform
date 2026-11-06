package com.example.support.api;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.example.support.IntegrationTestBase;

class OpenApiSpecTest extends IntegrationTestBase {

    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private OpenAPI load() {
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);
        SwaggerParseResult result = new OpenAPIParser().readLocation("openapi.yaml", null, opts);
        Assertions.assertTrue(result.getMessages() == null || result.getMessages().isEmpty(),
                "openapi.yaml has problems: " + result.getMessages());
        return result.getOpenAPI();
    }

    @Test
    void specIsValidOpenApi() {
        OpenAPI api = load();
        Assertions.assertTrue(api.getOpenapi().startsWith("3."));
        Assertions.assertFalse(api.getPaths().isEmpty());
    }

    /** The contract and the code must expose exactly the same operations. */
    @Test
    void everyImplementedOperationIsInTheSpecAndViceVersa() {
        Set<String> implemented = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String path : info.getPathPatternsCondition().getPatternValues()) {
                if (!path.startsWith("/v1/")) continue;
                for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                    implemented.add(m + " " + path);
                }
            }
        }
        Set<String> documented = new TreeSet<>();
        load().getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, op) -> documented.add(method + " " + path)));
        Assertions.assertEquals(documented, implemented);
    }

    @Test
    void everyOperationDocumentsItsRequiredScopeAndAnErrorModel() {
        load().getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
            Assertions.assertNotNull(op.getOperationId(), method + " " + path + " has no operationId");
            Assertions.assertNotNull(op.getExtensions() == null ? null : op.getExtensions().get("x-required-scopes"),
                    method + " " + path + " has no x-required-scopes");
            Assertions.assertTrue(op.getResponses().containsKey("401"), method + " " + path + " lacks 401");
            Assertions.assertTrue(op.getResponses().containsKey("429"), method + " " + path + " lacks 429");
        }));
    }
}
