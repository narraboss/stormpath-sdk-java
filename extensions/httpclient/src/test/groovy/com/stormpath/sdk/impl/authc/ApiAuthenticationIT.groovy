/*
 * Copyright 2014 Stormpath, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stormpath.sdk.impl.authc

import com.stormpath.sdk.account.Account
import com.stormpath.sdk.account.AccountStatus
import com.stormpath.sdk.api.ApiKey
import com.stormpath.sdk.api.ApiKeyStatus
import com.stormpath.sdk.application.Application
import com.stormpath.sdk.authc.ApiAuthenticationResult
import com.stormpath.sdk.authc.AuthenticationResult
import com.stormpath.sdk.authc.AuthenticationResultVisitor
import com.stormpath.sdk.client.ClientIT
import com.stormpath.sdk.error.authc.DisabledAccountException
import com.stormpath.sdk.error.authc.DisabledApiKeyException
import com.stormpath.sdk.error.authc.IncorrectCredentialsException
import com.stormpath.sdk.error.authc.InvalidApiKeyException
import com.stormpath.sdk.error.authc.MissingApiKeyException
import com.stormpath.sdk.error.authc.OauthAuthenticationException
import com.stormpath.sdk.error.authc.UnsupportedAuthenticationSchemeException
import com.stormpath.sdk.http.HttpMethod
import com.stormpath.sdk.http.HttpRequestBuilder
import com.stormpath.sdk.http.HttpRequests
import com.stormpath.sdk.impl.error.ApiAuthenticationExceptionFactory
import com.stormpath.sdk.impl.oauth.http.OauthHttpServletRequest
import com.stormpath.sdk.impl.util.Base64
import com.stormpath.sdk.oauth.authc.BasicOauthAuthenticationResult
import com.stormpath.sdk.oauth.authc.BearerLocation
import com.stormpath.sdk.oauth.authc.OauthAuthenticationResult
import com.stormpath.sdk.oauth.authz.ScopeFactory
import com.stormpath.sdk.oauth.authz.TokenResponse
import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import static org.testng.Assert.*

/**
 * Class ApiAuthenticationIT is used for testing Api Authentication methods.
 *
 * @since 1.0.RC
 */
class ApiAuthenticationIT extends ClientIT {

    Application application

    Account account

    @BeforeMethod
    void initiateTest() {
        application = createTempApp()
        account = createTestAccount(application)
    }

    @Test
    void testBasicAuthentication() {

        def apiKey = account.createApiKey()

        def headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), "application/x-www-form-urlencoded")

        HttpRequestBuilder httpRequestBuilder = HttpRequests.method(HttpMethod.GET).headers(headers)

        attemptSuccessfulAuthentication(httpRequestBuilder.build(), DefaultApiAuthenticationResult)

        def newApiKey = account.createApiKey()

        httpRequestBuilder.headers(createHttpHeaders(createBasicAuthzHeader(newApiKey.id, newApiKey.secret), null))

        attemptSuccessfulAuthentication(httpRequestBuilder.build(), DefaultApiAuthenticationResult)

        apiKey.setStatus(ApiKeyStatus.DISABLED)
        apiKey.save()

        Map parameters = convertToParametersMap([:])

        httpRequestBuilder.headers(createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), null)).parameters(parameters)

        OauthHttpServletRequest servletRequest = new OauthHttpServletRequest(httpRequestBuilder.build())

        verifyError(servletRequest, DisabledApiKeyException)

        httpRequestBuilder.headers(createHttpHeaders(createBasicAuthzHeader(newApiKey.id, newApiKey.secret), null))

        servletRequest = new OauthHttpServletRequest(httpRequestBuilder.build())

        attemptSuccessfulAuthentication(servletRequest, DefaultApiAuthenticationResult)
    }

    @Test
    void testOauthAuthentication() {

        def apiKey = account.createApiKey()

        def parameters = convertToParametersMap(["grant_type": "client_credentials"])

        def headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), "application/x-www-form-urlencoded")

        HttpRequestBuilder httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)

        def result = (BasicOauthAuthenticationResult) attemptSuccessfulAuthentication(httpRequestBuilder.build(), BasicOauthAuthenticationResult)

        httpRequestBuilder.headers(createHttpHeaders(createBearerAuthzHeader(result.tokenResponse.accessToken), "application/json"))

        attemptSuccessfulAuthentication(httpRequestBuilder.build(), OauthAuthenticationResult)

        httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).queryParameters("grant_type=client_credentials")

        result = (BasicOauthAuthenticationResult) application.authenticate(httpRequestBuilder.build()).execute()

        httpRequestBuilder.headers(createHttpHeaders(createBearerAuthzHeader(result.tokenResponse.accessToken), "application/xml"))

        attemptSuccessfulAuthentication(httpRequestBuilder.build(), OauthAuthenticationResult)

        testWithScopeFactory(apiKey)
    }

    void testWithScopeFactory(ApiKey apiKey) {

        Set<String> applicationScopes = ["readResource", "deleteResource", "createResource", "updateResource"]

        def parameters = convertToParametersMap(["grant_type": "client_credentials", "scope": "readResource createResource", "anyParam": "ignored"])

        def headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), "application/x-www-form-urlencoded")

        HttpRequestBuilder httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)

        ScopeFactory myScopeFactory = createScopeFactory(applicationScopes, account, false)

        def authResult = application.authenticateOauth(httpRequestBuilder.build()).using(myScopeFactory).withTtl(120).execute()

        verifySuccessfulAuthentication(authResult, application, account, BasicOauthAuthenticationResult)

        assertNotNull authResult.scope

        assertEquals authResult.scope.size(), 2
        assertTrue authResult.scope.contains("readResource")
        assertTrue authResult.scope.contains("createResource")

        String accessToken = authResult.tokenResponse.accessToken

        String[] contentTypeArray = ["application/json"]
        headers = ["content-type": contentTypeArray]

        httpRequestBuilder = HttpRequests.method(HttpMethod.GET).queryParameters("access_token=" + accessToken).headers(headers)

        authResult = application.authenticateOauth(httpRequestBuilder.build()).inLocation(BearerLocation.QUERY_PARAM).execute()

        verifySuccessfulAuthentication(authResult, application, account, OauthAuthenticationResult)

        assertEquals authResult.scope.size(), 2
        assertTrue authResult.scope.contains("readResource")
        assertTrue authResult.scope.contains("createResource")

        parameters = convertToParametersMap(["access_token": accessToken])

        httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(["content-type": convertToArray("application/x-www-form-urlencoded")]).parameters(parameters)

        authResult = application.authenticateOauth(httpRequestBuilder.build()).inLocation(BearerLocation.BODY).execute()

        assertEquals authResult.scope.size(), 2
        assertTrue authResult.scope.contains("readResource")
        assertTrue authResult.scope.contains("createResource")

        verifySuccessfulAuthentication(authResult, application, account, OauthAuthenticationResult)
    }

    @Test
    void testApiKeyAuthenticationErrors() {

        def apiKey = account.createApiKey()

        HttpRequestBuilder httpRequestBuilder = HttpRequests.method(HttpMethod.GET).headers(["content-type": convertToArray("application/json")])

        verifyError(httpRequestBuilder.build(), MissingApiKeyException)

        httpRequestBuilder.headers(["Authorization": convertToArray("this-is-not-correct-cred")])

        verifyError(httpRequestBuilder.build(), MissingApiKeyException)

        httpRequestBuilder.headers(["Authorization": convertToArray("SAuthc MyUserAnd")])

        verifyError(httpRequestBuilder.build(), UnsupportedAuthenticationSchemeException)

        httpRequestBuilder.headers(["Authorization": convertToArray("Basic @#%^&*")])

        verifyError(httpRequestBuilder.build(), InvalidApiKeyException)

        httpRequestBuilder.headers(["Authorization": convertToArray("Basic this-is-not-correct")])

        verifyError(httpRequestBuilder.build(), InvalidApiKeyException)

        httpRequestBuilder.headers(createHttpHeaders(createBasicAuthzHeader(apiKey.id, ""), null))

        verifyError(httpRequestBuilder.build(), IncorrectCredentialsException)

        httpRequestBuilder.headers(createHttpHeaders(createBasicAuthzHeader(apiKey.id, "wrong-value"), null))

        verifyError(httpRequestBuilder.build(), IncorrectCredentialsException)

        httpRequestBuilder.headers(createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), null))

        attemptSuccessfulAuthentication(httpRequestBuilder.build(), DefaultApiAuthenticationResult)

        apiKey.setStatus(ApiKeyStatus.DISABLED)
        apiKey.save()

        verifyError(httpRequestBuilder.build(), DisabledApiKeyException)

        apiKey.setStatus(ApiKeyStatus.ENABLED)
        apiKey.save()

        account.setStatus(AccountStatus.DISABLED)
        account.save()

        verifyError(httpRequestBuilder.build(), DisabledAccountException)
    }

    @Test
    void testOauthAuthenticationErrors() {

        def apiKey = account.createApiKey()

        def parameters = convertToParametersMap(["grant_type": "client_credentials"])

        //Error: Content-Type: application/json
        def headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), "application/json")
        HttpRequestBuilder httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)
        verifyOauthError(httpRequestBuilder.build(), OauthAuthenticationException.INVALID_REQUEST)

        //Error: HttpMethod is GET
        headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), "application/x-www-form-urlencoded")
        httpRequestBuilder = HttpRequests.method(HttpMethod.GET).headers(headers).parameters(parameters)
        verifyOauthError(httpRequestBuilder.build(), OauthAuthenticationException.INVALID_REQUEST)

        headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, apiKey.secret), "application/x-www-form-urlencoded")

        //Error: grant_type: authorization_code is not supported.
        parameters = convertToParametersMap(["grant_type": "authorization_code", "code": "my_code", "redirect_uri": "http://myredirecturi.com"])
        httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)
        verifyOauthError(httpRequestBuilder.build(), OauthAuthenticationException.UNSUPPORTED_GRANT_TYPE)

        //Error: grant_type: password is not supported.
        parameters = convertToParametersMap(["grant_type": "password", "username": "myuser", "password": "aPassword"])
        httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)
        verifyOauthError(httpRequestBuilder.build(), OauthAuthenticationException.UNSUPPORTED_GRANT_TYPE)

        //Error: grant_type: refresh_token is not supported.
        parameters = convertToParametersMap(["grant_type": "refresh_token", "refresh_token": "myrefresh_token", "anyParam": "ignored"])
        httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)
        verifyOauthError(httpRequestBuilder.build(), OauthAuthenticationException.UNSUPPORTED_GRANT_TYPE)

        //Error: invalid apiKey secret.
        headers = createHttpHeaders(createBasicAuthzHeader(apiKey.id, "invalid"), "application/x-www-form-urlencoded")
        parameters = convertToParametersMap(["grant_type": "client_credentials", "anyParam": "ignored"])
        httpRequestBuilder = HttpRequests.method(HttpMethod.POST).headers(headers).parameters(parameters)
        verifyOauthError(httpRequestBuilder.build(), OauthAuthenticationException.INVALID_CLIENT)
    }

    def AuthenticationResult attemptSuccessfulAuthentication(Object httpRequest, Class expectedResultClass) {

        def authResult = application.authenticate(httpRequest).execute()

        verifySuccessfulAuthentication(authResult, application, account, expectedResultClass)

        authResult
    }

    static void verifySuccessfulAuthentication(AuthenticationResult authResult, Application expectedApp, Account expectedAccount, Class expectedResultClass) {

        assertTrue expectedResultClass.isAssignableFrom(authResult.class)

        assertEquals expectedAccount.href, authResult.account.href

        assertEquals expectedAccount.givenName, authResult.account.givenName

        authResult.accept(new AuthenticationResultVisitor() {
            @Override
            void visit(AuthenticationResult result) {
                fail("Error: This must never received in ApiAuthentication.")
            }

            @Override
            void visit(ApiAuthenticationResult result) {
                assertNotNull result.apiKey
            }

            @Override
            void visit(OauthAuthenticationResult result) {
                assertNotNull result.apiKey
            }

            @Override
            void visit(BasicOauthAuthenticationResult result) {

                TokenResponse tokenResponse = result.tokenResponse

                assertNotNull tokenResponse

                assertEquals tokenResponse.applicationHref, expectedApp.href
                assertNotNull tokenResponse.accessToken
                assertEquals 3, new StringTokenizer(tokenResponse.accessToken, ".").countTokens()
                assertEquals tokenResponse.tokenType, "Bearer"
                assertEquals tokenResponse.refreshToken, null
            }
        })
    }

    static ScopeFactory createScopeFactory(final Set<String> applicationScopes, final expectedAccount, boolean throwErrorOnInvalidRequestedScope) {

        return new ScopeFactory() {

            public Set<String> createScope(AuthenticationResult result, Set<String> requestedScopes) {
                assertEquals expectedAccount.href, result.account.href

                Set<String> resultScope = []
                for (String scope : requestedScopes) {
                    if (applicationScopes.contains(scope)) {
                        resultScope.add(scope)
                    } else if (throwErrorOnInvalidRequestedScope) {
                        throw ApiAuthenticationExceptionFactory.newOauthException(OauthAuthenticationException, OauthAuthenticationException.INVALID_SCOPE)
                    }
                }
                return resultScope
            };
        }
    }

    void verifyError(Object httpRequest, Class exceptionClass) {
        try {
            application.authenticate(httpRequest).execute()
            fail("ResourceException: " + exceptionClass.toString() + "was expected")
        } catch (com.stormpath.sdk.resource.ResourceException exception) {
            assertTrue exceptionClass.isAssignableFrom(exception.class);
        }
    }

    void verifyOauthError(Object httpRequest, String expectedOauthError) {
        try {
            application.authenticate(httpRequest).execute()
            fail("OathError: " + expectedOauthError + "was expected")
        } catch (OauthAuthenticationException exception) {

            assertEquals exception.getOauthError(), expectedOauthError
        }
    }

    def static String[] convertToArray(String value) {

        String[] array = [value]

        array
    }

    def static Map createHttpHeaders(String authzHeader, String contentType) {

        def headers = [:]
        String[] authzHeaderArray = [authzHeader]

        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/json"
        }

        String[] contentTypeArray = [contentType]
        headers.put("content-type", contentTypeArray)

        headers.put("authorization", authzHeaderArray)

        headers
    }

    def static Map convertToParametersMap(Map<String, String> inputMap) {

        Map<String, String[]> result = new HashMap<>()

        for (Map.Entry<String, String> entry : inputMap.entrySet()) {

            String[] value = [entry.value]

            result.put(entry.key, value)
        }

        result
    }

    def static String createBasicAuthzHeader(String id, String secret) {

        String cred = id + ":" + secret

        byte[] bytes = cred.getBytes("UTF-8")

        "Basic " + Base64.encodeToString(bytes, false)
    }

    def static String createBearerAuthzHeader(String accessToken) {

        "Bearer " + accessToken
    }

    def Account createTestAccount(Application app) {

        def email = 'deleteme@nowhere.com'

        Account account = client.instantiate(Account)
        account.givenName = 'John'
        account.surname = 'DELETEME'
        account.email = email
        account.password = 'Changeme1!'

        app.createAccount(account)
        deleteOnTeardown(account)

        return account
    }

}
