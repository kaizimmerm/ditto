/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.gateway.security.authentication.jwt;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotEmpty;
import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonParseException;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.services.utils.jwt.JjwtDeserializer;
import org.eclipse.ditto.signals.commands.base.exceptions.GatewayAuthenticationFailedException;
import org.eclipse.ditto.signals.commands.base.exceptions.GatewayJwtInvalidException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

/**
 * Abstract implementation of {@link JsonWebToken}.
 */
public abstract class AbstractJsonWebToken implements JsonWebToken {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractJsonWebToken.class);

    /**
     * Delimiter of the authorization string.
     */
    private static final String AUTHORIZATION_DELIMITER = " ";

    /**
     * Delimiter of the JSON Web Token.
     */
    private static final String JWT_DELIMITER = "\\.";

    private final String token;
    private final JsonObject header;
    private final JsonObject body;
    private final String signature;

    protected AbstractJsonWebToken(final String authorizationString) {
        token = getBase64EncodedToken(authorizationString);

        final String[] tokenParts = token.split(JWT_DELIMITER);
        final int expectedTokenPartAmount = 3;
        if (expectedTokenPartAmount != tokenParts.length) {
            throw GatewayJwtInvalidException.newBuilder()
                    .description("The token is expected to have three parts: header, payload and signature.")
                    .build();
        }
        header = tryToDecodeJwtPart(tokenParts[0]);
        body = tryToDecodeJwtPart(tokenParts[1]);
        signature = tokenParts[2];
    }

    private static String getBase64EncodedToken(final String authorizationString) {
        checkNotNull(authorizationString, "Authorization String");
        checkNotEmpty(authorizationString, "Authorization String");

        final String[] authorizationStringSplit = authorizationString.split(AUTHORIZATION_DELIMITER);
        if (2 != authorizationStringSplit.length) {
            throw GatewayAuthenticationFailedException.newBuilder("The Authorization Header is invalid!").build();
        }
        return authorizationStringSplit[1];
    }

    private static JsonObject tryToDecodeJwtPart(final String jwtPart) {
        try {
            return decodeJwtPart(jwtPart);
        } catch (final IllegalArgumentException | JsonParseException e) {
            throw GatewayJwtInvalidException.newBuilder()
                    .description("Check if your JSON Web Token has the correct format and is Base64 URL encoded.")
                    .cause(e)
                    .build();
        }
    }

    private static JsonObject decodeJwtPart(final String jwtPart) {
        final Base64.Decoder decoder = Base64.getDecoder();
        return JsonFactory.newObject(new String(decoder.decode(jwtPart), StandardCharsets.UTF_8));
    }

    protected AbstractJsonWebToken(final JsonWebToken jsonWebToken) {
        checkNotNull(jsonWebToken, "JSON Web Token");

        token = jsonWebToken.getToken();
        header = jsonWebToken.getHeader();
        body = jsonWebToken.getBody();
        signature = jsonWebToken.getSignature();
    }

    @Override
    public JsonObject getHeader() {
        return header;
    }

    @Override
    public JsonObject getBody() {
        return body;
    }

    @Override
    public String getToken() {
        return token;
    }

    @Override
    public String getKeyId() {
        return header.getValueOrThrow(JsonFields.KEY_ID);
    }

    @Override
    public String getIssuer() {
        return body.getValueOrThrow(JsonFields.ISSUER);
    }

    @Override
    public String getSignature() {
        return signature;
    }

    @Override
    public Audience getAudience() {
        final Optional<JsonValue> audience = body.getValue(JsonFields.AUDIENCE);
        return audience.map(Audience::fromJson).orElseGet(Audience::empty);
    }

    @Override
    public String getAuthorizedParty() {
        return body.getValue(JsonFields.AUTHORIZED_PARTY).orElseGet(String::new);
    }

    @Override
    public List<String> getScopes() {
        final String[] strings = body.getValue(JsonFields.SCOPE).map(s -> s.split(" ")).orElseGet(() -> new String[]{});
        return Arrays.stream(strings).collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<BinaryValidationResult> validate(final PublicKeyProvider publicKeyProvider) {
        final String issuer = getIssuer();
        final String keyId = getKeyId();
        return publicKeyProvider.getPublicKey(issuer, keyId)
                .thenApply(publicKeyOpt -> publicKeyOpt
                        .map(this::tryToValidatePublicKey)
                        .orElseGet(() -> {
                            final String msgPattern = "Public Key of issuer <{0}> with key ID <{1}> not found!";
                            final String msg = MessageFormat.format(msgPattern, issuer, keyId);
                            final Exception exception = GatewayAuthenticationFailedException.newBuilder(msg).build();
                            return BinaryValidationResult.invalid(exception);
                        }));
    }

    private BinaryValidationResult tryToValidatePublicKey(final Key publicKey) {
        try {
            return validatePublicKey(publicKey);
        } catch (final Exception e) {
            LOGGER.info("Failed to parse JWT!", e);
            return BinaryValidationResult.invalid(e);
        }
    }

    @SuppressWarnings("unchecked")
    private BinaryValidationResult validatePublicKey(final Key publicKey) {
        final JwtParser jwtParser = Jwts.parser();
        jwtParser.deserializeJsonWith(JjwtDeserializer.getInstance())
                .setSigningKey(publicKey)
                .parse(getToken());

        return BinaryValidationResult.valid();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AbstractJsonWebToken that = (AbstractJsonWebToken) o;
        return Objects.equals(token, that.token) &&
                Objects.equals(header, that.header) &&
                Objects.equals(body, that.body) &&
                Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, header, body, signature);
    }

    @Override
    public String toString() {
        return "token=" + token + ", header=" + header + ", body=" + body + ", signature=" + signature;
    }

}
