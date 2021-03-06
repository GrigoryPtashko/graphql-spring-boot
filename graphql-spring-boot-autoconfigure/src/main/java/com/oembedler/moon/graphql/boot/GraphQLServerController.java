/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 oEmbedler Inc. and Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 *  documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 *  rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 *  persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.oembedler.moon.graphql.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import com.oembedler.moon.graphql.engine.GraphQLSchemaHolder;
import graphql.ErrorType;
import graphql.ExceptionWhileDataFetching;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.preparsed.PreparsedDocumentEntry;
import graphql.introspection.IntrospectionQuery;
import graphql.language.SourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.transaction.Transactional;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static graphql.ErrorType.DataFetchingException;

/**
 * @author <a href="mailto:java.lang.RuntimeException@gmail.com">oEmbedler Inc.</a>
 */
@RestController
@RequestMapping("${graphql.server.mapping:/graphql}")
public class GraphQLServerController {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLServerController.class);

    public static final String DEFAULT_QUERY_KEY = "query";
    public static final String DEFAULT_VARIABLES_KEY = "variables";
    public static final String DEFAULT_OPERATION_NAME_KEY = "operationName";
    public static final String DEFAULT_DATA_KEY = "data";
    public static final String DEFAULT_FILENAME_UPLOAD_KEY = "file";
    public static final String DEFAULT_ERRORS_KEY = "errors";
    public static final String HEADER_SCHEMA_NAME = "graphql-schema";

    // ---
    private final GraphQLProperties graphQLProperties;
    private final GraphQLSchemaLocator graphQLSchemaLocator;
    private final Cache<String, PreparsedDocumentEntry> queryDocsCache;
    private ObjectMapper objectMapper = new ObjectMapper();

    public GraphQLServerController(GraphQLProperties graphQLProperties, GraphQLSchemaLocator graphQLSchemaLocator) {
        this.graphQLProperties = graphQLProperties;
        this.graphQLSchemaLocator = graphQLSchemaLocator;

        this.queryDocsCache = Caffeine.newBuilder().maximumSize(10_000).build();
        ;
    }

    // ---

    @RequestMapping(value = "/schema", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> getSchema(
        @RequestHeader(value = HEADER_SCHEMA_NAME, required = false) String graphQLSchemaName) {

        final GraphQLSchemaHolder graphQLSchemaHolder = getGraphQLSchemaContainer(graphQLSchemaName);
        GraphQL gql =
            GraphQL.newGraphQL(graphQLSchemaHolder.getGraphQLSchema())
                .preparsedDocumentProvider(queryDocsCache::get)
                .build();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put(
            DEFAULT_DATA_KEY,
            gql.execute(IntrospectionQuery.INTROSPECTION_QUERY).getData());

        return ResponseEntity.ok(result);
    }


    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> getJson(
        @RequestParam(DEFAULT_QUERY_KEY) String query,
        @RequestParam(value = DEFAULT_VARIABLES_KEY, required = false) String variables,
        @RequestParam(value = DEFAULT_OPERATION_NAME_KEY, required = false) String operationName,
        @RequestHeader(value = HEADER_SCHEMA_NAME, required = false) String graphQLSchemaName,

        HttpServletRequest httpServletRequest) throws IOException {

        final GraphQLContext graphQLContext = new GraphQLContext();
        graphQLContext.setHttpRequest(httpServletRequest);

        final Map<String, Object> result = evaluateAndBuildResponseMap(query, operationName, graphQLContext, decodeIntoMap(variables), graphQLSchemaName);
        return ResponseEntity.ok(result);
    }

    @RequestMapping(method = RequestMethod.POST, consumes = "application/graphql")
    public ResponseEntity<Map<String, Object>> postGraphQL(@RequestBody String query,
                                                           @RequestParam(value = DEFAULT_OPERATION_NAME_KEY, required = false) String operationName,
                                                           @RequestHeader(value = HEADER_SCHEMA_NAME, required = false) String graphQLSchemaName,
                                                           HttpServletRequest httpServletRequest) {

        final GraphQLContext graphQLContext = new GraphQLContext();
        graphQLContext.setHttpRequest(httpServletRequest);

        final Map<String, Object> result = evaluateAndBuildResponseMap(query, operationName, graphQLContext, new HashMap<>(), graphQLSchemaName);
        return ResponseEntity.ok(result);
    }

    @RequestMapping(method = RequestMethod.POST, consumes = "application/json")
    public ResponseEntity<Map<String, Object>> postJson(@RequestBody Map<String, Object> body,
                                                        @RequestHeader(value = HEADER_SCHEMA_NAME, required = false) String graphQLSchemaName,
                                                        HttpServletRequest httpServletRequest) {

        final String query = (String) body.get(getQueryKey());
        final String operationName = (String) body.get(DEFAULT_OPERATION_NAME_KEY);

        Map<String, Object> variables = null;
        Object variablesObject = body.get(getVariablesKey());
        if (variablesObject != null && variablesObject instanceof Map)
            variables = (Map<String, Object>) variablesObject;

        final GraphQLContext graphQLContext = new GraphQLContext();
        graphQLContext.setHttpRequest(httpServletRequest);

        final Map<String, Object> result = evaluateAndBuildResponseMap(query, operationName, graphQLContext, variables, graphQLSchemaName);

        return ResponseEntity.ok(result);
    }

    @RequestMapping(method = RequestMethod.POST, consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam(DEFAULT_FILENAME_UPLOAD_KEY) MultipartFile file,
                                                          @RequestParam(DEFAULT_QUERY_KEY) String query,
                                                          @RequestParam(value = DEFAULT_VARIABLES_KEY, required = false) String variables,
                                                          @RequestParam(value = DEFAULT_OPERATION_NAME_KEY, required = false) String operationName,
                                                          @RequestHeader(value = HEADER_SCHEMA_NAME, required = false) String graphQLSchemaName,
                                                          HttpServletRequest httpServletRequest) throws IOException {

        final GraphQLContext graphQLContext = new GraphQLContext();
        graphQLContext.setUploadedFile(file);
        graphQLContext.setHttpRequest(httpServletRequest);

        final Map<String, Object> result = evaluateAndBuildResponseMap(query, operationName, graphQLContext, decodeIntoMap(variables), graphQLSchemaName);
        return ResponseEntity.ok(result);
    }

    @RequestMapping(method = RequestMethod.POST, consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Map<String, Object>> uploadSmallFile(@RequestParam(DEFAULT_QUERY_KEY) String query,
                                                               @RequestParam(value = DEFAULT_VARIABLES_KEY, required = false) String variables,
                                                               @RequestParam(value = DEFAULT_OPERATION_NAME_KEY, required = false) String operationName,
                                                               @RequestHeader(value = HEADER_SCHEMA_NAME, required = false) String graphQLSchemaName,
                                                               HttpServletRequest httpServletRequest) throws IOException {

        final GraphQLContext graphQLContext = new GraphQLContext();
        graphQLContext.setHttpRequest(httpServletRequest);

        final Map<String, Object> result = evaluateAndBuildResponseMap(query, operationName, graphQLContext, decodeIntoMap(variables), graphQLSchemaName);
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> decodeIntoMap(final String variablesParam) throws IOException {
        return objectMapper.readValue(variablesParam, Map.class);
    }

    private Map<String, Object> evaluateAndBuildResponseMap(final String query,
                                                            final String operationName,
                                                            final GraphQLContext graphQLContext,
                                                            final Map<String, Object> variables,
                                                            final String graphQLSchemaName) {
        final Map<String, Object> result = new LinkedHashMap<>();
        final GraphQLSchemaHolder graphQLSchemaHolder = getGraphQLSchemaContainer(graphQLSchemaName);
        final ExecutionResult executionResult = evaluate(query, operationName, graphQLContext, variables, graphQLSchemaHolder);

        if (executionResult.getErrors().size() > 0) {
            List<GraphQLError> errors = new LinkedList<>();
            for (GraphQLError error : executionResult.getErrors()) {
                if (error instanceof ExceptionWhileDataFetching &&
                    ((ExceptionWhileDataFetching) error).getException().getCause() != null &&
                    ((ExceptionWhileDataFetching) error).getException().getCause() instanceof InvocationTargetException &&
                    ((InvocationTargetException) ((ExceptionWhileDataFetching) error).getException().getCause()).getTargetException() != null) {
                    GraphQLError newError = new GraphQLError() {
                        @Override
                        public String getMessage() {
                            return ((InvocationTargetException) ((ExceptionWhileDataFetching) error).getException()
                                    .getCause()).getTargetException().getMessage();
                        }

                        @Override
                        public List<SourceLocation> getLocations() {
                            return null;
                        }

                        @Override
                        public ErrorType getErrorType() {
                            return DataFetchingException;
                        }
                    };

                    errors.add(newError);
                } else {
                    errors.add(error);
                }
            }
            result.put(DEFAULT_ERRORS_KEY, errors);
            result.put(DEFAULT_DATA_KEY, null);
            LOGGER.error("Errors: {}", executionResult.getErrors());
            LOGGER.error("Errors for client: {}", errors);
        } else {
            result.put(DEFAULT_DATA_KEY, executionResult.getData());
        }

        return result;
    }

    private ExecutionResult evaluate(
        final String query,
        final String operationName,
        final GraphQLContext graphQLContext,
        final Map<String, Object> variables,
        final GraphQLSchemaHolder graphQLSchemaHolder) {

        ExecutionResult executionResult;

        if (graphQLSchemaHolder == null) {
            executionResult =
                new ExecutionResultImpl(
                    Lists.newArrayList(new ErrorGraphQLSchemaUndefined()));
        } else {
            try {
                ExecutionInput.Builder executionInput =
                    ExecutionInput.newExecutionInput()
                        .query(query)
                        .context(graphQLContext);

                if (variables != null) {
                    executionInput.variables(variables);
                }

                if (StringUtils.hasText(operationName)) {
                    executionInput.operationName(operationName);
                }

                GraphQL gql =
                    GraphQL.newGraphQL(graphQLSchemaHolder.getGraphQLSchema())
                        .preparsedDocumentProvider(queryDocsCache::get)
                        .build();

                executionResult = gql.execute(executionInput);
            } catch (Exception ex) {
                LOGGER.error("Error occurred evaluating query: {}", query);
                LOGGER.error("", ex);
                executionResult =
                    new ExecutionResultImpl(
                        Lists.newArrayList(new ErrorGraphQLQueryEvaluation()));
            }
        }

        return executionResult;
    }

    private String getQueryKey() {
        return StringUtils.hasText(graphQLProperties.getServer().getQueryKey()) ?
                graphQLProperties.getServer().getQueryKey() : DEFAULT_QUERY_KEY;
    }

    private String getVariablesKey() {
        return StringUtils.hasText(graphQLProperties.getServer().getVariablesKey()) ?
                graphQLProperties.getServer().getVariablesKey() : DEFAULT_VARIABLES_KEY;
    }

    public GraphQLSchemaHolder getGraphQLSchemaContainer(String graphQLSchema) {
        if (StringUtils.hasText(graphQLSchema))
            return graphQLSchemaLocator.getGraphQLSchemaHolder(graphQLSchema);
        else if (graphQLSchemaLocator.getTotalNumberOfSchemas() == 1)
            return graphQLSchemaLocator.getSingleSchema();

        return null;
    }
}
